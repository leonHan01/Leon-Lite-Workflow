package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.leon.workflow.automation.WorkflowJson.*;

class WorkflowQqBindingsTest {
    static final String SECRET = "fake-qq-app-secret", TOKEN = "fake-qq-access-token", TARGET = "QQ_USER_OPENID_TEST_1234567890";
    JdbcTemplate jdbc;
    WorkflowCredentials credentials;
    WorkflowQq qq;
    WorkflowQqBindings bindings;
    FakeGateway gateway;
    AtomicLong clock;
    String credentialId;
    static final class FakeConnection implements WorkflowQqBindings.Connection {
        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<>();
        final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        volatile IOException failure;
        FakeConnection() {
            events.add(Map.of("op", 10, "d", Map.of("heartbeat_interval", 1000)));
            events.add(Map.of("op", 0, "s", 1, "t", "READY"));
        }
        public Map<String, Object> receive(long timeout) throws Exception {
            if (failure != null) throw failure;
            return events.poll(timeout, TimeUnit.MILLISECONDS);
        }
        public void send(Map<String, Object> event) { sent.add(event); }
        public void close() { closed = true; }
    }
    static final class FakeGateway implements WorkflowQqBindings.Gateway {
        final List<FakeConnection> connections = new CopyOnWriteArrayList<>();
        public WorkflowQqBindings.Connection open(URI url) {
            assertEquals(URI.create("wss://api.bot.qq.com/websocket/"), url);
            var connection = new FakeConnection(); connections.add(connection); return connection;
        }
    }
    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:qq-bind-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        var store = new WorkflowStore(jdbc); store.initialize();
        credentials = new WorkflowCredentials(jdbc, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        credentialId = credential("12345678"); qq = mock(WorkflowQq.class);
        when(qq.bindingAccessToken(anyString())).thenReturn(TOKEN);
        when(qq.bindingGateway(TOKEN)).thenReturn(URI.create("wss://api.bot.qq.com/websocket/"));
        gateway = new FakeGateway(); clock = new AtomicLong(System.currentTimeMillis());
        bindings = new WorkflowQqBindings(credentials, qq, gateway, clock::get);
    }
    @AfterEach void close() { bindings.close(); jdbc.execute("SHUTDOWN"); }
    String credential(String appId) {
        return text(credentials.create(Map.of("name", "QQ 机器人", "kind", "qq", "value", Map.of("appId", appId, "appSecret", SECRET))).get("id"));
    }
    Map<String, Object> start(String type) { return bindings.start(Map.of("credentialId", credentialId, "targetType", type)); }
    Map<String, Object> await(String id, String state) throws Exception {
        for (int i = 0; i < 600; i++) {
            var result = bindings.get(id); if (state.equals(result.get("status"))) return result;
            if (Set.of("FAILED", "EXPIRED", "CANCELLED").contains(result.get("status"))) fail(write(result));
            Thread.sleep(5);
        }
        throw new AssertionError("QQ 模拟绑定未完成：" + write(bindings.get(id)));
    }
    Map<String, Object> event(String type, String content) {
        return Map.of("op", 0, "t", type, "d", Map.of("content", content, "author", Map.of("user_openid", TARGET), "group_openid", "QQ_GROUP_OPENID_TEST_1234567890"));
    }
    @Test void privateBindingUsesOneTimeChallengeAndOnlyReturnsMatchedOpenId() throws Exception {
        String id = text(start("user").get("id")); var waiting = await(id, "WAITING");
        String code = text(waiting.get("code")); assertTrue(code.matches("qq-bind:[0-9a-f]{32}"));
        assertFalse(write(waiting).contains(SECRET)); assertFalse(write(waiting).contains(TOKEN));
        var connection = gateway.connections.getFirst();
        assertTrue(connection.sent.stream().anyMatch(e -> Integer.valueOf(2).equals(e.get("op")) && object(e.get("d")).get("token").equals("QQBot " + TOKEN)));
        connection.events.add(event("C2C_MESSAGE_CREATE", "private unrelated content"));
        connection.events.add(event("GROUP_AT_MESSAGE_CREATE", code));
        connection.events.add(event("C2C_MESSAGE_CREATE", code));
        var result = await(id, "BOUND");
        assertEquals(TARGET, result.get("targetId")); assertEquals("", result.get("code")); assertTrue(connection.closed);
        assertFalse(write(result).contains("private unrelated content")); assertFalse(write(result).contains(SECRET)); assertFalse(write(result).contains(TOKEN));
        verify(qq, times(1)).bindingAccessToken(credentialId);
        verify(qq, times(1)).bindingGateway(TOKEN);
        verifyNoMoreInteractions(qq); // Binding never sends a QQ notification.
    }
    @Test void groupBindingCapturesOnlyGroupOpenIdAndDoesNotAcceptPrivateChallenge() throws Exception {
        String id = text(start("group").get("id")); String code = text(await(id, "WAITING").get("code"));
        var connection = gateway.connections.getFirst();
        connection.events.add(event("C2C_MESSAGE_CREATE", code));
        connection.events.add(event("GROUP_AT_MESSAGE_CREATE", "<@12345678> " + code));
        var bound = await(id, "BOUND"); assertEquals("QQ_GROUP_OPENID_TEST_1234567890", bound.get("targetId"));
        assertEquals("group", bound.get("targetType"));
    }
    @Test void wrongCodesAndChannelEventsCannotBind() {
        String code = "qq-bind:unique-challenge";
        for (var event : List.of(event("C2C_MESSAGE_CREATE", "prefix " + code), event("MESSAGE_CREATE", code), event("C2C_MESSAGE_CREATE", "old-code"), Map.<String, Object>of("op", 0)))
            assertNull(WorkflowQqBindings.target(event, code, "user"));
        assertNull(WorkflowQqBindings.target(Map.of("op", 0, "t", "C2C_MESSAGE_CREATE", "d", Map.of("content", code, "author", Map.of("user_openid", "12345678"))), code, "user"));
    }
    @Test void cancellationAndExpiryDisconnectAndLateMessagesCannotBind() throws Exception {
        String id = text(start("user").get("id")); String oldCode = text(await(id, "WAITING").get("code"));
        assertEquals("CANCELLED", bindings.cancel(id).get("status")); assertTrue(gateway.connections.getFirst().closed);
        gateway.connections.getFirst().events.add(event("C2C_MESSAGE_CREATE", oldCode));
        assertEquals("", bindings.get(id).get("targetId"));
        String second = text(start("user").get("id")); var waiting = await(second, "WAITING");
        assertNotEquals(oldCode, waiting.get("code"));
        clock.addAndGet(120001);
        assertEquals("EXPIRED", bindings.get(second).get("status")); assertTrue(gateway.connections.getLast().closed);
        assertEquals("", bindings.get(second).get("targetId"));
        clock.addAndGet(60000);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> bindings.get(second)).getStatusCode().value());
    }
    @Test void changedOrDeletedCredentialsInvalidateWaitingAndCompletedBindings() throws Exception {
        String first = text(start("user").get("id")); await(first, "WAITING");
        credentials.update(credentialId, Map.of("value", Map.of("appSecret", "replacement-secret")));
        assertEquals("FAILED", bindings.get(first).get("status")); assertTrue(gateway.connections.getFirst().closed);
        String second = text(start("user").get("id")); String code = text(await(second, "WAITING").get("code"));
        gateway.connections.getLast().events.add(event("C2C_MESSAGE_CREATE", code)); await(second, "BOUND");
        credentials.delete(credentialId);
        var invalidated = bindings.get(second); assertEquals("FAILED", invalidated.get("status")); assertEquals("", invalidated.get("targetId"));
    }
    @Test void concurrentStartsForSameBotAreRejectedAndGlobalConnectionsAreBounded() throws Exception {
        String first = text(start("user").get("id")); await(first, "WAITING");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> start("user")).getStatusCode().value());
        String alias = credential("12345678");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> bindings.start(Map.of("credentialId", alias))).getStatusCode().value());
        String secondCredential = credential("23456789"), thirdCredential = credential("34567890");
        String second = text(bindings.start(Map.of("credentialId", secondCredential)).get("id")); await(second, "WAITING");
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> bindings.start(Map.of("credentialId", thirdCredential))).getStatusCode().value());
        bindings.cancel(first);
        String third = text(bindings.start(Map.of("credentialId", thirdCredential)).get("id")); await(third, "WAITING");
        assertEquals(3, gateway.connections.size());
    }
    @Test void authenticationAndGatewayFailuresNeverReturnSecretsOrProviderBodies() throws Exception {
        when(qq.bindingAccessToken(credentialId)).thenThrow(new IOException(SECRET + TOKEN));
        String id = text(start("user").get("id"));
        for (int i = 0; i < 600 && !"FAILED".equals(bindings.get(id).get("status")); i++) Thread.sleep(5);
        var result = bindings.get(id); assertEquals("FAILED", result.get("status"));
        assertFalse(write(result).contains(SECRET)); assertFalse(write(result).contains(TOKEN)); assertTrue(gateway.connections.isEmpty());
        doReturn(TOKEN).when(qq).bindingAccessToken(credentialId);
        String second = text(start("user").get("id")); await(second, "WAITING");
        gateway.connections.getFirst().events.add(Map.of("op", 9, "d", SECRET + TOKEN));
        for (int i = 0; i < 600 && !"FAILED".equals(bindings.get(second).get("status")); i++) Thread.sleep(5);
        assertEquals("FAILED", bindings.get(second).get("status")); assertTrue(gateway.connections.getFirst().closed);
        assertFalse(write(bindings.get(second)).contains(SECRET));
    }
    @Test void shutdownClosesActiveConnectionsAndInvalidRequestsNeverOpenSockets() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> bindings.start(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> bindings.start(Map.of("credentialId", credentialId, "targetType", "channel")));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> bindings.get("missing")).getStatusCode().value());
        assertTrue(gateway.connections.isEmpty());
        String id = text(start("user").get("id")); await(id, "WAITING"); bindings.close();
        assertTrue(gateway.connections.getFirst().closed);
        assertThrows(IllegalArgumentException.class, () -> start("user"));
    }
    @Test void socketParsesFragmentedEventsAndClosingReleasesQueuedMessagesAndWakesReaders() throws Exception {
        var socket = new WorkflowQqBindings.Socket(); var ws = mock(WebSocket.class); socket.onOpen(ws);
        socket.onText(ws, "{\"op\":", false); socket.onText(ws, "11}", true);
        assertEquals(Map.of("op", 11), socket.receive(10));
        socket.onText(ws, "{\"op\":0,\"d\":\"private queued content\"}", true);
        socket.close(); socket.close();
        var error = assertThrows(IOException.class, () -> socket.receive(10));
        assertFalse(error.getMessage().contains("private")); verify(ws, times(1)).abort();
    }
    @Test void invalidOversizeAndBinaryFramesCloseSocketWithoutReturningRawPayloads() {
        for (String frame : List.of("not-json-private-content", "x".repeat(262145))) {
            var socket = new WorkflowQqBindings.Socket(); var ws = mock(WebSocket.class); socket.onOpen(ws);
            socket.onText(ws, frame, true);
            var error = assertThrows(IOException.class, () -> socket.receive(10));
            assertFalse(error.getMessage().contains(frame)); verify(ws).abort();
        }
        var socket = new WorkflowQqBindings.Socket(); var ws = mock(WebSocket.class); socket.onOpen(ws);
        socket.onBinary(ws, java.nio.ByteBuffer.wrap(new byte[]{1}), true);
        assertThrows(IOException.class, () -> socket.receive(10)); verify(ws).abort();
    }
}
