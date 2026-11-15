package com.leon.workflow.automation;

import com.leon.workflow.service.AppSettingService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowRssTest {
    JdbcTemplate jdbc;
    WorkflowStore store;
    WorkflowHttp http;
    WorkflowRss rss;
    WorkflowAiSummary ai;
    WorkflowFeishu feishu;
    WorkflowEngine engine;
    AtomicReference<String> xml;
    static final String FEED = "https://example.com/feed";
    Map<String, Object> source(String name, String url) { return Map.of("name", name, "url", url); }
    Map<String, Object> config() { return new LinkedHashMap<>(Map.of("sources", List.of(source("测试源", FEED)), "lookbackHours", 168, "perSource", 5, "maxItems", 35, "onlyNew", true)); }
    String article(String id, String link, String title) { return "<item><guid>" + id + "</guid><title>" + title + "</title><link>" + link + "</link><pubDate>" + Instant.now() + "</pubDate><description><![CDATA[<p>技术摘要</p><script>unsafe()</script>]]></description></item>"; }
    String feed(String... entries) { return "<rss version='2.0'><channel><title>测试</title>" + String.join("", entries) + "</channel></rss>"; }
    Map<String, Object> response(Object body) { return Map.of("items", List.of(Map.of("json", Map.of("body", body)))); }
    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:rss-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new WorkflowStore(jdbc); store.initialize(); http = mock(WorkflowHttp.class); rss = new WorkflowRss(http, store);
        xml = new AtomicReference<>(feed(article("one", "https://example.com/one", "科技爱好者周刊")));
        when(http.executeResolved(anyMap(), any())).thenAnswer(call -> response(xml.get()));
        var settings = mock(AppSettingService.class); when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("rss-test-secret");
        ai = new WorkflowAiSummary(http, settings);
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(response(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", write(Map.of("summary", "本次摘要", "entries", List.of(Map.of("index", 1, "summary", "新文章解读"))))))))));
        feishu = mock(WorkflowFeishu.class); when(feishu.execute(anyMap(), anyMap(), anyMap(), any())).thenAnswer(call -> call.getArgument(1));
        engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, feishu, mock(WorkflowEmail.class), mock(WorkflowGithubTrending.class), rss, ai, mock(WorkflowQq.class));
    }
    @AfterEach void close() { engine.close(); jdbc.execute("SHUTDOWN"); }
    WorkflowGraph graph(Map<String, Object> config) {
        return new WorkflowGraph(List.of(node("start", "start", Map.of()), node("rss", "rss", config), node("summary", "ai-summary", Map.of()),
                node("notify", "feishu", Map.of("credentialId", "test-bot", "messageType", "card", "title", "{{ input.items[0].json.title }}", "text", "{{ input.items[0].json.feishuMarkdown }}")), node("end", "end", Map.of())),
                List.of(edge("start", "rss"), edge("rss", "summary"), edge("summary", "notify"), edge("notify", "end")));
    }
    WorkflowGraph.Node node(String id, String type, Map<String, Object> config) { return new WorkflowGraph.Node(id, type, id, config, Map.of()); }
    WorkflowGraph.Edge edge(String a, String b) { return new WorkflowGraph.Edge(a + b, a, b, ""); }
    String publish(Map<String, Object> config) { String id = text(store.create("RSS 日报").get("id")); store.save(id, "RSS 日报", graph(config), 1); store.publish(id, 2, "{}"); return id; }
    Map<String, Object> run(String workflow) throws Exception {
        String id = text(engine.start(workflow, Map.of("items", List.of())).get("id"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var result = store.run(id); if (!Set.of("RUNNING", "QUEUED").contains(result.get("status"))) return result; Thread.sleep(10);
        }
        throw new AssertionError("RSS test timed out");
    }
    @Test void successfulWorkflowCommitsDedupAndEmptyNextRunSkipsAiAndFeishuEvenAfterRestart() throws Exception {
        String workflow = publish(config());
        var first = run(workflow); assertEquals("SUCCEEDED", first.get("status"), text(first.get("error")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_seen", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_pending", Integer.class));
        new WorkflowStore(jdbc).initialize();
        var second = run(workflow); assertEquals("SUCCEEDED", second.get("status"), text(second.get("error")));
        assertEquals(List.of(), object(second.get("output")).get("items"));
        verify(http, times(1)).executeResolved(anyMap(), any(), eq(240000));
        verify(feishu, times(1)).execute(anyMap(), anyMap(), anyMap(), any());
        xml.set(feed(article("two", "https://example.com/two", "新增文章")));
        assertEquals("SUCCEEDED", run(workflow).get("status"));
        verify(feishu, times(2)).execute(anyMap(), anyMap(), anyMap(), any());
    }
    @Test void deliveryFailureDoesNotConsumeNewArticlesAndRetryCanSendThem() throws Exception {
        when(feishu.execute(anyMap(), anyMap(), anyMap(), any())).thenThrow(new IllegalArgumentException("模拟飞书失败")).thenAnswer(call -> call.getArgument(1));
        String workflow = publish(config());
        assertEquals("FAILED", run(workflow).get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_seen", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_pending", Integer.class));
        assertEquals("SUCCEEDED", run(workflow).get("status"));
        verify(feishu, times(2)).execute(anyMap(), anyMap(), anyMap(), any());
    }
    @Test void debugDoesNotConsumeAndSourceFiltersAndCrossFeedLinksAreDeduplicated() throws Exception {
        xml.set(feed(article("one", "https://example.com/one?utm_source=test", "科技爱好者周刊"), article("one-duplicate", "https://example.com/one#top", "科技爱好者周刊"), article("other", "https://example.com/other", "非周刊")));
        var config = config(); config.put("sources", List.of(Map.of("name", "周刊", "url", FEED, "titleContains", "科技爱好者周刊"), source("重复源", "https://example.com/second")));
        var result = engine.test(node("rss", "rss", config), Map.of("items", List.of()));
        assertEquals(2, ((List<?>)object(result.get("output")).get("items")).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_pending", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_seen", Integer.class));
    }
    @Test void atomRdfAndRelativeLinksParseButHtmlXxeAndJavascriptUrlsCannotBeUsed() {
        String atom = "<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>atom-1</id><title>周刊</title><link rel='alternate' href='/article'/><updated>2026-11-15T00:00:00Z</updated><content type='html'>&lt;p&gt;内容&lt;/p&gt;</content></entry></feed>";
        var entry = WorkflowRss.parse(atom, source("Atom", FEED)).getFirst();
        assertEquals("https://example.com/article", entry.json().get("url")); assertEquals("内容", entry.json().get("content")); assertNotNull(entry.date());
        String rdf = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns='http://purl.org/rss/1.0/'><item><title>RDF</title><link>https://example.com/rdf</link></item></rdf:RDF>";
        assertEquals(1, WorkflowRss.parse(rdf, source("RDF", FEED)).size());
        assertEquals("", WorkflowRss.articleUrl(FEED, "javascript:alert(1)"));
        for (String invalid : List.of("<html><body>not RSS</body></html>", "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>"))
            assertThrows(IllegalArgumentException.class, () -> WorkflowRss.parse(invalid, source("拒绝", FEED)));
    }
    @Test void partialSourceFailuresStayVisibleAndTotalFailureStopsTheWorkflow() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("连接失败")).thenAnswer(call -> response(xml.get()));
        var config = config(); config.put("sources", List.of(source("失败源", FEED), source("正常源", "https://example.com/ok")));
        var result = rss.execute(config, "preview-test", "rss", new WorkflowPrivacy(), log -> {});
        assertEquals(1, ((List<?>)result.get("items")).size()); assertEquals(1, ((List<?>)object(result.get("rss")).get("warnings")).size());
        when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("连接失败"));
        assertThrows(IllegalArgumentException.class, () -> rss.execute(config, "preview-test", "rss", new WorkflowPrivacy(), log -> {}));
    }
    @Test void pendingRunsAreSerializedAndCancelledOrInterruptedRunsNeverCommitPendingArticles() {
        String workflow = publish(config()); WorkflowGraph graph = graph(config());
        String id = store.createRun(workflow, 1, graph, Map.of("items", List.of())); store.start(id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.createRun(workflow, 1, graph, Map.of("items", List.of())));
        store.stageRss(id, "rss", List.of("a")); store.finish(id, "CANCELLED", null, "取消"); store.finish(id, "SUCCEEDED", Map.of("items", List.of()), null);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_seen", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> store.stageRss(id, "rss", List.of("late")));
        String interrupted = store.createRun(workflow, 1, graph, Map.of("items", List.of())); store.start(interrupted); store.stageRss(interrupted, "rss", List.of("b"));
        new WorkflowStore(jdbc).initialize();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM automation_rss_pending", Integer.class));
        assertEquals("FAILED", store.run(interrupted).get("status"));
    }
}
