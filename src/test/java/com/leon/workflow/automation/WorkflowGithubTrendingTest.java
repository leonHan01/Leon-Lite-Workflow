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
        trending = new WorkflowGithubTrending(http, settings, mock(WorkflowCredentials.class));
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-private-trending-key");
    }
    Map<String, Object> response(Object body) { return Map.of("items", List.of(Map.of("json", Map.of("body", body)))); }
    Map<String, Object> analysis() { return Map.of("summary", "开发工具与资料不足的项目", "projects", List.of(
            Map.of("repo", "sample/one", "purpose", "为开发者提供工具和辅助功能", "useCases", "开发工作流"),
            Map.of("repo", "sample/two", "purpose", "资料不足，无法确认具体用途", "useCases", "需查看项目说明后确认"))); }
    Map<String, Object> completion(Object content, String finish) { return response(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", content))))); }
    @Test void retriesSnapshotReadAfterTransientConnectionClose() throws Exception {
        long captured = java.time.Instant.now().getEpochSecond();
        var snapshot = Map.of("period", "weekly", "items", List.of(
                Map.of("owner", "sample", "repo", "one", "captured_at", captured),
                Map.of("owner", "sample", "repo", "two", "captured_at", captured)));
        when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("HTTP 请求未完成：连接或响应超时"))
                .thenThrow(new IllegalArgumentException("HTTP 请求未完成：HTTP 连接被关闭或响应读取失败"))
                .thenReturn(response(snapshot));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        var logs = new ArrayList<String>();
        var result = trending.execute(Map.of(), new WorkflowPrivacy(), logs::add);
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        assertEquals("GitStock 快照", output.get("dataSource"));
        assertTrue(String.join("\n", logs).contains("第 2 次"));
        var requests = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http, times(3)).executeResolved(requests.capture(), any());
        assertEquals("GET", requests.getAllValues().get(1).get("method"));
        assertEquals("https://gitstock.org/api/v1/github/trending?period=weekly", requests.getAllValues().get(2).get("url"));
        verify(http, times(1)).executeResolved(anyMap(), any(), eq(240000));
    }
    @Test void snapshotRetriesAreBoundedAndNeverRetryBlockedAddresses() throws Exception {
        for (String failure : List.of("HTTP 请求未完成：HTTP 连接被关闭或响应读取失败", "HTTP 请求未完成：目标地址不允许访问")) {
            reset(http);
            when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("原始榜单不可用")).thenThrow(new IllegalArgumentException(failure));
            var error = assertThrows(IllegalArgumentException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy()));
            assertTrue(error.getMessage().contains("GitStock 榜单快照请求失败"));
            verify(http, times(failure.contains("目标地址") ? 2 : 4)).executeResolved(anyMap(), any());
            verify(http, never()).executeResolved(anyMap(), any(), anyInt());
        }
    }
    @Test void cancellationInterruptsSnapshotRetry() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("原始榜单不可用"))
                .thenThrow(new IllegalArgumentException("HTTP 请求未完成：HTTP 连接被关闭或响应读取失败"));
        try {
            assertThrows(InterruptedException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy(), message -> {
                if (message.contains("GitStock 第 1 次请求失败")) Thread.currentThread().interrupt();
            }));
            verify(http, times(2)).executeResolved(anyMap(), any());
            verify(http, never()).executeResolved(anyMap(), any(), anyInt());
        } finally { Thread.interrupted(); }
    }
    @Test void timeoutFallsBackToRecentTrendingSnapshotAndLabelsItsSource() throws Exception {
        long captured = java.time.Instant.now().getEpochSecond();
        var snapshot = Map.of("period", "weekly", "date", "2026-11-15", "items", List.of(
                Map.of("rank", 1, "owner", "sample", "repo", "one", "description", "工具", "language", "Java", "stars", 12, "stars_gained", 3, "captured_at", captured),
                Map.of("rank", 2, "owner", "sample", "repo", "two", "description", "", "language", "", "stars", 3, "stars_gained", 1, "captured_at", captured)));
        when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("HTTP 请求未完成：连接或响应超时" )).thenReturn(response(snapshot));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        var logs = new ArrayList<String>();
        var result = trending.execute(Map.of(), new WorkflowPrivacy(), logs::add);
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        assertEquals("GitStock 快照", output.get("dataSource"));
        assertTrue(text(output.get("feishuText")).contains("GitStock"));
        assertTrue(text(output.get("feishuMarkdown")).contains("GitStock"));
        assertTrue(text(output.get("emailBody")).contains("GitStock"));
        assertTrue(text(output.get("qqText")).contains("GitStock"));
        assertTrue(text(output.get("qqText")).contains(java.time.Instant.ofEpochSecond(captured).toString()));
        assertTrue(text(output.get("qqText")).getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES);
        assertTrue(text(output.get("qqMarkdown")).contains("GitStock"));
        assertTrue(text(output.get("qqMarkdown")).replace("\\", "").contains(java.time.Instant.ofEpochSecond(captured).toString()));
        assertTrue(text(output.get("qqMarkdown")).getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES);
        assertTrue(String.join("\n", logs).contains("GitStock"));
    }
    @Test void fallbackRejectsOldOrWrongPeriodSnapshotsBeforeCallingDeepseek() throws Exception {
        long old = java.time.Instant.now().minusSeconds(172801).getEpochSecond();
        var item = Map.of("owner", "sample", "repo", "one", "captured_at", old);
        for (var snapshot : List.of(Map.of("period", "weekly", "items", List.of(item)), Map.of("period", "monthly", "items", List.of(item)))) {
            reset(http);
            when(http.executeResolved(anyMap(), any())).thenThrow(new IllegalArgumentException("连接超时")).thenReturn(response(snapshot));
            assertThrows(IllegalArgumentException.class, () -> trending.execute(Map.of(), new WorkflowPrivacy()));
            verify(http, never()).executeResolved(anyMap(), any(), anyInt());
        }
    }
    @Test void fetchesThePageAndSendsOnlyExtractedProjectsToDeepseek() throws Exception {
        when(http.executeResolved(anyMap(), any())).thenReturn(response(HTML));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(analysis()), "stop"));
        var privacy = new WorkflowPrivacy();
        var result = trending.execute(Map.of(), privacy);
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        assertEquals(2, output.get("count")); assertEquals(2, output.get("totalProjects"));
        assertEquals("weekly", output.get("period")); assertEquals("周榜", output.get("periodLabel"));
        assertTrue(text(output.get("feishuText")).contains("sample/one"));
        assertTrue(text(output.get("emailBody")).contains("为开发者提供工具"));
        assertTrue(text(output.get("emailHtml")).contains("https://github.com/sample/two"));
        assertTrue(text(output.get("qqText")).contains("开发工具与资料不足的项目"));
        assertTrue(text(output.get("qqText")).contains("为开发者提供工具和辅助功能"));
        assertTrue(text(output.get("qqText")).contains("https://github.com/sample/two"));
        var fetch = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http).executeResolved(fetch.capture(), same(privacy));
        assertEquals("https://github.com/trending?since=weekly", fetch.getValue().get("url"));
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
            for (String field : List.of("title", "feishuText", "feishuMarkdown", "qqText", "emailSubject", "emailBody", "emailHtml")) assertTrue(text(output.get(field)).contains(label), field);
            for (String field : List.of("feishuText", "feishuMarkdown", "qqText", "emailBody", "emailHtml")) assertTrue(text(output.get(field)).contains(source), field);
            var fetch = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(fetch.capture(), any());
            assertEquals(source, fetch.getValue().get("url"));
            var post = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(post.capture(), any(), eq(240000));
            String messages = write(object(post.getValue().get("body")).get("messages"));
            assertTrue(messages.contains(source)); assertTrue(messages.contains(label));
            assertTrue(String.join("\n", logs).contains("周期=" + label));
        }
    }
    @Test void legacyDailyAndMissingPeriodUseWeeklyWithoutChangingMonthly() {
        String weekly = "https://github.com/trending?since=weekly";
        assertEquals(weekly, WorkflowGithubTrending.sourceUrl(Map.of()));
        assertEquals(weekly, WorkflowGithubTrending.sourceUrl(Map.of("sourceUrl", "https://github.com/trending")));
        assertEquals(weekly, WorkflowGithubTrending.sourceUrl(Map.of("sourceUrl", "https://github.com/trending?since=daily")));
        assertEquals("https://github.com/trending?since=monthly", WorkflowGithubTrending.sourceUrl(Map.of("sourceUrl", "https://github.com/trending?since=monthly")));
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
        String markdown = text(output.get("feishuMarkdown"));
        var card = Map.of("schema", "2.0", "header", Map.of("title", Map.of("tag", "plain_text", "content", output.get("title")), "template", "blue"),
                "config", Map.of("update_multi", true), "body", Map.of("elements", List.of(Map.of("tag", "markdown", "content", markdown))));
        int cardBytes = write(Map.of("msg_type", "interactive", "card", card, "timestamp", "1800000000", "sign", "s".repeat(44))).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(cardBytes < 20 * 1024, "带样式的签名卡片超过消息限制: " + cardBytes);
        for (var project : source) assertTrue(markdown.contains(text(project.get("url"))), "卡片应保留全部项目链接");
        assertFalse(markdown.contains("<at")); assertFalse(markdown.contains("<img"));
        for (String field : List.of("qqText", "qqMarkdown")) {
            String qq = text(output.get(field));
            assertTrue(qq.getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES, "QQ 单条摘要超过限制");
            assertFalse(qq.contains("<at")); assertFalse(qq.contains("<img")); assertFalse(qq.contains("\uFFFD"));
            var urls = source.stream().map(project -> text(project.get("url"))).collect(java.util.stream.Collectors.toSet());
            var included = qq.lines().filter(line -> line.startsWith("https://github.com/")).toList();
            assertFalse(included.isEmpty()); assertTrue(included.size() <= 5);
            assertTrue(urls.containsAll(included), "QQ 消息必须保留完整仓库链接");
            assertTrue(qq.contains("其余 " + (25 - included.size()) + " 项"));
            assertTrue(qq.contains(WorkflowGithubTrending.DEFAULT_SOURCE));
        }
    }
    @Test void qqSummaryShowsAtMostFiveProjectsInOriginalOrderAndLeavesFullAnalysisIntact() {
        var source = new ArrayList<Map<String, Object>>(); var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 10; i++) {
            String repo = "sample/project-" + i;
            source.add(Map.of("repo", repo, "url", "https://github.com/" + repo));
            rows.add(Map.of("repo", repo, "purpose", "开发者工具 " + i, "useCases", "开发工作流"));
        }
        var result = WorkflowGithubTrending.report(Map.of("summary", "本周主要关注开发者工具", "projects", rows), new WorkflowGithubTrending.Page(10, source));
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        for (String field : List.of("qqText", "qqMarkdown")) {
            String qq = text(output.get(field));
            for (int i = 1; i <= 5; i++) assertTrue(qq.contains("https://github.com/sample/project-" + i));
            assertFalse(qq.contains("sample/project-6")); assertTrue(qq.contains("其余 5 项"));
            assertTrue(qq.indexOf("sample/project-1") < qq.indexOf("sample/project-2"));
            assertEquals(10, ((List<?>)output.get("projects")).size()); assertTrue(text(output.get("emailBody")).contains("sample/project-10"));
            assertTrue(qq.getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES);
        }
    }
    @Test void qqMarkdownBoldsHeadingsNamesAndOverviewAndEscapesUntrustedMarkup() {
        var analysis = new LinkedHashMap<>(analysis());
        analysis.put("summary", "概览 **伪造** [外链](https://example.invalid) <at id=all></at>");
        var output = object(object(((List<?>)WorkflowGithubTrending.report(analysis, WorkflowGithubTrending.parsePage(HTML, 2)).get("items")).getFirst()).get("json"));
        String markdown = text(output.get("qqMarkdown"));
        assertTrue(markdown.startsWith("**GitHub Trending 周榜项目解读"));
        assertTrue(markdown.contains("**榜单概览：概览 \\*\\*伪造\\*\\*"));
        assertTrue(markdown.contains("**1. sample/one**"));
        assertTrue(markdown.contains("**用途：** 为开发者提供工具和辅助功能"));
        assertTrue(markdown.contains("https://github.com/sample/one"));
        assertFalse(markdown.contains("[外链](https://example.invalid)"));
        assertFalse(markdown.contains("<at"));
        assertFalse(text(output.get("qqText")).startsWith("**"), "纯文本输出保持兼容");
        long delimiters = java.util.regex.Pattern.compile("(?<!\\\\)\\*\\*").matcher(markdown).results().count();
        assertEquals(0, delimiters % 2, "加粗标记必须成对闭合");
        // Escaped units and emoji must stay intact even when shortened at the byte boundary.
        var source = List.<Map<String, Object>>of(Map.of("repo", "sample/tool_name", "url", "https://github.com/sample/tool_name"));
        var rows = List.of(Map.of("repo", "sample/tool_name", "purpose", "😀*\\".repeat(150), "useCases", "开发"));
        output = object(object(((List<?>)WorkflowGithubTrending.report(Map.of("summary", "*\\😀".repeat(300), "projects", rows), new WorkflowGithubTrending.Page(1, source)).get("items")).getFirst()).get("json"));
        markdown = text(output.get("qqMarkdown"));
        assertTrue(markdown.getBytes(StandardCharsets.UTF_8).length <= WorkflowQq.MAX_MESSAGE_BYTES);
        assertTrue(markdown.contains("**1. sample/tool\\_name**"));
        assertFalse(markdown.contains("\uFFFD"));
        assertFalse(java.util.regex.Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\\\…").matcher(markdown).find(), "截断不能留下半个转义符");
        assertEquals(0, java.util.regex.Pattern.compile("(?<!\\\\)\\*\\*").matcher(markdown).results().count() % 2);
    }
    @Test void cardMarkdownStylesTrustedLabelsAndEscapesModelFormatting() {
        var analysis = new LinkedHashMap<>(analysis());
        analysis.put("summary", "概览 **伪造** [外链](https://example.invalid) <at id=all></at>");
        var output = object(object(((List<?>)WorkflowGithubTrending.report(analysis, WorkflowGithubTrending.parsePage(HTML, 2)).get("items")).getFirst()).get("json"));
        String markdown = text(output.get("feishuMarkdown"));
        assertTrue(markdown.contains("<text_tag color='blue'>周榜</text_tag>"));
        assertTrue(markdown.contains("<text_tag color='green'>分析 2 / 2 个项目</text_tag>"));
        assertTrue(markdown.contains("**榜单概览**"));
        assertTrue(markdown.contains("**1. [sample/one](https://github.com/sample/one)**"));
        assertTrue(markdown.contains("**用途：** 为开发者提供工具和辅助功能"));
        assertTrue(markdown.contains("**适用场景：** 开发工作流"));
        assertTrue(markdown.contains("\\*\\*伪造\\*\\*"));
        assertFalse(markdown.contains("[外链](https://example.invalid)"));
        assertFalse(markdown.contains("<at"));
        assertFalse(text(output.get("feishuText")).contains("<text_tag"), "原有纯文本输出应保持兼容");
    }
    @Test void weeklyFindsLatestIssueAndPreparesBothMessages() throws Exception {
        String index = "[第 420 期](docs/issue-420.md) [第 419 期](docs/issue-419.md)";
        String issue = "# 第 420 期\n这是最新周刊正文";
        var indexBody = Map.of("encoding", "base64", "content", Base64.getEncoder().encodeToString(index.getBytes(StandardCharsets.UTF_8)));
        var issueBody = Map.of("encoding", "base64", "content", Base64.getEncoder().encodeToString(issue.getBytes(StandardCharsets.UTF_8)));
        when(http.executeResolved(anyMap(), any())).thenReturn(response(indexBody), response(issueBody));
        when(http.executeResolved(anyMap(), any(), eq(240000))).thenReturn(completion(write(Map.of("summary", "本期概述", "highlights", List.of("工具介绍", "科技新闻"))), "stop"));
        var result = trending.executeWeekly(Map.of(), new WorkflowPrivacy(), m -> {});
        var output = object(object(((List<?>)result.get("items")).getFirst()).get("json"));
        assertEquals(420, output.get("issue"));
        assertEquals("https://github.com/ruanyf/weekly/blob/master/docs/issue-420.md", output.get("sourceUrl"));
        assertTrue(text(output.get("feishuMarkdown")).contains("**重点内容**"));
        assertTrue(text(output.get("emailBody")).contains("科技新闻"));
        var fetch = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http, times(2)).executeResolved(fetch.capture(), any());
        assertEquals("https://api.github.com/repos/ruanyf/weekly/contents/docs/issue-420.md", fetch.getAllValues().get(1).get("url"));
        var post = org.mockito.ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(post.capture(), any(), eq(240000));
        assertTrue(write(post.getValue().get("body")).contains("这是最新周刊正文"));
        assertDoesNotThrow(() -> WorkflowGraph.validateNode(new WorkflowGraph.Node("weekly", "tech-weekly", "周刊", Map.of(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGithubTrending.validateWeeklyConfig(Map.of("sourceUrl", "http://127.0.0.1")));
    }
    @Test void weeklyRejectsMissingIndexAndIncompleteModelOutput() throws Exception {
        var empty = Map.of("encoding", "base64", "content", Base64.getEncoder().encodeToString("没有周刊".getBytes(StandardCharsets.UTF_8)));
        when(http.executeResolved(anyMap(), any())).thenReturn(response(empty));
        assertThrows(IllegalArgumentException.class, () -> trending.executeWeekly(Map.of(), new WorkflowPrivacy(), m -> {}));
        verify(http, never()).executeResolved(anyMap(), any(), anyInt());
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
        var engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, feishu, email, trending, mock(WorkflowRss.class), mock(WorkflowAiSummary.class), mock(WorkflowQq.class));
        try {
            var graph = new WorkflowGraph(List.of(
                    new WorkflowGraph.Node("start", "start", "开始", Map.of(), Map.of()),
                    new WorkflowGraph.Node("trending", "github-trending", "项目分析", Map.of(), Map.of()),
                    new WorkflowGraph.Node("feishu", "feishu", "飞书", Map.of("credentialId", "test-bot", "messageType", "card", "title", "{{ input.items[0].json.title }}", "text", "{{ input.items[0].json.feishuMarkdown }}"), Map.of()),
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
            assertTrue(feishuText.get().contains("**用途：**"));
            assertTrue(write(store.get(id)).contains("{{ input.items[0].json.emailBody }}"));
            assertFalse(write(run).contains("test-private-trending-key"));
            verify(feishu).execute(anyMap(), anyMap(), anyMap(), any()); verify(email).execute(anyMap(), anyMap(), anyMap(), any());
        } finally { engine.close(); jdbc.execute("SHUTDOWN"); }
    }
}
