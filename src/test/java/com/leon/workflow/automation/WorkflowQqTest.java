package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.IOException;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowQqTest {
    private static final String SECRET = "test-qq-app-secret", TOKEN = "test-qq-access-token", TARGET = "USER_OPENID_EXAMPLE_1234567890";
    JdbcTemplate jdbc;
    WorkflowStore store;
    WorkflowCredentials credentials;
    WorkflowHttp http;
    WorkflowQq qq;
    WorkflowEngine engine;
    String credentialId;
    List<Map<String, Object>> requests;
    Object authBody, sendBody;
    int sendStatus;
    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:qq-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new WorkflowStore(jdbc); store.initialize();
        credentials = new WorkflowCredentials(jdbc, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        credentialId = text(credentials.create(Map.of("name", "QQ 机器人", "kind", "qq", "value", Map.of("appId", "12345678", "appSecret", SECRET))).get("id"));
        http = mock(WorkflowHttp.class); qq = new WorkflowQq(credentials, http);
        engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, mock(WorkflowFeishu.class), mock(WorkflowEmail.class), mock(WorkflowGithubTrending.class), mock(WorkflowRss.class), mock(WorkflowAiSummary.class), qq);
        requests = new ArrayList<>(); authBody = Map.of("access_token", TOKEN, "expires_in", "7200"); sendBody = Map.of("id", "message-1"); sendStatus = 200;
        when(http.executeResolved(anyMap(), any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0); requests.add(request);
            boolean auth = text(request.get("url")).endsWith("/getAppAccessToken");
            return response(auth ? 200 : sendStatus, auth ? authBody : sendBody);
        });
    }
    @AfterEach void close() { engine.close(); jdbc.execute("SHUTDOWN"); }
    private Map<String, Object> config() { return new LinkedHashMap<>(Map.of("credentialId", credentialId, "targetType", "user", "targetId", TARGET, "messageType", "text", "text", "你好，{{ input.items[0].json.name }}", "timeoutMs", 10000)); }
    private Map<String, Object> input() { return Map.of("items", List.of(Map.of("json", Map.of("name", "leon")))); }
    private Map<String, Object> response(int status, Object body) { return Map.of("items", List.of(Map.of("json", Map.of("status", status, "body", body)))); }
    private Map<String, Object> run(Map<String, Object> config, Map<String, Object> input) throws Exception {
        var graph = new WorkflowGraph(List.of(new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()),
                new WorkflowGraph.Node("qq", "qq", "QQ 消息", config, Map.of()), new WorkflowGraph.Node("end", "end", "结束", Map.of(), Map.of())),
                List.of(new WorkflowGraph.Edge("one", "start", "qq", ""), new WorkflowGraph.Edge("two", "qq", "end", "")));
        String id = text(store.create("QQ 通知").get("id")); store.save(id, "QQ 通知", graph, 1); store.publish(id, 2, "{}");
        String runId = text(engine.start(id, input).get("id"));
        for (int i = 0; i < 500; i++) {
            var run = store.run(runId); if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return run;
            Thread.sleep(10);
        }
        throw new AssertionError("QQ 模拟流程未完成");
    }
    @Test void publishedNodeUsesOfficialApiAndOnlyPassesOriginalInputDownstream() throws Exception {
        var run = run(config(), input());
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(input(), run.get("output"));
        assertEquals(2, requests.size());
        assertEquals(WorkflowQq.API + "/app/getAppAccessToken", requests.getFirst().get("url"));
        assertEquals(Map.of("appId", "12345678", "clientSecret", SECRET), requests.getFirst().get("body"));
        var sent = requests.getLast();
        assertEquals(WorkflowQq.API + "/v2/users/" + TARGET + "/messages", sent.get("url"));
        assertEquals(Map.of("Authorization", "QQBot " + TOKEN), sent.get("headers"));
        assertEquals(Map.of("msg_type", 0, "content", "你好，leon"), sent.get("body"));
        assertFalse(write(run).contains(SECRET)); assertFalse(write(run).contains(TOKEN));
        assertTrue((int)sent.get("timeoutMs") <= (int)requests.getFirst().get("timeoutMs"));
    }
    @Test void trendingSummaryCanBeSentAsOneMessageThroughThePublishedQqNode() throws Exception {
        var analysis = Map.<String, Object>of("summary", "开发工具概览", "projects", List.of(Map.of("repo", "sample/tool", "purpose", "提升效率", "useCases", "开发工作流")));
        var report = WorkflowGithubTrending.report(analysis, new WorkflowGithubTrending.Page(1, List.of(Map.of("repo", "sample/tool", "url", "https://github.com/sample/tool"))));
        var config = config(); config.put("text", "{{ input.items[0].json.qqText }}");
        var run = run(config, report);
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(report, run.get("output"));
        var json = object(object(((List<?>)report.get("items")).getFirst()).get("json"));
        assertEquals(json.get("qqText"), object(requests.getLast().get("body")).get("content"));
        assertEquals(2, requests.size());
    }
    @Test void groupMarkdownAndTargetTemplatesResolveOnce() throws Exception {
        var config = config(); config.put("targetType", "group"); config.put("messageType", "markdown");
        config.put("targetId", "{{ input.items[0].json.group }}"); config.put("text", "{{ input.items[0].json.literal }}");
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("group", TARGET, "literal", "{{ input.items[0].json.noExpansion }}"))));
        assertEquals(input, qq.execute(config, input, Map.of(), new WorkflowPrivacy()));
        assertEquals(WorkflowQq.API + "/v2/groups/" + TARGET + "/messages", requests.getLast().get("url"));
        assertEquals(Map.of("msg_type", 2, "markdown", Map.of("content", "{{ input.items[0].json.noExpansion }}")), requests.getLast().get("body"));
    }
    @Test void bindingDiscoversOfficialGatewayWithAuthenticationAndRejectsOtherDestinations() throws Exception {
        authBody = Map.of("access_token", TOKEN, "expires_in", "7200");
        assertEquals(TOKEN, qq.bindingAccessToken(credentialId));
        requests.clear(); sendBody = Map.of("url", "wss://api.bot.qq.com/websocket/");
        assertEquals("wss://api.bot.qq.com/websocket/", qq.bindingGateway(TOKEN).toString());
        assertEquals("GET", requests.getLast().get("method"));
        assertEquals(WorkflowQq.API + "/gateway", requests.getLast().get("url"));
        assertEquals(Map.of("Authorization", "QQBot " + TOKEN), requests.getLast().get("headers"));
        for (String url : List.of("ws://api.bot.qq.com/websocket/", "wss://127.0.0.1/websocket/", "wss://api.bot.qq.com.evil.test/websocket/", "wss://user@api.bot.qq.com/websocket/", "wss://api.bot.qq.com:8443/websocket/", "wss://api.bot.qq.com/websocket/?token=private", "wss://api.bot.qq.com/other")) {
            sendBody = Map.of("url", url);
            var error = assertThrows(IllegalArgumentException.class, () -> qq.bindingGateway(TOKEN));
            assertFalse(error.getMessage().contains(url));
        }
    }
    @Test void bindingAcceptsTheActualLegacyGatewayWithoutATrailingSlash() throws Exception {
        for (String url : List.of("wss://api.sgroup.qq.com/websocket", "wss://api.sgroup.qq.com/websocket/", "wss://api.bot.qq.com/websocket")) {
            sendBody = Map.of("url", url);
            assertEquals(url, qq.bindingGateway(TOKEN).toString());
        }
        for (String url : List.of("wss://api.sgroup.qq.com.evil.test/websocket", "wss://api.sgroup.qq.com@evil.test/websocket", "ws://api.sgroup.qq.com/websocket",
                "wss://api.sgroup.qq.com:8443/websocket", "wss://api.sgroup.qq.com/websocket?token=private", "wss://api.sgroup.qq.com/websocket#fragment", "wss://api.sgroup.qq.com/other")) {
            sendBody = Map.of("url", url);
            assertThrows(IllegalArgumentException.class, () -> qq.bindingGateway(TOKEN));
        }
    }
    @Test void cacheReusesTokensButChangingCredentialSecretRefreshesBeforeNextSend() throws Exception {
        qq.execute(config(), input(), Map.of(), new WorkflowPrivacy());
        var privacy = new WorkflowPrivacy(); qq.execute(config(), input(), Map.of(), privacy);
        assertFalse(privacy.message(TOKEN).contains(TOKEN)); assertEquals(3, requests.size());
        credentials.update(credentialId, Map.of("value", Map.of("appSecret", "test-new-secret")));
        qq.execute(config(), input(), Map.of(), new WorkflowPrivacy()); assertEquals(5, requests.size());
        assertEquals("test-new-secret", object(requests.get(3).get("body")).get("clientSecret"));
        credentials.delete(credentialId);
        assertThrows(IllegalArgumentException.class, () -> qq.execute(config(), input(), Map.of(), new WorkflowPrivacy()));
        assertEquals(5, requests.size());
    }
    @Test void credentialsAreEncryptedEditableAndIsolatedFromOtherNodeTypes() {
        assertEquals(Map.of("appId", "12345678"), credentials.get(credentialId).get("value"));
        assertFalse(write(credentials.list()).contains(SECRET));
        assertFalse(jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, credentialId).contains(SECRET));
        credentials.update(credentialId, Map.of("name", "新名称", "value", Map.of("appSecret", "")));
        assertEquals(SECRET, credentials.qq(credentialId, new WorkflowPrivacy()).get("appSecret"));
        assertThrows(IllegalArgumentException.class, () -> credentials.headers(credentialId, new WorkflowPrivacy()));
        assertThrows(IllegalArgumentException.class, () -> credentials.feishu(credentialId, new WorkflowPrivacy()));
        String bearer = text(credentials.create(Map.of("name", "HTTP", "kind", "bearer", "value", Map.of("token", "test-token"))).get("id"));
        assertThrows(IllegalArgumentException.class, () -> credentials.qq(bearer, new WorkflowPrivacy()));
        for (var value : List.of(Map.of("appId", "evil/address", "appSecret", SECRET), Map.of("appId", "123", "appSecret", "bad secret")))
            assertThrows(IllegalArgumentException.class, () -> credentials.update(credentialId, Map.of("value", value)));
        assertEquals(SECRET, credentials.qq(credentialId, new WorkflowPrivacy()).get("appSecret"));
    }
    @Test void noNewItemsSkipsBothTokenAndMessageRequests() throws Exception {
        var empty = Map.<String, Object>of("items", List.of()); var run = run(config(), empty);
        assertEquals("SUCCEEDED", run.get("status")); assertEquals(empty, run.get("output"));
        verifyNoInteractions(http);
    }
    @Test void missingTemplatesOversizedUtf8AndInvalidTargetsFailBeforeHttp() {
        for (String target : List.of("123456789", "https://example.com/escape", "../escape", "abcd")) {
            var c = config(); c.put("targetId", target);
            assertThrows(IllegalArgumentException.class, () -> qq.execute(c, input(), Map.of(), new WorkflowPrivacy()));
        }
        for (String content : List.of("你".repeat(667), "{{ input.items[0].json.missing }}", "{{ input.items[0].json.empty }}")) {
            var c = config(); c.put("text", content);
            assertThrows(IllegalArgumentException.class, () -> qq.execute(c, input(), Map.of(), new WorkflowPrivacy()));
        }
        for (String key : List.of("url", "appSecret", "headers", "access_token")) {
            var c = config(); c.put(key, "inline-value");
            assertThrows(IllegalArgumentException.class, () -> WorkflowQq.validateConfig(c));
        }
        verifyNoInteractions(http);
    }
    @Test void byteLimitAllowsBoundaryAndResolvesTargetBeforeSending() throws Exception {
        var c = config(); c.put("text", "你".repeat(666) + "ab"); qq.execute(c, input(), Map.of(), new WorkflowPrivacy());
        assertEquals(2000, text(object(requests.getLast().get("body")).get("content")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        requests.clear(); c.put("targetId", "{{ input.items[0].json.missing }}");
        assertThrows(IllegalArgumentException.class, () -> qq.execute(c, input(), Map.of(), new WorkflowPrivacy())); assertTrue(requests.isEmpty());
    }
    @Test void authBusinessErrorsAndMalformedTokensNeverSendMessages() throws Exception {
        for (Object body : List.of(Map.of("code", 100016, "message", SECRET), Map.of("expires_in", 7200), Map.of("access_token", TOKEN, "expires_in", 0), Map.of("access_token", TOKEN, "expires_in", "bad"), "bad")) {
            authBody = body; requests.clear();
            var error = assertThrows(IllegalArgumentException.class, () -> qq.execute(config(), input(), Map.of(), new WorkflowPrivacy()));
            assertFalse(error.getMessage().contains(SECRET)); assertEquals(1, requests.size());
        }
    }
    @Test void providerRejectionsNeverRetryAndDoNotSaveRawReply() throws Exception {
        for (int code : List.of(40034105, 40054004, 40054013, 40034100, 40034006, 40034127, 999)) {
            requests.clear(); sendBody = Map.of("code", code, "message", SECRET + TOKEN + "private-recipient");
            var run = run(config(), input());
            assertEquals("FAILED", run.get("status")); assertTrue(text(run.get("error")).contains(String.valueOf(code)));
            assertFalse(write(run).contains(SECRET)); assertFalse(write(run).contains(TOKEN)); assertFalse(write(run).contains("private-recipient"));
            assertEquals(2, requests.size());
            assertTrue(write(run).contains("SKIPPED"));
        }
    }
    @Test void malformedSendHttpFailureAndUnknownNetworkOutcomeAreNotReportedAsSuccessOrRetried() throws Exception {
        for (Object body : List.of(Map.of(), Map.of("code", "bad"), Map.of("code", 0), "bad")) {
            requests.clear(); sendBody = body;
            assertThrows(IllegalArgumentException.class, () -> qq.execute(config(), input(), Map.of(), new WorkflowPrivacy())); assertEquals(2, requests.size());
        }
        sendStatus = 503; sendBody = Map.of("id", "misleading-success"); requests.clear();
        assertThrows(IllegalArgumentException.class, () -> qq.execute(config(), input(), Map.of(), new WorkflowPrivacy())); assertEquals(2, requests.size());
        when(http.executeResolved(anyMap(), any())).thenAnswer(call -> {
            var request = object(call.getArgument(0));
            if (text(request.get("url")).endsWith("/getAppAccessToken")) return response(200, authBody);
            throw new IOException("timeout " + SECRET + TOKEN);
        });
        clearInvocations(http); var run = run(config(), input()); assertEquals("FAILED", run.get("status"));
        assertFalse(write(run).contains(SECRET)); assertFalse(write(run).contains(TOKEN));
        verify(http, times(2)).executeResolved(anyMap(), any());
    }
}
