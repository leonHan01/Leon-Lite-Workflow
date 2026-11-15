package com.leon.workflow.automation;

import com.leon.workflow.service.AppSettingService;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkflowGithubTrendingTest {
    WorkflowHttp http;
    AppSettingService settings;
    WorkflowGithubTrending trending;
    static final String HTML = """
            <html><script>ignore-this-script</script><main>
            <article class="Box-row"><h2><a href="/sample/one"><span>sample /</span> one</a></h2>
            <p>Tools &amp; helpers for developers</p><span itemprop="programmingLanguage">Java</span>
            <a href="/sample/one/stargazers">1,234</a><span class="float-sm-right">123 stars today</span></article>
            <article class="Box-row"><h2><a href="/sample/two">sample / two</a></h2></article>
            </main></html>
            """;
    @BeforeEach void setup() {
        http = mock(WorkflowHttp.class); settings = mock(AppSettingService.class);
        trending = new WorkflowGithubTrending(http, settings);
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-private-trending-key");
    }
    Map<String, Object> response(Object body) { return Map.of("items", List.of(Map.of("json", Map.of("body", body)))); }
    Map<String, Object> analysis() { return Map.of("summary", "开发工具与资料不足的项目", "projects", List.of(
            Map.of("repo", "sample/one", "purpose", "为开发者提供工具和辅助功能", "useCases", "开发工作流"),
            Map.of("repo", "sample/two", "purpose", "资料不足，无法确认具体用途", "useCases", "需查看项目说明后确认"))); }
    Map<String, Object> completion(Object content, String finish) { return response(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", content))))); }
    @Test void fetchesThePageAndSendsOnlyExtractedProjectsToDeepseek() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(HTML));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        var privacy = new WorkflowPrivacy();
        var result = trending.execute(Map.of(), privacy);
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        assertEquals(2, output.get("count")); assertEquals(2, output.get("totalProjects"));
        assertEquals("daily", output.get("period")); assertEquals("日榜", output.get("periodLabel"));
        assertTrue(text(output.get("feishuText")).contains("sample/one"));
        assertTrue(text(output.get("emailBody")).contains("为开发者提供工具"));
        assertTrue(text(output.get("emailHtml")).contains("https://github.com/sample/two"));
        var fetch = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http).executeResolved(fetch.capture(), same(privacy));
        assertEquals("https://github.com/trending", fetch.getValue().get("url"));
        assertFalse(object(fetch.getValue().get("headers")).containsKey("Authorization"));
        var post = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http).executeResolved(post.capture(), same(privacy), eq(240000));
        assertEquals("https://api.deepseek.com/chat/completions", post.getValue().get("url"));
        var body = object(post.getValue().get("body"));
        assertEquals("deepseek-flash", body.get("model")); assertEquals(Map.of("type", "json_object"), body.get("response_format"));
        String messages = write(body.get("messages"));
        assertTrue(messages.contains("Tools & helpers")); assertTrue(messages.contains("sample/two"));
        assertFalse(messages.contains("<article")); assertFalse(messages.contains("ignore-this-script"));
        assertFalse(messages.contains("test-private-trending-key")); assertFalse(write(result).contains("test-private-trending-key"));
        assertEquals("[已隐藏]", privacy.message("test-private-trending-key"));
    }
    @Test void weeklyAndMonthlyPeriodsReachTheFetcherModelAndEveryPreparedMessage() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(HTML));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        for (String period : List.of("weekly", "monthly")) {
            clearInvocations(http);
            String source = "https://github.com/trending?since=" + period, label = "weekly".equals(period) ? "周榜" : "月榜";
            List<String> logs = new ArrayList<>();
            var result = trending.execute(Map.of("sourceUrl", source), new WorkflowPrivacy(), logs::add);
            var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
            assertEquals(source, output.get("sourceUrl")); assertEquals(period, output.get("period")); assertEquals(label, output.get("periodLabel"));
            for (String field : List.of("title", "feishuText", "emailSubject", "emailBody", "emailHtml")) assertTrue(text(output.get(field)).contains(label), field);
            for (String field : List.of("feishuText", "emailBody", "emailHtml")) assertTrue(text(output.get(field)).contains(source), field);
            var fetch = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(fetch.capture(), any());
            assertEquals(source, fetch.getValue().get("url"));
            var post = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(post.capture(), any(), eq(240000));
            String messages = write(object(post.getValue().get("body")).get("messages"));
            assertTrue(messages.contains(source)); assertTrue(messages.contains(label));
            assertTrue(String.join("\n", logs).contains("周期=" + label));
        }
    }
    @Test void parsesGithubMarkupAndKeepsTheRealTotalWhenLimitingAnalysis() {
        var page = WorkflowGithubTrending.parsePage(HTML, 1);
        assertEquals(2, page.total()); assertEquals(1, page.projects().size());
        assertEquals("Tools & helpers for developers", page.projects().getFirst().get("description"));
        assertEquals("Java", page.projects().getFirst().get("language")); assertEquals("1,234", page.projects().getFirst().get("stars"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGithubTrending.parsePage("<html>访问受限</html>", 25));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGithubTrending.parsePage(HTML.replace("/sample/two", "//evil.test/repo"), 25));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGithubTrending.parsePage(HTML.replace("/sample/two", "/sample/one"), 25));
    }
    @Test void missingKeysAndBlockedOrChangedPagesFailBeforeCallingTheModel() throws Exception {
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("");
        assertThrows(IllegalArgumentException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy())); verifyNoInteractions(http);
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-key");
        when(http.executeResolved(anyMap(), any())).thenReturn(response("<html>页面不可用</html>"));
        assertThrows(IllegalArgumentException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy()));
        verify(http, never()).executeResolved(anyMap(), any(), anyInt());
    }
    @Test void incompleteOrInventedModelResultsCannotBecomeSuccessfulReports() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(HTML));
        for (var reply : List.of(completion(write(analysis()), "length"), completion("not json", "stop"),
                completion(write(Map.of("summary", "缺少项目", "projects", List.of())), "stop"),
                completion(write(analysis()).replace("sample/two", "invented/project"), "stop"))) {
            when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(reply);
            assertThrows(IllegalArgumentException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy()));
        }
    }
    @Test void generatedMessagesFitFeishuAndHtmlDoesNotExecuteModelMarkup() {
        List<Map<String, Object>> source = new ArrayList<>(), projects = new ArrayList<>();
        String purpose = "工具分析中文😀\"\\".repeat(45) + "<at user_id=all></at><img src=x onerror=alert(1)>";
        for (int i = 0; i < 25; i++) {
            String repo = "o".repeat(39) + "/" + "r".repeat(95) + i;
            source.add(Map.of("repo", repo, "url", "https://github.com/" + repo));
            projects.add(Map.of("repo", repo, "purpose", purpose, "useCases", "使用场景".repeat(50)));
        }
        var result = WorkflowGithubTrending.report(Map.of("summary", "榜单概述".repeat(80), "projects", projects), new WorkflowGithubTrending.Page(25, source));
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        byte[] payload = write(Map.of("msg_type", "text", "content", Map.of("text", output.get("feishuText")))).getBytes(StandardCharsets.UTF_8);
        assertTrue(payload.length < 20 * 1024, "飞书消息超过限制: " + payload.length);
        for (var project : source) assertTrue(text(output.get("feishuText")).contains(text(project.get("repo"))));
        assertTrue(text(output.get("emailBody")).contains(purpose));
        assertFalse(text(output.get("emailHtml")).contains("<img")); assertTrue(text(output.get("emailHtml")).contains("&lt;img"));
        assertFalse(text(output.get("feishuText")).contains("<at"));
    }
    @Test void configValidationKeepsSecretsAndArbitraryUrlsOutOfNodes() {
        for (var config : List.<Map<String, Object>>of(Map.of("sourceUrl", "http://127.0.0.1"), Map.of("sourceUrl", "https://github.com/trending?since=yearly"), Map.of("sourceUrl", "https://github.com/trending?since=weekly&redirect=http://127.0.0.1"), Map.of("apiKey", "inline"), Map.of("maxProjects", 0), Map.of("maxProjects", 26), Map.of("timeoutMs", 240001)))
            assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.validateNode(new WorkflowGraph.Node("trending", "github-trending", "趋势分析", config, Map.of())));
        assertDoesNotThrow(() -> WorkflowGraph.validateNode(new WorkflowGraph.Node("trending", "github-trending", "趋势分析", Map.of(), Map.of())));
    }
    @Test void publishedTrendingOutputsCanFeedBothMessageBranchesWithoutReplacingTheirReferences() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(HTML));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:trending-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        var store = new WorkflowStore(jdbc); store.initialize();
        var feishu = mock(WorkflowFeishu.class); var email = mock(WorkflowEmail.class);
        AtomicReference<String> feishuText = new AtomicReference<>(), emailBody = new AtomicReference<>();
        when(feishu.execute(anyMap(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            Map<String, Object> config = call.getArgument(0), input = call.getArgument(1), ctx = call.getArgument(2);
            feishuText.set(text(WorkflowExpressions.resolve(config.get("text"), input, ctx))); return input;
        });
        when(email.execute(anyMap(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            Map<String, Object> config = call.getArgument(0), input = call.getArgument(1), ctx = call.getArgument(2);
            emailBody.set(text(WorkflowExpressions.resolve(config.get("body"), input, ctx))); return input;
        });
        var engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, feishu, email, trending);
        try {
            var graph = new WorkflowGraph(List.of(
                    new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()),
                    new WorkflowGraph.Node("trending", "github-trending", "项目分析", Map.of(), Map.of()),
                    new WorkflowGraph.Node("feishu", "feishu", "飞书", Map.of("credentialId", "test-bot", "text", "{{ input.items[0].json.feishuText }}"), Map.of()),
                    new WorkflowGraph.Node("email", "email", "邮件", Map.of("credentialId", "test-mail", "to", "recipient@example.com", "subject", "{{ input.items[0].json.emailSubject }}", "body", "{{ input.items[0].json.emailBody }}"), Map.of()),
                    new WorkflowGraph.Node("end", "end", "结束", Map.of(), Map.of())),
                    List.of(new WorkflowGraph.Edge("a", "start", "trending", ""), new WorkflowGraph.Edge("b", "trending", "feishu", ""), new WorkflowGraph.Edge("c", "trending", "email", ""), new WorkflowGraph.Edge("d", "feishu", "end", ""), new WorkflowGraph.Edge("e", "email", "end", "")));
            var workflow = store.create("Trending 推送准备"); String id = text(workflow.get("id"));
            store.save(id, "Trending 推送准备", graph, 1); store.publish(id, 2, "{}");
            String runId = text(engine.start(id, Map.of("items", List.of(Map.of("json", Map.of())))).get("id"));
            Map<String, Object> run = store.run(runId);
            for (int i = 0; i < 100 && Set.of("QUEUED", "RUNNING").contains(text(run.get("status"))); i++) { Thread.sleep(20); run = store.run(runId); }
            assertEquals("SUCCEEDED", run.get("status"), text(run.get("error")));
            assertTrue(feishuText.get().contains("为开发者提供工具")); assertTrue(emailBody.get().contains("sample/two"));
            assertTrue(write(store.get(id)).contains("{{ input.items[0].json.emailBody }}"));
            assertFalse(write(run).contains("test-private-trending-key"));
            verify(feishu).execute(anyMap(), anyMap(), anyMap(), any()); verify(email).execute(anyMap(), anyMap(), anyMap(), any());
        } finally { engine.close(); jdbc.execute("SHUTDOWN"); }
    }
}
