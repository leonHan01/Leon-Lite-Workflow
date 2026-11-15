package com.leon.workflow.automation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Short-lived, outbound-only discovery. Never persists QQ events or exposes bot tokens. */
@Service
public class WorkflowQqBindings implements AutoCloseable {
    private static final long TTL = 120_000;
    private final WorkflowCredentials credentials;
    private final WorkflowQq qq;
    private final Gateway gateway;
    private final LongSupplier clock;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("qq-binding-", 0).factory());
    private boolean closed;
    interface Connection extends AutoCloseable {
        Map<String, Object> receive(long timeoutMs) throws Exception;
        void send(Map<String, Object> event) throws Exception;
        @Override void close();
    }
    interface Gateway extends AutoCloseable {
        Connection open(URI url) throws Exception;
        @Override default void close() {}
    }
    @Autowired public WorkflowQqBindings(WorkflowCredentials credentials, WorkflowQq qq) {
        this(credentials, qq, new OfficialGateway(), System::currentTimeMillis);
    }
    WorkflowQqBindings(WorkflowCredentials credentials, WorkflowQq qq, Gateway gateway, LongSupplier clock) {
        this.credentials = credentials; this.qq = qq; this.gateway = gateway; this.clock = clock;
    }
    private static final class Binding {
        final String id = UUID.randomUUID().toString(), credentialId, targetType, code;
        final Map<String, Object> credential;
        final long expiresAt;
        String status = "CONNECTING", targetId = "", error = "";
        volatile Future<?> task;
        Connection connection;
        Binding(String credentialId, String targetType, Map<String, Object> credential, long expiresAt) {
            this.credentialId = credentialId; this.targetType = targetType; this.credential = credential; this.expiresAt = expiresAt;
            byte[] random = new byte[16]; new SecureRandom().nextBytes(random); code = "qq-bind:" + HexFormat.of().formatHex(random);
        }
        synchronized boolean active() { return Set.of("CONNECTING", "WAITING").contains(status); }
        synchronized Map<String, Object> snapshot() {
            return Map.of("id", id, "credentialId", credentialId, "targetType", targetType, "status", status, "expiresAt", expiresAt,
                    "code", "WAITING".equals(status) ? code : "", "targetId", targetId, "error", error);
        }
        synchronized boolean attach(Connection value) {
            if (!active()) { value.close(); return false; }
            connection = value; return true;
        }
        synchronized void finish(String state, String target, String message, boolean interrupt) {
            if (!active()) return;
            status = state; targetId = target; error = message;
            if (connection != null) { connection.close(); connection = null; }
            if (interrupt && task != null) task.cancel(true);
        }
        synchronized void invalidate() {
            if ("BOUND".equals(status)) { status = "FAILED"; targetId = ""; error = "机器人凭据已修改或删除，请重新开始绑定"; }
            else finish("FAILED", "", "机器人凭据已修改或删除，请重新开始绑定", true);
        }
    }
    public Map<String, Object> start(Map<String, Object> request) {
        String id = required(request.get("credentialId"), "请选择 QQ 机器人凭据后再绑定", 100);
        String type = text(request.getOrDefault("targetType", "user"));
        if (!Set.of("user", "group").contains(type)) throw new IllegalArgumentException("请选择 QQ 单聊或 QQ 群聊");
        var credential = credentials.qq(id, new WorkflowPrivacy());
        synchronized (bindings) {
            prune();
            if (closed) throw new IllegalArgumentException("服务正在停止，请稍后重新绑定");
            if (bindings.values().stream().anyMatch(b -> b.active() && b.credential.get("appId").equals(credential.get("appId"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此机器人正在绑定，请先取消已有绑定或等待两分钟后重试");
            if (bindings.values().stream().filter(Binding::active).count() >= 2)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "同时最多绑定两个机器人，请稍后再试");
            while (bindings.size() >= 32) bindings.remove(bindings.values().stream().filter(b -> !b.active()).findFirst().orElseThrow().id);
            Binding b = new Binding(id, type, credential, clock.getAsLong() + TTL);
            bindings.put(b.id, b); b.task = workers.submit(() -> listen(b));
            return b.snapshot();
        }
    }
    public Map<String, Object> get(String id) {
        Binding b = find(id);
        if (!current(b)) b.invalidate();
        return b.snapshot();
    }
    public Map<String, Object> cancel(String id) {
        Binding b = find(id); b.finish("CANCELLED", "", "绑定已取消", true); return b.snapshot();
    }
    private Binding find(String id) {
        synchronized (bindings) {
            prune(); Binding b = bindings.get(id);
            if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "绑定已失效，请重新开始绑定");
            return b;
        }
    }
    private void prune() {
        long now = clock.getAsLong();
        for (Binding b : bindings.values()) if (now >= b.expiresAt) b.finish("EXPIRED", "", "绑定已超时，请重新生成绑定码", true);
        bindings.values().removeIf(b -> !b.active() && now >= b.expiresAt + 60_000);
    }
    private boolean current(Binding b) {
        try { return b.credential.equals(credentials.qq(b.credentialId, new WorkflowPrivacy())); }
        catch (RuntimeException error) { return false; }
    }
    private void listen(Binding b) {
        try {
            String token = qq.bindingAccessToken(b.credentialId);
            if (!b.active() || !current(b)) { b.finish("FAILED", "", "机器人凭据已修改或删除，请重新开始绑定", false); return; }
            try (Connection connection = gateway.open(qq.bindingGateway(token))) {
                if (!b.attach(connection)) return;
                Long sequence = null;
                boolean identified = false, ready = false;
                long interval = 0, nextBeat = Long.MAX_VALUE, hardDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TTL);
                while (b.active() && !Thread.currentThread().isInterrupted()) {
                    long now = System.nanoTime(), remaining = Math.min(b.expiresAt - clock.getAsLong(), TimeUnit.NANOSECONDS.toMillis(hardDeadline - now));
                    if (remaining <= 0) { b.finish("EXPIRED", "", "绑定已超时，请重新生成绑定码", false); return; }
                    if (now >= nextBeat) {
                        Map<String, Object> beat = new LinkedHashMap<>(); beat.put("op", 1); beat.put("d", sequence); connection.send(beat);
                        nextBeat = now + interval;
                    }
                    long wait = Math.min(remaining, interval == 0 ? 10_000 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nextBeat - System.nanoTime())));
                    Map<String, Object> event = connection.receive(wait);
                    if (event == null) continue;
                    if (event.get("s") instanceof Number seq) sequence = seq.longValue();
                    int op = integer(event.get("op"), -1, 0, 13);
                    if (op == 10) {
                        if (identified) throw new IOException("重复握手");
                        interval = TimeUnit.MILLISECONDS.toNanos(integer(object(event.get("d")).get("heartbeat_interval"), 0, 1000, 120000));
                        connection.send(Map.of("op", 2, "d", Map.of("token", "QQBot " + token, "intents", 1 << 25, "shard", List.of(0, 1), "properties", Map.of())));
                        identified = true; nextBeat = System.nanoTime();
                    } else if (op == 7 || op == 9) {
                        b.finish("FAILED", "", "QQ 拒绝连接或要求重连，请检查机器人事件权限和启用状态后重试", false); return;
                    } else if (op == 0 && "READY".equals(event.get("t")) && identified) {
                        ready = true;
                        synchronized (b) { if (b.active()) b.status = "WAITING"; }
                    } else if (ready) {
                        String target = target(event, b.code, b.targetType);
                        if (target != null) {
                            if (clock.getAsLong() >= b.expiresAt) b.finish("EXPIRED", "", "绑定已超时，请重新生成绑定码", false);
                            else if (!current(b)) b.finish("FAILED", "", "机器人凭据已修改或删除，请重新开始绑定", false);
                            else b.finish("BOUND", target, "", false);
                            return;
                        }
                    }
                }
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            b.finish("FAILED", "", "QQ 绑定连接未完成，请检查机器人 AppID、AppSecret、事件权限和服务器网络后重试", false);
        }
    }
    static String target(Map<String, Object> event, String code, String type) {
        if (!Integer.valueOf(0).equals(event.get("op")) || !(event.get("d") instanceof Map<?, ?> data) || !(data.get("content") instanceof String content)) return null;
        Object id;
        if ("user".equals(type) && "C2C_MESSAGE_CREATE".equals(event.get("t")) && code.equals(content.trim()) && data.get("author") instanceof Map<?, ?> author)
            id = author.get("user_openid");
        else if ("group".equals(type) && Set.of("GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE").contains(text(event.get("t")))
                && code.equals(Arrays.stream(content.trim().split("\\s+")).reduce((a, v) -> v).orElse(""))) id = data.get("group_openid");
        else return null;
        return id instanceof String value && value.matches("[A-Za-z0-9_-]{16,128}") && !value.matches("[0-9]+") ? value : null;
    }
    @PreDestroy @Override public void close() {
        synchronized (bindings) {
            closed = true; bindings.values().forEach(b -> b.finish("CANCELLED", "", "服务已停止，请重新绑定", true)); bindings.clear();
        }
        workers.shutdownNow(); gateway.close();
    }
    private static final class OfficialGateway implements Gateway {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        public Connection open(URI url) throws Exception {
            Socket connection = new Socket();
            var future = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(url, connection);
            try { connection.socket = future.get(10, TimeUnit.SECONDS); return connection; }
            catch (Exception error) { connection.close(); future.thenAccept(WebSocket::abort); future.cancel(true); throw error; }
        }
        public void close() { client.shutdownNow(); }
    }
    static final class Socket implements Connection, WebSocket.Listener {
        private final BlockingQueue<Object> events = new ArrayBlockingQueue<>(32);
        private final StringBuilder frame = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean closed;
        public void onOpen(WebSocket ws) { socket = ws; if (closed) ws.abort(); else ws.request(1); }
        public synchronized CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (closed) return null;
            try {
                if (frame.length() + data.length() > 262_144) throw new IOException("QQ 事件超过大小限制");
                frame.append(data);
                if (last) {
                    var event = read(frame.toString()); frame.setLength(0);
                    if (!events.offer(event)) throw new IOException("QQ 事件过于频繁");
                }
                ws.request(1);
            } catch (Exception error) { fail(); }
            return null;
        }
        public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) { fail(); return null; }
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { fail(); return null; }
        public void onError(WebSocket ws, Throwable error) { fail(); }
        private void fail() { close(); }
        public Map<String, Object> receive(long timeoutMs) throws Exception {
            Object event = events.poll(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (event instanceof Exception error) throw error;
            return event == null ? null : object(event);
        }
        public void send(Map<String, Object> event) throws Exception { socket.sendText(write(event), true).get(5, TimeUnit.SECONDS); }
        public synchronized void close() {
            if (closed) return;
            closed = true; frame.setLength(0); events.clear(); events.offer(new IOException("QQ 网关连接中断"));
            if (socket != null) socket.abort();
        }
    }
}
