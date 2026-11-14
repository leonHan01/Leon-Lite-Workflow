package com.leon.workflow.automation;

import com.leon.workflow.automation.runner.ScriptRunnerMain;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Fails closed if the operating-system sandbox is unavailable. No shell command or app environment is passed to scripts. */
@Component
public class ScriptProcessRunner {
    public record Result(Map<String, Object> output, String logs) {}
    public static final class ScriptFailure extends IllegalArgumentException {
        private final String logs;
        public ScriptFailure(String message, String logs) { super(message); this.logs = logs; }
        public String logs() { return logs; }
    }
    private static final String MAIN = "com.leon.workflow.automation.runner.ScriptRunnerMain";
    private static final String LIBRARIES = "(?:polyglot|js-language|truffle-api|truffle-runtime|truffle-compiler|regex|icu4j|collections|nativeimage|word|jniutils|groovy|jackson-databind|jackson-core|jackson-annotations)-[0-9].*\\.jar";
    private Path runtime;
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    public String sandbox() {
        if (System.getProperty("os.name").startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) return "macOS Sandbox";
        if (Files.isExecutable(Path.of("/usr/bin/bwrap")) && Files.isExecutable(Path.of("/usr/bin/setpriv"))) return "bubblewrap";
        return "unavailable";
    }
    public Result execute(String language, String source, Map<String, Object> input, Map<String, Object> ctx, int timeoutMs) throws Exception {
        if ("unavailable".equals(sandbox())) throw new IllegalArgumentException("脚本隔离运行环境尚未安装，请联系管理员");
        Path directory = prepare();
        List<String> command = sandboxCommand(directory);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear(); builder.environment().put("LANG", "en_US.UTF-8"); builder.environment().put("HOME", "/nonexistent");
        Process process = builder.start(); active.add(process);
        try {
            CompletableFuture<byte[]> stdout = read(process.getInputStream(), MAX_BYTES + 20_000);
            CompletableFuture<byte[]> stderr = read(process.getErrorStream(), 8192);
            try (var in = process.getOutputStream()) {
                in.write(write(Map.of("language", language, "source", source, "input", input, "ctx", ctx)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) { throw new IllegalArgumentException("脚本进程启动失败，请检查服务器隔离环境"); }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalArgumentException("脚本执行超时（" + timeoutMs + " ms）");
            byte[] bytes = stdout.get(2, TimeUnit.SECONDS);
            stderr.get(2, TimeUnit.SECONDS); // Drain bounded stderr, but never include launch diagnostics or paths in run logs.
            if (process.exitValue() != 0 || bytes.length == 0) throw new IllegalArgumentException("脚本进程未能完成；请检查隔离环境及内存限制");
            Map<String, Object> response = WorkflowJson.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!Boolean.TRUE.equals(response.get("success"))) throw new ScriptFailure(required(response.get("error"), "脚本执行失败", 1000), text(response.get("logs")));
            return new Result(envelope(response.get("output")), text(response.get("logs")));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw error;
        } finally { kill(process); active.remove(process); }
    }
    private CompletableFuture<byte[]> read(InputStream stream, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream; var out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > limit) throw new IllegalArgumentException("脚本输出超过限制");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            } catch (IOException e) { throw new CompletionException(e); }
        });
    }
    private List<String> sandboxCommand(Path directory) throws IOException {
        Path jdk = Path.of(System.getProperty("java.home")).toRealPath();
        List<String> command = new ArrayList<>();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        if (mac) {
            // No file writes, network, process forks, or access to the workspace/home directory.
            String profile = "(version 1)(deny default)(allow process-exec (literal \"" + escaped(jdk.resolve("bin/java")) + "\"))"
                    + "(allow process-info*)(allow signal (target self))(allow sysctl-read)(allow mach-lookup)"
                    + "(allow file-read-metadata)(allow file-read-data (vnode-type DIRECTORY))"
                    + "(allow file-read* (subpath \"" + escaped(jdk) + "\") (subpath \"" + escaped(directory) + "\")"
                    + " (subpath \"/System/Library\") (subpath \"/usr/lib\") (subpath \"/private/var/db/dyld\")"
                    + " (literal \"/dev/random\") (literal \"/dev/urandom\") (literal \"/dev/null\") (literal \"/dev/dtracehelper\"))";
            command.addAll(List.of("/usr/bin/sandbox-exec", "-p", profile, jdk.resolve("bin/java").toString()));
        } else {
            // The HTTP service inherits CAP_NET_BIND_SERVICE for port 80. Bubblewrap refuses ambient capabilities;
            // remove them only from the worker launch chain, leaving the parent service's privileges unchanged.
            command.addAll(List.of("/usr/bin/setpriv", "--no-new-privs", "--inh-caps=-all", "--ambient-caps=-all",
                    "/usr/bin/bwrap", "--die-with-parent", "--new-session", "--unshare-all", "--cap-drop", "ALL",
                    "--ro-bind", jdk.toString(), "/jdk", "--ro-bind", directory.toString(), "/runtime", "--proc", "/proc", "--dev", "/dev",
                    "--tmpfs", "/tmp", "--chdir", "/runtime"));
            for (String path : List.of("/lib", "/lib64", "/usr/lib", "/usr/lib64")) if (Files.exists(Path.of(path))) command.addAll(List.of("--ro-bind", path, path));
            // Distribution JDKs (including Dragonwell/RHEL) link timezone/security resources outside java.home.
            // Bind individual public JDK resources, never the surrounding /etc or /usr/share directories.
            for (Path resource : externalJdkResources(jdk)) command.addAll(List.of("--ro-bind", resource.toString(), resource.toString()));
            command.add("/jdk/bin/java");
        }
        command.addAll(List.of("-Xmx128m", "-Xss512k", "-XX:MaxMetaspaceSize=96m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1",
                "-XX:+UseSerialGC", "-XX:-UsePerfData", "-XX:+DisableAttachMechanism", "-Dgroovy.grape.enable=false", "-Djava.io.tmpdir=/tmp",
                "-Duser.home=/nonexistent", "-Dpolyglot.engine.WarnInterpreterOnly=false", "-cp", (mac ? directory : Path.of("/runtime")) + "/*", MAIN));
        return command;
    }
    private String escaped(Path path) { return path.toString().replace("\\", "\\\\").replace("\"", "\\\""); }
    static Set<Path> externalJdkResources(Path jdk) throws IOException {
        List<String> names = new ArrayList<>(List.of("lib/tzdb.dat", "conf/security/java.security", "conf/security/java.policy",
                "conf/net.properties", "conf/logging.properties", "lib/security/default.policy", "lib/security/blocked.certs",
                "lib/security/cacerts", "lib/security/public_suffix_list.dat"));
        for (String policy : List.of("limited", "unlimited"))
            for (String name : List.of("default_local.policy", "default_US_export.policy", "exempt_local.policy"))
                names.add("conf/security/policy/" + policy + "/" + name);
        Set<Path> resources = new LinkedHashSet<>();
        for (String name : names) {
            Path file = jdk.resolve(name);
            if (Files.isRegularFile(file)) {
                Path real = file.toRealPath();
                if (!real.startsWith(jdk)) resources.add(real);
            }
        }
        return resources;
    }
    private synchronized Path prepare() throws IOException {
        if (runtime != null) return runtime;
        Path dir = Files.createTempDirectory("leon-script-runtime-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        try {
            try (var jar = new JarOutputStream(Files.newOutputStream(dir.resolve("runner.jar")))) {
                for (String name : List.of(MAIN, MAIN + "$LimitedOutput")) {
                    String resource = name.replace('.', '/') + ".class";
                    jar.putNextEntry(new JarEntry(resource));
                    try (var in = ScriptRunnerMain.class.getClassLoader().getResourceAsStream(resource)) {
                        if (in == null) throw new IOException("Missing worker class"); in.transferTo(jar);
                    }
                    jar.closeEntry();
                }
            }
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path path = Path.of(entry);
                if (!Files.isRegularFile(path) || !entry.endsWith(".jar")) continue;
                String name = path.getFileName().toString();
                if (name.matches(LIBRARIES)) Files.copy(path, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                else try (JarFile jar = new JarFile(path.toFile())) {
                    for (JarEntry nested : Collections.list(jar.entries())) {
                        if (!nested.getName().startsWith("BOOT-INF/lib/")) continue;
                        String nestedName = nested.getName().substring("BOOT-INF/lib/".length());
                        if (nestedName.matches(LIBRARIES)) try (var in = jar.getInputStream(nested)) { Files.copy(in, dir.resolve(nestedName), StandardCopyOption.REPLACE_EXISTING); }
                    }
                }
            }
            try (var files = Files.list(dir)) {
                if (files.noneMatch(p -> p.getFileName().toString().startsWith("js-language-"))) throw new IOException("Language runtime jars not found");
            }
            runtime = dir; return dir;
        } catch (Exception e) { remove(dir); throw e; }
    }
    private static void kill(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void remove(Path dir) {
        try (var files = Files.walk(dir)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException ignored) {}
    }
    @PreDestroy public void close() { active.forEach(ScriptProcessRunner::kill); if (runtime != null) remove(runtime); }
}
