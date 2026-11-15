package com.leon.workflow.automation;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowEmailTest {
    @TempDir static Path temp;
    static SSLContext tls;
    JdbcTemplate jdbc;
    WorkflowStore store;
    WorkflowCredentials credentials;
    WorkflowEmail email;
    WorkflowEngine engine;

    @BeforeAll static void certificate() throws Exception {
        Path file = temp.resolve("smtp.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keystore", file.toString(),
                "-storetype", "PKCS12", "-storepass", "test-only-password", "-keypass", "test-only-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(file)) { store.load(in, "test-only-password".toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, "test-only-password".toCharArray());
        tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), null, null);
    }
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:email-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new WorkflowStore(jdbc); store.initialize();
        credentials = new WorkflowCredentials(jdbc, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        email = spy(new WorkflowEmail(credentials));
        engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), mock(WorkflowHttp.class), mock(WorkflowFeishu.class), email, mock(WorkflowGithubTrending.class), mock(WorkflowRss.class), mock(WorkflowAiSummary.class), mock(WorkflowQq.class));
    }
    @AfterEach void close() { engine.close(); email.close(); jdbc.execute("SHUTDOWN"); }
    Map<String, Object> smtp(int port, String security) { return Map.of("host", "localhost", "port", port, "security", security, "username", "sender@example.com", "password", "test-mail-authorization", "from", "sender@example.com"); }
    String credential(int port, String security) { return text(credentials.create(Map.of("name", "测试邮箱", "kind", "smtp", "value", smtp(port, security))).get("id")); }
    Map<String, Object> config(String id) { return new LinkedHashMap<>(Map.of("credentialId", id, "to", "{{ input.items[0].json.recipient }}", "cc", "copy@example.com", "bcc", "hidden@example.com", "subject", "通知：{{ input.items[0].json.name }}", "body", "你好，{{ input.items[0].json.literal }}", "format", "html", "timeoutMs", 3000)); }
    Map<String, Object> input() { return Map.of("items", List.of(Map.of("json", Map.of("recipient", "receiver@example.com", "name", "小李", "literal", "{{ input.items[0].json.name }}")))); }
    void allowLocalTestServer(boolean trust) throws Exception {
        doAnswer(call -> new WorkflowEmail.SmtpSockets(InetAddress.getLoopbackAddress())).when(email).sockets("localhost");
        if (trust) doAnswer(call -> {
            var sender = (org.springframework.mail.javamail.JavaMailSenderImpl)call.callRealMethod();
            sender.getJavaMailProperties().setProperty("mail.smtp.ssl.trust", "localhost"); return sender;
        }).when(email).sender(anyMap(), anyInt(), any());
    }
    String published(Map<String, Object> config) {
        var graph = new WorkflowGraph(List.of(new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()), new WorkflowGraph.Node("mail", "email", "发送邮件", config, Map.of()),
                new WorkflowGraph.Node("end", "end", "结束", Map.of(), Map.of())), List.of(new WorkflowGraph.Edge("one", "start", "mail", ""), new WorkflowGraph.Edge("two", "mail", "end", "")));
        String id = text(store.create("邮件通知流程").get("id")); store.save(id, "邮件通知流程", graph, 1); store.publish(id, 2, "{}"); return id;
    }
    Map<String, Object> await(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            var run = store.run(id);
            if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return run;
            Thread.sleep(10);
        }
        throw new AssertionError("邮件流程未完成");
    }

    @Test void sslAndStartTlsSendUtf8MimeOnceWithBccAndPassInputThroughPublishedEngine() throws Exception {
        allowLocalTestServer(true);
        for (String security : List.of("ssl", "starttls")) try (var server = new SmtpServer(security)) {
            String credential = credential(server.port(), security), workflow = published(config(credential));
            var run = await(text(engine.start(workflow, input()).get("id")));
            assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(input(), run.get("output"));
            MimeMessage message = server.message.get(3, TimeUnit.SECONDS);
            assertEquals("通知：小李", message.getSubject());
            assertEquals("你好，{{ input.items[0].json.name }}", message.getContent().toString().trim());
            assertTrue(message.isMimeType("text/html"));
            assertEquals("sender@example.com", message.getFrom()[0].toString());
            assertEquals("receiver@example.com", message.getRecipients(Message.RecipientType.TO)[0].toString());
            assertNull(message.getHeader("Bcc"));
            assertEquals(List.of("receiver@example.com", "copy@example.com", "hidden@example.com"), server.recipients);
            assertTrue(server.authenticated); assertEquals(1, server.submissions);
            assertFalse(write(run).contains("test-mail-authorization"));
            assertFalse(write(store.get(workflow)).contains("password"));
            assertFalse(write(credentials.list()).contains("sender@example.com"));
            assertFalse(jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, credential).contains("test-mail-authorization"));
        }
    }
    @Test void smtpWithoutStartTlsCannotReceiveCredentialsOrMessages() throws Exception {
        allowLocalTestServer(false);
        try (var server = new SmtpServer("plain")) {
            var config = config(credential(server.port(), "starttls"));
            var run = await(text(engine.start(published(config), input()).get("id")));
            assertEquals("FAILED", run.get("status")); assertEquals(0, server.submissions); assertFalse(server.authenticated);
            assertTrue(text(run.get("error")).contains("TLS"));
            assertFalse(write(run).contains("test-mail-authorization"));
            var nodes = (List<?>) run.get("nodes"); assertEquals("SKIPPED", object(nodes.getLast()).get("status"));
        }
    }
    @Test void invalidCertificatesAreRejectedAndDebugUsesTheSameTransport() throws Exception {
        allowLocalTestServer(false);
        try (var server = new SmtpServer("ssl")) {
            String id = credential(server.port(), "ssl");
            var node = new WorkflowGraph.Node("mail", "email", "发送邮件", config(id), Map.of());
            var error = assertThrows(IllegalArgumentException.class, () -> engine.test(node, input()));
            assertTrue(error.getMessage().contains("邮件提交未完成"));
            assertTrue(error.getMessage().contains("TLS"));
            assertFalse(server.authenticated); assertEquals(0, server.submissions);
        }
    }
    @Test void smtpAuthenticationFailurePersistsSpecificReasonsWithoutProviderRepliesOrSecrets() throws Exception {
        allowLocalTestServer(true);
        try (var server = new SmtpServer("ssl-auth-fail")) {
            var run = await(text(engine.start(published(config(credential(server.port(), "ssl"))), input()).get("id")));
            assertEquals("FAILED", run.get("status"));
            assertTrue(text(run.get("error")).contains("SMTP 认证失败"));
            assertTrue(text(run.get("error")).contains("535 5.7.8"));
            var failed = ((List<?>)run.get("nodes")).stream().map(WorkflowJson::object).filter(n -> "FAILED".equals(n.get("status"))).findFirst().orElseThrow();
            assertEquals(run.get("error"), failed.get("error"));
            assertFalse(write(run).contains("provider-private-payload")); assertFalse(write(run).contains("test-mail-authorization"));
            assertEquals(0, server.submissions);
        }
    }
    @Test void stalledSmtpTimesOutWithoutRetrying() throws Exception {
        allowLocalTestServer(false);
        try (var server = new SmtpServer("stall")) {
            var config = config(credential(server.port(), "starttls")); config.put("timeoutMs", 150);
            long start = System.nanoTime();
            assertThrows(IllegalArgumentException.class, () -> email.execute(config, input(), Map.of(), new WorkflowPrivacy()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
            assertFalse(server.authenticated); assertEquals(0, server.submissions);
        }
    }
    @Test void invalidRecipientsHeaderInjectionEmptyTemplatesAndInlineSecretsFailBeforeConnecting() throws Exception {
        var base = config(credential(465, "ssl"));
        for (var change : List.of(Map.of("to", "bad-address"), Map.of("to", "x@example.com\r\nBcc:leak@example.com"), Map.of("subject", "hello\nBcc: leak"),
                Map.of("to", "{{ input.items[0].json.missing }}"), Map.of("body", "{{ input.items[0].json.missing }}"), Map.of("password", "inline-secret"),
                Map.of("format", "invalid"), Map.of("cc", "bad"), Map.of("bcc", String.join(",", Collections.nCopies(101, "x@example.com"))))) {
            var config = new LinkedHashMap<>(base); config.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> email.execute(config, input(), Map.of(), new WorkflowPrivacy()));
        }
        verify(email, never()).sockets(anyString());
    }
    @Test void smtpSecretsRemainEncryptedAndCannotBeUsedAsHttpOrFeishuCredentials() throws Exception {
        String id = credential(465, "ssl"); var privacy = new WorkflowPrivacy();
        assertEquals("sender@example.com", credentials.smtp(id, privacy).get("from"));
        assertEquals("[已隐藏]", privacy.message("test-mail-authorization"));
        assertThrows(IllegalArgumentException.class, () -> credentials.headers(id, privacy));
        assertThrows(IllegalArgumentException.class, () -> credentials.feishu(id, privacy));
        assertThrows(IllegalArgumentException.class, () -> credentials.smtp("missing", privacy));
        String bearer = text(credentials.create(Map.of("name", "HTTP", "kind", "bearer", "value", Map.of("token", "test-token"))).get("id"));
        assertThrows(IllegalArgumentException.class, () -> credentials.smtp(bearer, privacy));
        for (var change : List.of(Map.of("security", "none"), Map.of("host", "smtp://localhost"), Map.of("from", "bad"), Map.of("password", ""), Map.of("port", 0))) {
            var value = new LinkedHashMap<>(smtp(465, "ssl")); value.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> credentials.create(Map.of("name", "无效", "kind", "smtp", "value", value)));
        }
        for (String host : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "localhost"))
            assertThrows(IOException.class, () -> new WorkflowEmail(credentials).sockets(host));
        // A numeric target is pinned to the validated address even if the caller supplies a different endpoint.
        try (var sockets = new WorkflowEmail.SmtpSockets(InetAddress.getLoopbackAddress()); var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var socket = sockets.createSocket(); socket.connect(InetSocketAddress.createUnresolved("unused.invalid", server.getLocalPort()), 500);
            assertTrue(socket.getInetAddress().isLoopbackAddress());
        }
    }
    @Test void blockedSmtpAddressesReportThatNoMailWasSent() throws Exception {
        var value = new LinkedHashMap<>(smtp(465, "ssl")); value.put("host", "127.0.0.1");
        String id = text(credentials.create(Map.of("name", "受限地址", "kind", "smtp", "value", value)).get("id"));
        var error = assertThrows(IllegalArgumentException.class, () -> email.execute(config(id), input(), Map.of(), new WorkflowPrivacy()));
        assertTrue(error.getMessage().contains("邮件未发送"));
        assertTrue(error.getMessage().contains("目标地址不允许访问"));
        assertFalse(error.getMessage().contains("可能已收到"));
    }

    /** Local protocol peer; TLS and AUTH must finish before it accepts an envelope. */
    static class SmtpServer implements AutoCloseable {
        final ServerSocket server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CompletableFuture<MimeMessage> message = new CompletableFuture<>();
        final List<String> recipients = new CopyOnWriteArrayList<>();
        volatile boolean authenticated;
        volatile int submissions;
        volatile Socket client;
        SmtpServer(String mode) throws IOException {
            server = mode.startsWith("ssl") ? tls.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress()) : new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            worker.submit(() -> {
                try (Socket accepted = server.accept()) {
                    client = accepted; accepted.setSoTimeout(5000);
                    if ("stall".equals(mode)) { accepted.getInputStream().read(); return; }
                    boolean encrypted = mode.startsWith("ssl");
                    BufferedReader in = reader(client); BufferedWriter out = writer(client); reply(out, "220 localhost SMTP");
                    for (String line; (line = in.readLine()) != null;) {
                        if (line.startsWith("EHLO")) {
                            reply(out, "250-localhost");
                            if ("starttls".equals(mode) && !encrypted) reply(out, "250-STARTTLS");
                            reply(out, "250 AUTH LOGIN");
                        } else if (line.equals("STARTTLS")) {
                            reply(out, "220 Ready for TLS");
                            var secure = (SSLSocket)tls.getSocketFactory().createSocket(client, "localhost", port(), true);
                            secure.setUseClientMode(false); secure.startHandshake(); client = secure; encrypted = true; in = reader(client); out = writer(client);
                        } else if (line.startsWith("AUTH")) {
                            if (!encrypted) throw new AssertionError("AUTH before TLS");
                            reply(out, "334 VXNlcm5hbWU6"); in.readLine(); reply(out, "334 UGFzc3dvcmQ6");
                            assertEquals("test-mail-authorization", new String(Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8));
                            if ("ssl-auth-fail".equals(mode)) { reply(out, "535 5.7.8 provider-private-payload test-mail-authorization"); continue; }
                            authenticated = true; reply(out, "235 Authenticated");
                        } else if (line.startsWith("MAIL FROM:")) {
                            if (!authenticated) throw new AssertionError("MAIL before AUTH"); reply(out, "250 OK");
                        } else if (line.startsWith("RCPT TO:")) {
                            recipients.add(line.substring(line.indexOf('<') + 1, line.indexOf('>'))); reply(out, "250 OK");
                        } else if (line.equals("DATA")) {
                            reply(out, "354 End with dot"); StringBuilder raw = new StringBuilder();
                            while ((line = in.readLine()) != null && !line.equals(".")) raw.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                            submissions++; message.complete(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.toString().getBytes(StandardCharsets.UTF_8)))); reply(out, "250 Queued");
                        } else if (line.equals("QUIT")) { reply(out, "221 Bye"); return; }
                        else reply(out, "250 OK");
                    }
                } catch (Throwable e) { message.completeExceptionally(e); }
            });
        }
        int port() { return server.getLocalPort(); }
        static BufferedReader reader(Socket socket) throws IOException { return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); }
        static BufferedWriter writer(Socket socket) throws IOException { return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)); }
        static void reply(BufferedWriter out, String line) throws IOException { out.write(line + "\r\n"); out.flush(); }
        @Override public void close() throws IOException { server.close(); if (client != null) client.close(); worker.shutdownNow(); }
    }
}
