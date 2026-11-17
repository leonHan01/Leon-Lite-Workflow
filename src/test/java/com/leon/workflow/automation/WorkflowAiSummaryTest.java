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
    @BeforeEach void setup() { http = mock(WorkflowHttp.class); settings = mock(AppSettingService.class); when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-ai-key"); ai = new WorkflowAiSummary(http, settings, mock(WorkflowCredentials.class), mock(WorkflowModels.class)); }
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
    Map<String, Object> digestInput(int count) {
        var items = new ArrayList<>();
        for (int i = 1; i <= count; i++) items.add(Map.of("json", Map.of("title", "技术资讯 " + i, "source", "测试源", "url", "https://example.com/" + i, "content", "原始资料 " + i)));
        return Map.of("items", items, "rss", Map.of("warnings", List.of("某源暂不可用")));
    }
    Map<String, Object> digestAnalysis(int count) {
        var entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int rank = count - i + 1; boolean selected = rank <= Math.min(10, count);
            entries.add(new LinkedHashMap<>(Map.of("index", i, "rank", selected ? rank : 0,
                    "highlight", selected ? "核心变化 **重要** <script>不执行</script>" : "",
                    "summary", selected ? "开发工具的关键进展及实际使用价值。".repeat(4) : "新工具发布，帮助开发者提升效率。")));
        }
        return new LinkedHashMap<>(Map.of("summary", "本期聚焦软件架构和 AI 技术。", "entries", entries));
    }
    @Test void digestCallsAiOnceAndCreatesRankedRichMessagesForAllThreeChannels() throws Exception {
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(digestAnalysis(35)), "stop"));
        var output = json(ai.execute(Map.of("mode", "top10"), digestInput(35), new WorkflowPrivacy(), log -> {}));
        assertEquals(10, output.get("selectedCount")); assertEquals(25, output.get("briefCount")); assertEquals(35, output.get("articleCount"));
        var articles = (List<?>)output.get("articles"); assertEquals("https://example.com/35", object(articles.getFirst()).get("url"));
        assertEquals(1, object(articles.getFirst()).get("rank")); assertEquals("https://example.com/1", object(articles.get(10)).get("url"));
        String card = text(output.get("feishuMarkdown")), email = text(output.get("emailHtml"));
        assertTrue(card.contains("**精选 10 篇**")); assertTrue(card.contains("**看点：")); assertTrue(card.contains("其余速览（25 篇）"));
        assertTrue(email.contains("<strong>看点：")); assertTrue(email.contains("&lt;script&gt;")); assertFalse(email.contains("<script>"));
        var messages = (List<?>)output.get("qqMessages"); assertTrue(messages.size() > 1); assertEquals(messages.size(), output.get("qqMessageCount"));
        for (Object message : messages) {
            String content = text(message); assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES);
            assertTrue(content.startsWith("**")); assertFalse(content.contains("<script>")); assertFalse(content.contains("\uFFFD"));
        }
        String qq = String.join("\n", messages.stream().map(WorkflowJson::text).toList());
        for (int i = 1; i <= 35; i++) {
            String url = "https://example.com/" + i + ")";
            assertTrue(card.contains(url)); assertTrue(qq.contains(url)); assertTrue(email.contains("https://example.com/" + i + "\""));
        }
        assertTrue(card.contains("\\*\\*重要\\*\\*")); assertTrue(qq.contains("**看点：")); assertTrue(qq.contains("某源暂不可用"));
        assertTrue(write(output.get("feishuMarkdown")).getBytes(StandardCharsets.UTF_8).length <= 16000);
        var request = ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(request.capture(), any(), eq(240000));
        String body = write(request.getValue().get("body")); assertTrue(body.contains("highlight")); assertTrue(body.contains("rank"));
    }
    @Test void digestRejectsIncompleteOrInvalidRanksAndMissingHighlights() throws Exception {
        var bad = new ArrayList<Map<String, Object>>();
        for (String key : List.of("rank", "index", "highlight", "summary")) {
            var analysis = digestAnalysis(12); var entries = (List<?>)analysis.get("entries");
            object(entries.getLast()).put(key, key.equals("rank") ? 11 : key.equals("index") ? 999 : ""); bad.add(analysis);
        }
        var fewer = digestAnalysis(12); object(((List<?>)fewer.get("entries")).getLast()).put("rank", 0); bad.add(fewer);
        for (var analysis : bad) {
            when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(analysis), "stop"));
            assertThrows(IllegalArgumentException.class, () -> ai.execute(Map.of("mode", "top10"), digestInput(12), new WorkflowPrivacy(), log -> {}));
        }
        assertThrows(IllegalArgumentException.class, () -> WorkflowAiSummary.validateConfig(Map.of("mode", "unknown")));
    }
    @Test void tiedRanksKeepEveryPickAndGenerateAllNotificationFormatsInStableInputOrder() throws Exception {
        var analysis = digestAnalysis(12);
        for (Object value : (List<?>)analysis.get("entries")) {
            var entry = object(value); if ((int)entry.get("rank") > 0) entry.put("rank", 1);
        }
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(analysis), "stop"));
        var output = json(ai.execute(Map.of("mode", "top10"), digestInput(12), new WorkflowPrivacy(), log -> {}));
        assertEquals(10, output.get("selectedCount")); assertEquals(2, output.get("briefCount")); assertEquals(12, output.get("articleCount"));
        var articles = ((List<?>)output.get("articles")).stream().map(WorkflowJson::object).toList();
        assertEquals(java.util.stream.IntStream.rangeClosed(3,12).boxed().toList(), articles.subList(0,10).stream().map(a -> a.get("index")).toList());
        assertTrue(articles.subList(0,10).stream().allMatch(a -> Integer.valueOf(1).equals(a.get("rank"))));
        String qq = String.join("\n", ((List<?>)output.get("qqMessages")).stream().map(WorkflowJson::text).toList());
        for (int i=1;i<=12;i++) {
            String url="https://example.com/"+i;
            assertTrue(text(output.get("feishuMarkdown")).contains(url+")")); assertTrue(text(output.get("emailHtml")).contains(url+"\"")); assertTrue(qq.contains(url+")"));
        }
        verify(http).executeResolved(anyMap(), any(), eq(240000));
    }
    @Test void smallDigestSelectsEveryArticleAndKeepsSourceLinksOverModelLinks() throws Exception {
        var analysis = digestAnalysis(2); object(((List<?>)analysis.get("entries")).getFirst()).put("url", "https://invented.invalid");
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(analysis), "stop"));
        var output = json(ai.execute(Map.of("mode", "top10"), digestInput(2), new WorkflowPrivacy(), log -> {}));
        assertEquals(2, output.get("selectedCount")); assertEquals(0, output.get("briefCount")); assertFalse(write(output).contains("invented.invalid"));
        var empty = Map.<String, Object>of("items", List.of());
        assertSame(empty, ai.execute(Map.of("mode", "top10"), empty, new WorkflowPrivacy(), log -> {}));
        verify(http).executeResolved(anyMap(), any(), eq(240000));
    }
    @Test void longMultilingualDigestFitsEachChannelAndRetainsFullAnalysisAndLinks() throws Exception {
        var input = digestInput(35); var analysis = digestAnalysis(35);
        String detail = "解释😀_*[]".repeat(100);
        for (int i = 0; i < 35; i++) {
            var original = new LinkedHashMap<>(object(object(((List<?>)input.get("items")).get(i)).get("json")));
            original.put("title", "标题😀_*[]".repeat(50)); original.put("url", "https://example.com/article(" + i + ")");
            ((List<Object>)input.get("items")).set(i, Map.of("json", original));
            var entry = object(((List<?>)analysis.get("entries")).get(i)); entry.put("summary", detail);
            if ((int)entry.get("rank") > 0) entry.put("highlight", "看点😀_*".repeat(20));
        }
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(result(write(analysis), "stop"));
        var output = json(ai.execute(Map.of("mode", "top10"), input, new WorkflowPrivacy(), log -> {}));
        assertEquals(detail, object(((List<?>)output.get("articles")).getFirst()).get("summary"));
        assertTrue(text(output.get("emailBody")).contains(detail));
        assertTrue(write(output.get("feishuMarkdown")).getBytes(StandardCharsets.UTF_8).length <= 16000);
        String qq = "";
        for (Object message : (List<?>)output.get("qqMessages")) {
            String content = text(message); assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= 2000);
            assertFalse(content.contains("\uFFFD")); qq += content;
        }
        for (int i = 0; i < 35; i++) {
            String link = "https://example.com/article%28" + i + "%29";
            assertTrue(qq.contains("[阅读原文](" + link + ")")); assertTrue(text(output.get("feishuMarkdown")).contains(link));
        }
    }
}
