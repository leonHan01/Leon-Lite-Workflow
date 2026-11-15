package com.leon.workflow.automation;

import com.leon.workflow.service.AppSettingService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowAiSummaryTest {
    WorkflowHttp http;
    WorkflowAiSummary ai;
    AppSettingService settings;
    @BeforeEach void setup() { http = mock(WorkflowHttp.class); settings = mock(AppSettingService.class); when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-ai-key"); ai = new WorkflowAiSummary(http, settings); }
    Map<String, Object> input() { return Map.of("items", List.of(Map.of("json", Map.of("title", "<b>项目</b>", "source", "来源", "url", "https://example.com/article", "content", "<script>prompt-injection()</script>"))), "rss", Map.of("warnings", List.of("某源暂不可用"))); }
    Map<String, Object> result(Object content, String finish) { return Map.of("items", List.of(Map.of("json", Map.of("body", Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", content)))))))); }
    Map<String, Object> analysis(int index) { return Map.of("summary", "整体总结", "entries", List.of(Map.of("index", index, "summary", "<script>不执行</script> 新变化"))); }
    Map<String, Object> json(Map<String, Object> output) { return object(object(((List<?>)output.get("items")).getFirst()).get("json")); }
    @Test void summarizesGeneralItemsAndCreatesSafeMessagesWithOnlyOriginalLinks() throws Exception {
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(analysis(1)), "stop"));
        var privacy = new WorkflowPrivacy(); var output = json(ai.execute(Map.of(), input(), privacy, log -> {}));
        assertEquals(1, output.get("articleCount")); assertTrue(text(output.get("emailHtml")).contains("https://example.com/article"));
        assertFalse(text(output.get("emailHtml")).contains("<script>")); assertTrue(text(output.get("emailHtml")).contains("&lt;script&gt;"));
        assertTrue(text(output.get("feishuMarkdown")).contains("某源暂不可用"));
        assertFalse(write(output).contains("test-ai-key")); assertEquals("[已隐藏]", privacy.message("test-ai-key"));
        var request = ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(request.capture(), any(), eq(240000));
        assertEquals(WorkflowGithubTrending.API, request.getValue().get("url"));
        assertEquals("json_object", object(object(request.getValue().get("body")).get("response_format")).get("type"));
    }
    @Test void emptyInputSkipsApiAndInvalidCorrespondenceTruncatedOrBlankResponsesFail() throws Exception {
        var empty = Map.<String, Object>of("items", List.of()); assertSame(empty, ai.execute(Map.of(), empty, new WorkflowPrivacy(), log -> {})); verifyNoInteractions(http);
        for (var response : List.of(result(write(analysis(2)), "stop"), result(write(analysis(1)), "length"), result(" ", "stop"), result("not json", "stop"), result(write(Map.of("summary", "总结", "entries", List.of())), "stop"))) {
            when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(response);
            assertThrows(IllegalArgumentException.class, () -> ai.execute(Map.of(), input(), new WorkflowPrivacy(), log -> {}));
        }
    }
    @Test void missingKeyInlineSecretsAndOverLimitItemsFailBeforeCallingApi() {
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("");
        assertThrows(IllegalArgumentException.class, () -> ai.execute(Map.of(), input(), new WorkflowPrivacy(), log -> {}));
        assertThrows(IllegalArgumentException.class, () -> WorkflowAiSummary.validateConfig(Map.of("apiKey", "inline")));
        var items = Map.<String, Object>of("items", List.of(Map.of("json", Map.of()), Map.of("json", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> ai.execute(Map.of("maxItems", 1), items, new WorkflowPrivacy(), log -> {})); verifyNoInteractions(http);
    }
    @Test void retriesAnEmptyJsonCompletionOnceWithoutRetryingDeliveryOrLosingItems() throws Exception {
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result("   ", "stop"), result(write(analysis(1)), "stop"));
        var output = json(ai.execute(Map.of(), input(), new WorkflowPrivacy(), log -> {}));
        assertEquals(1, output.get("articleCount"));
        verify(http, times(2)).executeResolved(anyMap(), any(), eq(240000));
    }
    @Test void textBudgetIsExplicitAndFeishuPayloadFitsEvenForLargeSummaries() throws Exception {
        var entries = new ArrayList<>(); var items = new ArrayList<>();
        for (int i = 1; i <= 50; i++) { entries.add(Map.of("index", i, "summary", "中".repeat(1000))); items.add(Map.of("json", Map.of("title", "标题 " + i, "url", "https://example.com/" + i, "content", "长".repeat(4000)))); }
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(Map.of("summary", "总体", "entries", entries)), "stop"));
        var output = json(ai.execute(Map.of("maxInputChars", 1000), Map.of("items", items), new WorkflowPrivacy(), log -> {}));
        assertEquals(true, output.get("inputTruncated")); assertEquals(50, ((List<?>)output.get("articles")).size());
        assertTrue(write(output.get("feishuMarkdown")).getBytes(StandardCharsets.UTF_8).length <= 16000);
        assertTrue(text(output.get("emailBody")).length() > 50000);
    }
}
