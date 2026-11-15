package com.leon.workflow.automation;

import jakarta.annotation.PreDestroy;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.leon.workflow.automation.WorkflowJson.*;

/** One TLS-protected SMTP submission per execution; no retry after an uncertain delivery. */
@Component
public class WorkflowEmail {
    private final WorkflowCredentials credentials;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("workflow-email-deadline").factory());
    public WorkflowEmail(WorkflowCredentials credentials) { this.credentials = credentials; }

    static Map<String, Object> credential(Map<String, Object> value) {
        String host = required(value.get("host"), "请填写 SMTP 服务器地址", 253).trim();
        if (!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) throw new IllegalArgumentException("SMTP 服务器请填写域名或 IPv4 地址，不包含协议或端口");
        String security = text(value.getOrDefault("security", "ssl"));
        if (!Set.of("ssl", "starttls").contains(security)) throw new IllegalArgumentException("SMTP 必须使用 SSL/TLS 或 STARTTLS");
        int port = integer(value.get("port"), "ssl".equals(security) ? 465 : 587, 1, 65535);
        String username = required(value.get("username"), "请填写 SMTP 用户名", 1000);
        String password = required(value.get("password"), "请填写邮箱密码或授权码", 8192);
        String from = required(value.get("from"), "请填写发件人邮箱", 1000);
        if (addresses(from, "发件人", true).length != 1) throw new IllegalArgumentException("只能配置一个发件人邮箱");
        return Map.of("host", host, "port", port, "security", security, "username", username, "password", password, "from", from);
    }

    static void validateConfig(Map<String, Object> config) {
        required(config.get("credentialId"), "请选择 SMTP 邮箱凭据", 100);
        required(config.get("to"), "请填写收件人邮箱", 8192);
        required(config.get("subject"), "请填写邮件主题", 1000);
        required(config.get("body"), "请填写邮件正文", MAX_BYTES);
        if (!Set.of("text", "html").contains(text(config.getOrDefault("format", "text")))) throw new IllegalArgumentException("请选择纯文本或 HTML 正文");
        integer(config.get("timeoutMs"), 10_000, 100, 30_000);
        for (String key : List.of("host", "port", "username", "password", "secret", "from", "security"))
            if (config.containsKey(key)) throw new IllegalArgumentException("SMTP 连接信息和密码请保存在邮箱凭据中");
        for (String key : List.of("to", "cc", "bcc")) {
            String value = text(config.get(key));
            if (!value.contains("{{")) addresses(value, key, "to".equals(key));
        }
        header(text(config.get("subject")), "邮件主题");
    }

    static InternetAddress[] addresses(String value, String label, boolean required) {
        header(value, label);
        if (value.isBlank()) {
            if (required) throw new IllegalArgumentException("请填写" + label + "邮箱");
            return new InternetAddress[0];
        }
        try {
            InternetAddress[] result = InternetAddress.parse(value.replace('；', ',').replace(';', ','), true);
            if (result.length == 0 || result.length > 100) throw new IllegalArgumentException("每个收件人字段需要 1–100 个邮箱");
            for (var address : result) {
                address.validate();
                if (address.isGroup() || !address.getAddress().contains("@")) throw new IllegalArgumentException("请填写有效的" + label + "邮箱");
            }
            return result;
        } catch (jakarta.mail.internet.AddressException e) { throw new IllegalArgumentException(label + "邮箱格式无效，多个邮箱用逗号分隔"); }
    }
    private static void header(String value, String label) {
        if (value.length() > 8192 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException(label + "不能包含换行或超过长度限制");
    }

    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> input, Map<String, Object> ctx, WorkflowPrivacy privacy) throws Exception {
        validateConfig(config);
        var credential = credentials.smtp(text(config.get("credentialId")), privacy);
        InternetAddress[] to = addresses(text(WorkflowExpressions.resolveRequired(config.get("to"), input, ctx, "邮件收件人")), "收件人", true);
        InternetAddress[] cc = addresses(resolve(config.get("cc"), input, ctx), "抄送", false);
        InternetAddress[] bcc = addresses(resolve(config.get("bcc"), input, ctx), "密送", false);
        if (to.length + cc.length + bcc.length > 100) throw new IllegalArgumentException("一封邮件最多发送至 100 个邮箱");
        String subject = required(WorkflowExpressions.resolveRequired(config.get("subject"), input, ctx, "邮件主题"), "邮件主题解析后不能为空", 1000);
        header(subject, "邮件主题");
        String body = required(WorkflowExpressions.resolveRequired(config.get("body"), input, ctx, "邮件正文"), "邮件正文解析后不能为空", MAX_BYTES);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("邮件正文不能超过 1 MB");
        int timeout = integer(config.get("timeoutMs"), 10_000, 100, 30_000);
        SmtpSockets transport;
        try { transport = sockets(text(credential.get("host"))); }
        catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new IllegalArgumentException("邮件未发送：" + WorkflowNetworkErrors.describe(e, true, false));
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        try (var sockets = transport) {
            JavaMailSenderImpl sender = sender(credential, timeout, sockets);
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(addresses(text(credential.get("from")), "发件人", true)[0]);
            message.setRecipients(Message.RecipientType.TO, to);
            message.setRecipients(Message.RecipientType.CC, cc);
            message.setRecipients(Message.RecipientType.BCC, bcc);
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8", "html".equals(config.get("format")) ? "html" : "plain");
            var deadline = deadlines.schedule(() -> { timedOut.set(true); sockets.close(); }, timeout, TimeUnit.MILLISECONDS);
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                sender.send(message);
            } finally { deadline.cancel(false); }
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new IllegalArgumentException("邮件提交未完成：" + WorkflowNetworkErrors.describe(e, true, timedOut.get())
                    + "\n部分收件人可能已收到，请确认后重试。");
        }
        return input;
    }
    private String resolve(Object value, Map<String, Object> input, Map<String, Object> ctx) { return text(WorkflowExpressions.resolve(value, input, ctx)); }

    JavaMailSenderImpl sender(Map<String, Object> credential, int timeout, SocketFactory sockets) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(text(credential.get("host"))); sender.setPort(((Number)credential.get("port")).intValue());
        sender.setUsername(text(credential.get("username"))); sender.setPassword(text(credential.get("password"))); sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        boolean ssl = "ssl".equals(credential.get("security"));
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(!ssl));
        props.setProperty("mail.smtp.starttls.required", String.valueOf(!ssl));
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(timeout));
        props.setProperty("mail.smtp.timeout", String.valueOf(timeout));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(timeout));
        props.setProperty("mail.smtp.sendpartial", "false");
        props.setProperty("mail.smtp.quitwait", "false");
        props.setProperty("mail.smtp.socketFactory.fallback", "false");
        props.put("mail.smtp.socketFactory", sockets);
        props.setProperty("mail.debug", "false");
        return sender;
    }
    SmtpSockets sockets(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (var address : addresses) if (!WorkflowHttp.publicAddress(address)) throw new IOException("目标地址不允许访问");
        return new SmtpSockets(addresses[0]);
    }
    /** Pin the validated IP while retaining the original hostname for TLS certificate verification. */
    static final class SmtpSockets extends SocketFactory implements AutoCloseable {
        private final Socket socket;
        SmtpSockets(InetAddress address) {
            socket = new Socket() {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    super.connect(new InetSocketAddress(address, ((InetSocketAddress)endpoint).getPort()), timeout);
                }
            };
        }
        @Override public Socket createSocket() { return socket; }
        @Override public Socket createSocket(String host, int port) throws IOException { socket.connect(new InetSocketAddress(host, port)); return socket; }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return createSocket(host.getHostAddress(), port); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { socket.bind(new InetSocketAddress(local, localPort)); return createSocket(host, port); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return createSocket(host.getHostAddress(), port, local, localPort); }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
    @PreDestroy void close() { deadlines.shutdownNow(); }
}
