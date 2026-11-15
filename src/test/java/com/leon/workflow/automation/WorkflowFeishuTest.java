package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.leon.workflow.automation.WorkflowJson.*;

class WorkflowFeishuTest {
    private static final String URL = "https://open.feishu.cn/open-apis/bot/v2/hook/sample-webhook-id";
    private static final String SECRET = "sample-signing-secret";
    JdbcTemplate jdbc;
    WorkflowStore store;
    WorkflowCredentials credentials;
    WorkflowHttp http;
    WorkflowFeishu feishu;
    WorkflowEngine engine;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:feishu-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new WorkflowStore(jdbc); store.initialize();
        credentials = new WorkflowCredentials(jdbc, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        http = mock(WorkflowHttp.class); feishu = new WorkflowFeishu(credentials, http);
        engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, feishu, mock(WorkflowEmail.class), mock(WorkflowGithubTrending.class));
    }
    @AfterEach void close() { engine.close(); jdbc.execute("SHUTDOWN"); }
    private String credential(String secret) {
        return text(credentials.create(Map.of("name", "测试飞书机器人", "kind", "feishu", "value", Map.of("webhookUrl", URL, "secret", secret))).get("id"));
    }
    private Map<String, Object> config(String id) { return new LinkedHashMap<>(Map.of("credentialId", id, "messageType", "text", "text", "你好，{{ input.items[0].json.name }}", "timeoutMs", 10000)); }
    private Map<String, Object> input() { return Map.of("items", List.of(Map.of("json", Map.of("name", "leon")))); }
    private Map<String, Object> response(Object body) { return Map.of("items", List.of(Map.of("json", Map.of("status", 200, "body", body)))); }
    private String published(Map<String, Object> config) {
        var graph = new WorkflowGraph(List.of(new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()),
                new WorkflowGraph.Node("notify", "feishu", "飞书消息", config, Map.of()), new WorkflowGraph.Node("end", "end", "结束", Map.of(), Map.of())),
                List.of(new WorkflowGraph.Edge("one", "start", "notify", ""), new WorkflowGraph.Edge("two", "notify", "end", "")));
        String id = text(store.create("飞书通知流程").get("id"));
        store.save(id, "飞书通知流程", graph, 1); store.publish(id, 2, "{}"); return id;
    }
    private Map<String, Object> await(String runId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var run = store.run(runId);
            if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return run;
            Thread.sleep(10);
        }
        throw new AssertionError("模拟飞书流程未完成");
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private Map<String, Object> sent() throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class)Map.class);
        verify(http).executeResolved(captor.capture(), any(WorkflowPrivacy.class)); return captor.getValue();
    }
    @Test void publishedNodeSendsOneTextMessageWithEncryptedCredentialsAndPassesInputToTheEnd() throws Exception {
        String credential = credential("");
        when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("code", 0)));
        String workflow = published(config(credential));
        var run = await(text(engine.start(workflow, input()).get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(input(), run.get("output"));
        var request = sent(); assertEquals("POST", request.get("method")); assertEquals(URL, request.get("url"));
        assertEquals(Map.of("msg_type", "text", "content", Map.of("text", "你好，leon")), request.get("body"));
        @SuppressWarnings("unchecked") var nodes = (List<Map<String, Object>>)run.get("nodes");
        assertTrue(nodes.stream().allMatch(n -> "SUCCEEDED".equals(n.get("status"))));
        assertTrue(text(nodes.get(1).get("logs")).contains("飞书消息已发送"));
        assertTrue(text(nodes.get(1).get("logs")).contains("首项字段=[name]"));
        assertFalse(write(store.get(workflow)).contains("sample-webhook-id")); assertFalse(write(run).contains(URL));
        String encrypted = jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, credential);
        assertFalse(encrypted.contains(URL)); assertFalse(write(credentials.list()).contains("webhookUrl"));
    }
    @Test void missingTrendingFieldsMustNotSendOnlyTheNotificationPrefix() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("code", 0)));
        var config = config(credential("")); config.put("text", "工作流通知：{{ input.items[0].json.name }}");
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("feishuText", "项目分析正文", "emailBody", "邮件分析正文"))));
        var error = assertThrows(IllegalArgumentException.class, () -> feishu.execute(config, input, Map.of(), new WorkflowPrivacy()));
        assertTrue(error.getMessage().contains("input.items[0].json.name"));
        verifyNoInteractions(http);
    }
    @Test void trendingMarkdownBecomesASignedStyledCard() throws Exception {
        var config = config(credential(SECRET));
        config.put("messageType", "card"); config.put("title", "{{ input.items[0].json.title }}"); config.put("text", "{{ input.items[0].json.feishuMarkdown }}");
        var report = WorkflowGithubTrending.report(Map.of("summary", "开发工具概览", "projects", List.of(Map.of("repo", "sample/tool", "purpose", "提升开发效率", "useCases", "日常开发"))),
                new WorkflowGithubTrending.Page(1, List.of(Map.of("repo", "sample/tool", "url", "https://github.com/sample/tool"))));
        when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("code", 0)));
        assertEquals(report, feishu.execute(config, report, Map.of(), new WorkflowPrivacy()));
        var payload = object(sent().get("body")); var card = object(payload.get("card"));
        assertEquals("interactive", payload.get("msg_type"));
        assertEquals("blue", object(card.get("header")).get("template"));
        var markdown = object(((List<?>)object(card.get("body")).get("elements")).getFirst());
        assertTrue(text(markdown.get("content")).contains("**用途：** 提升开发效率"));
        assertTrue(text(markdown.get("content")).contains("<text_tag color='blue'>周榜</text_tag>"));
        assertFalse(text(payload.get("sign")).isBlank());
    }
    @Test void cardMessagesUseSigningAndResolveTemplatesOnlyOnce() throws Exception {
        String id = credential(SECRET); var config = config(id);
        config.put("messageType", "card"); config.put("title", "通知：{{ input.items[0].json.name }}"); config.put("text", "{{ input.items[0].json.literal }}");
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("name", "leon", "literal", "{{ input.items[0].json.password }}", "password", "do-not-expand"))));
        when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("code", 0)));
        var privacy = new WorkflowPrivacy(); assertEquals(input, feishu.execute(config, input, Map.of(), privacy));
        var payload = object(sent().get("body"));
        var card = object(payload.get("card"));
        assertEquals("interactive", payload.get("msg_type")); assertEquals("2.0", card.get("schema"));
        assertEquals("通知：leon", object(object(card.get("header")).get("title")).get("content"));
        var markdown = object(((List<?>)object(card.get("body")).get("elements")).getFirst());
        assertEquals("{{ input.items[0].json.password }}", markdown.get("content"));
        assertFalse(write(payload).contains("do-not-expand"));
        assertTrue(Math.abs(Long.parseLong(text(payload.get("timestamp"))) - java.time.Instant.now().getEpochSecond()) < 10);
        assertFalse(text(payload.get("sign")).isBlank());
        assertFalse(privacy.message(URL + SECRET + payload.get("sign")).contains(SECRET));
        assertEquals("l1N0gAcBjdwBvGm1xMjOF0XSyaLRpR7tuO5dHfhAYc8=", WorkflowFeishu.sign("demo", "1599360473"));
    }
    @Test void businessErrorsAndMalformedSuccessResponsesFailInsteadOfReportingSent() throws Exception {
        var config = config(credential(SECRET));
        for (int code : List.of(19021, 19022, 19024, 11232, 999)) {
            when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("code", code, "msg", URL + SECRET)));
            var error = assertThrows(IllegalArgumentException.class, () -> feishu.execute(config, input(), Map.of(), new WorkflowPrivacy()));
            assertFalse(error.getMessage().contains(URL)); assertFalse(error.getMessage().contains(SECRET));
        }
        for (Object body : List.of("", Map.of(), Map.of("code", "invalid"))) {
            when(http.executeResolved(anyMap(), any())).thenReturn(response(body));
            assertThrows(IllegalArgumentException.class, () -> feishu.execute(config, input(), Map.of(), new WorkflowPrivacy()));
        }
        when(http.executeResolved(anyMap(), any())).thenReturn(response(Map.of("StatusCode", 0)));
        assertEquals(input(), feishu.execute(config, input(), Map.of(), new WorkflowPrivacy()));
    }
    @Test void errorsEchoingWebhookOrSigningSecretsAreRedactedAndTheEndIsSkipped() throws Exception {
        String id = credential(SECRET);
        when(http.executeResolved(anyMap(), any())).thenThrow(new IOException("failed " + URL + " " + SECRET));
        var run = await(text(engine.start(published(config(id)), input()).get("id")));
        assertEquals("FAILED", run.get("status"));
        assertFalse(write(run).contains("sample-webhook-id")); assertFalse(write(run).contains(SECRET));
        assertTrue(text(run.get("error")).contains("[已隐藏]"));
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>)run.get("nodes");
        assertEquals("FAILED", steps.get(1).get("status")); assertEquals("SKIPPED", steps.get(2).get("status"));
        verify(http, times(1)).executeResolved(anyMap(), any());
    }
    @Test void missingOrOversizedMessagesAndInlineSecretsAreRejectedBeforeSending() throws Exception {
        var config = config(credential(""));
        for (String message : List.of("{{ input.items[0].json.missing }}", "你".repeat(7000))) {
            config.put("text", message);
            assertThrows(IllegalArgumentException.class, () -> feishu.execute(config, input(), Map.of(), new WorkflowPrivacy()));
        }
        config.put("text", "消息"); config.put("webhookUrl", URL);
        assertThrows(IllegalArgumentException.class, () -> WorkflowFeishu.validateConfig(config));
        verifyNoInteractions(http);
    }
    @Test void onlyFeishuHttpsHooksAreAcceptedAndCredentialKindsCannotBeMixed() {
        for (String url : List.of("http://open.feishu.cn/open-apis/bot/v2/hook/test", "https://open.feishu.cn.evil.test/open-apis/bot/v2/hook/test", "https://127.0.0.1/open-apis/bot/v2/hook/test",
                URL + "?debug=true", URL + "#fragment", "https://user@open.feishu.cn/open-apis/bot/v2/hook/test", "https://open.feishu.cn:8443/open-apis/bot/v2/hook/test"))
            assertThrows(IllegalArgumentException.class, () -> credentials.create(Map.of("name", "拒绝地址", "kind", "feishu", "value", Map.of("webhookUrl", url))));
        String bot = credential(SECRET);
        assertThrows(IllegalArgumentException.class, () -> credentials.headers(bot, new WorkflowPrivacy()));
        String bearer = text(credentials.create(Map.of("name", "HTTP", "kind", "bearer", "value", Map.of("token", "fake-token"))).get("id"));
        assertThrows(IllegalArgumentException.class, () -> credentials.feishu(bearer, new WorkflowPrivacy()));
        assertThrows(IllegalArgumentException.class, () -> credentials.feishu("missing", new WorkflowPrivacy()));
        String encrypted = jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, bot);
        assertFalse(encrypted.contains(SECRET));
    }
}
