package com.leon.workflow.automation.runner;

import groovy.lang.GroovyShell;
import groovy.lang.Binding;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Standalone entrypoint. The parent copies only this class and language-runtime jars into the OS sandbox. */
public final class ScriptRunnerMain {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        LimitedOutput logs = new LimitedOutput(16_384);
        System.setOut(new PrintStream(logs, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(logs, true, StandardCharsets.UTF_8));
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            byte[] bytes = System.in.readNBytes(1_200_001);
            if (bytes.length > 1_200_000) throw new IllegalArgumentException("脚本请求过大");
            Map<?, ?> request = JSON.readValue(bytes, Map.class);
            String source = (String) request.get("source");
            Object output;
            if ("javascript".equals(request.get("language"))) {
                try (Context context = Context.newBuilder("js").allowAllAccess(false)
                        .allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false)
                        .allowIO(IOAccess.NONE).allowCreateThread(false).allowNativeAccess(false)
                        .allowEnvironmentAccess(EnvironmentAccess.NONE).out(logs).err(logs)
                        .option("engine.WarnInterpreterOnly", "false").build()) {
                    Value input = context.eval("js", "JSON.parse").execute(JSON.writeValueAsString(request.get("input")));
                    Value ctx = context.eval("js", "JSON.parse").execute(JSON.writeValueAsString(request.get("ctx")));
                    context.eval(Source.newBuilder("js", source + "\n;main;", "workflow.js").buildLiteral());
                    Value main = context.getBindings("js").getMember("main");
                    if (main == null || !main.canExecute()) throw new IllegalArgumentException("需要定义 main(input, ctx) 函数");
                    Value result = main.execute(input, ctx);
                    String serialized = context.eval("js", "JSON.stringify").execute(result).asString();
                    if (serialized == null || serialized.length() > 1_048_576) throw new IllegalArgumentException("脚本返回值无效或超过 1 MB");
                    output = JSON.readValue(serialized, Object.class);
                }
            } else if ("groovy".equals(request.get("language"))) {
                // Compilation is inside the same OS sandbox, including any compile-time transformations.
                CompilerConfiguration config = new CompilerConfiguration();
                config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
                try (var loader = new groovy.lang.GroovyClassLoader(ScriptRunnerMain.class.getClassLoader(), config)) {
                    var shell = new GroovyShell(loader, new Binding(), config);
                    var script = shell.parse(source, "workflow.groovy");
                    output = script.invokeMethod("main", new Object[]{request.get("input"), request.get("ctx")});
                    // Force serialization inside the sandbox; arbitrary guest objects never cross into the server.
                    String serialized = JSON.writeValueAsString(output);
                    if (serialized.length() > 1_048_576) throw new IllegalArgumentException("脚本返回值超过 1 MB");
                    output = JSON.readValue(serialized, Object.class);
                }
            } else throw new IllegalArgumentException("不支持的脚本语言");
            response.put("output", output); response.put("success", true);
        } catch (Throwable error) {
            String message = error.getMessage();
            response.put("success", false);
            response.put("error", message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 900)));
        }
        response.put("logs", logs.toString(StandardCharsets.UTF_8));
        protocol.print(JSON.writeValueAsString(response)); protocol.flush();
    }
    public static final class LimitedOutput extends ByteArrayOutputStream {
        private final int limit;
        public LimitedOutput(int limit) { this.limit = limit; }
        @Override public synchronized void write(int b) { if (count < limit) super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { super.write(b, off, Math.min(len, limit - count)); }
    }
}
