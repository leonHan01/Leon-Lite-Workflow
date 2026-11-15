package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkflowDiagnosticsTest {
    @Test void liveStageLogsAreAvailableWhileTheNodeIsRunningAndContainOnlySafeMetadata() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:diagnostics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        var store = new WorkflowStore(jdbc); store.initialize();
        var trending = mock(WorkflowGithubTrending.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(trending.execute(anyMap(), any(), any())).thenAnswer(call -> {
            WorkflowPrivacy privacy = call.getArgument(1); Consumer<String> trace = call.getArgument(2);
            privacy.secret("test-private-key"); trace.accept("榜单解析完成；项目=15；测试屏蔽 test-private-key"); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalArgumentException("测试超时");
            return Map.of("items", List.of(Map.of("json", Map.of("feishuText", "BUSINESS_CONTENT", "emailBody", "MAIL_CONTENT"))));
        });
        var engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), mock(WorkflowHttp.class), mock(WorkflowFeishu.class), mock(WorkflowEmail.class), trending);
        try {
            var graph = new WorkflowGraph(List.of(new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()), new WorkflowGraph.Node("analysis", "github-trending", "分析", Map.of(), Map.of()), new WorkflowGraph.Node("end", "end", "结束", Map.of(), Map.of())),
                    List.of(new WorkflowGraph.Edge("a", "start", "analysis", ""), new WorkflowGraph.Edge("b", "analysis", "end", "")));
            String id = text(store.create("日志测试").get("id")); store.save(id, "日志测试", graph, 1); store.publish(id, 2, "{}");
            String runId = text(engine.start(id, Map.of("items", List.of(Map.of("json", Map.of("token", "private-input-value"))))).get("id"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var node = ((List<?>)store.run(runId).get("nodes")).stream().map(WorkflowJson::object).filter(n -> "analysis".equals(n.get("nodeId"))).findFirst().orElseThrow();
            assertEquals("RUNNING", node.get("status"));
            assertTrue(text(node.get("logs")).contains("项目=15")); assertTrue(text(node.get("logs")).contains("[已隐藏]"));
            assertFalse(text(node.get("logs")).contains("test-private-key")); assertFalse(text(node.get("logs")).contains("private-input-value"));
            release.countDown();
            Map<String, Object> run = store.run(runId);
            for (int i = 0; i < 100 && Set.of("QUEUED", "RUNNING").contains(text(run.get("status"))); i++) { Thread.sleep(10); run = store.run(runId); }
            assertEquals("SUCCEEDED", run.get("status"));
            String logs = ((List<?>)run.get("nodes")).stream().map(WorkflowJson::object).map(n -> text(n.get("logs"))).reduce("", String::concat);
            assertTrue(logs.contains("feishuText=16 字符")); assertFalse(logs.contains("BUSINESS_CONTENT")); assertFalse(logs.contains("MAIL_CONTENT"));
        } finally { release.countDown(); engine.close(); jdbc.execute("SHUTDOWN"); }
    }
    @Test void requiredReferencesRejectMissingValuesWithoutResolvingNestedTemplateText() {
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("feishuText", "正文 {{ input.items[0].json.missing }}"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowExpressions.resolveRequired("工作流通知：{{ input.items[0].json.name }}", input, Map.of(), "飞书正文"));
        assertEquals("正文 {{ input.items[0].json.missing }}", WorkflowExpressions.resolveRequired("{{ input.items[0].json.feishuText }}", input, Map.of(), "飞书正文"));
        assertFalse(WorkflowExpressions.condition(Map.of("operator", "exists", "left", "{{ input.items[0].json.name }}"), input, Map.of()));
    }
    @Test void diagnosticBuffersAreBoundedInUtf8Bytes() {
        String logs = WorkflowDiagnostics.combine("中文😀".repeat(10000));
        assertTrue(logs.getBytes(StandardCharsets.UTF_8).length <= 16384); assertTrue(logs.contains("日志已截断"));
    }
}
