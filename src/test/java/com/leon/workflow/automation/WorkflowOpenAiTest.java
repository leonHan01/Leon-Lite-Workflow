package com.leon.workflow.automation;

import com.leon.workflow.service.AppSettingService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowOpenAiTest {
    static final String KEY="test-openai-private-key";
    JdbcTemplate jdbc; WorkflowCredentials credentials; WorkflowHttp http; AppSettingService settings; WorkflowAiSummary ai; WorkflowGithubTrending trending; String id;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:openai-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")); new WorkflowStore(jdbc).initialize();
        credentials=new WorkflowCredentials(jdbc,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        id=text(credentials.create(Map.of("kind","openai","name","OpenAI","value",Map.of("apiKey",KEY))).get("id"));
        http=mock(WorkflowHttp.class); settings=mock(AppSettingService.class); when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("");
        ai=new WorkflowAiSummary(http,settings,credentials,new WorkflowModels(jdbc,credentials)); trending=new WorkflowGithubTrending(http,settings,credentials,new WorkflowModels(jdbc,credentials));
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    Map<String,Object> config() { return new LinkedHashMap<>(Map.of("provider","openai","credentialId",id)); }
    Map<String,Object> input() { return Map.of("items",List.of(Map.of("json",Map.of("title","原文","url","https://example.com/original","content","待分析资料")))); }
    Map<String,Object> json(Map<String,Object> output) { return object(object(((List<?>)output.get("items")).getFirst()).get("json")); }
    Map<String,Object> response(int status,Object body) { return Map.of("items",List.of(Map.of("json",Map.of("status",status,"body",body)))); }
    Map<String,Object> completion(Object content,String reason) { return response(200,Map.of("usage",Map.of("prompt_tokens",10,"completion_tokens",2,"total_tokens",12),"choices",List.of(Map.of("finish_reason",reason,"message",Map.of("content",content))))); }
    String analysis() { return write(Map.of("summary","整体概览","entries",List.of(Map.of("index",1,"rank",1,"highlight","核心看点","summary","内容和阅读价值")))); }
    @Test void summaryAndTopTenUseOpenAiCredentialsAndKeepEveryExistingOutputFormat() throws Exception {
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(analysis(),"stop"));
        for(String mode:List.of("summary","top10")) {
            var c=config(); c.put("mode",mode); var privacy=new WorkflowPrivacy(); var logs=new ArrayList<String>();
            var result=json(ai.execute(c,input(),privacy,logs::add));
            assertTrue(text(result.get("feishuMarkdown")).contains("整体概览")); assertTrue(text(result.get("emailHtml")).contains("https://example.com/original"));
            if(mode.equals("top10")) { assertEquals(1,result.get("selectedCount")); assertFalse(((List<?>)result.get("qqMessages")).isEmpty()); }
            assertFalse(write(result).contains(KEY)); assertFalse(String.join("\n",logs).contains(KEY)); assertEquals("[已隐藏]",privacy.message(KEY));
        }
        var requests=ArgumentCaptor.forClass(Map.class); verify(http,times(2)).executeResolved(requests.capture(),any(),eq(240000));
        for(var request:requests.getAllValues()) {
            assertEquals(WorkflowAiModel.OPENAI_API,request.get("url")); assertEquals(Map.of("Authorization","Bearer "+KEY),request.get("headers"));
            var body=object(request.get("body")); assertEquals(WorkflowAiModel.OPENAI_MODEL,body.get("model")); assertEquals(16000,body.get("max_completion_tokens"));
            assertEquals(false,body.get("store")); assertFalse(body.containsKey("thinking")); assertFalse(body.containsKey("max_tokens")); assertFalse(write(body).contains(KEY));
            assertEquals("developer",object(((List<?>)body.get("messages")).getFirst()).get("role")); assertEquals(Map.of("type","json_object"),body.get("response_format"));
        }
        verify(settings,never()).getValue(AppSettingService.DEEPSEEK_API_KEY);
    }
    @Test void bothAiNodesCallTheModelSelectedBySharedProfile() throws Exception {
        var catalog=new WorkflowModels(jdbc,credentials);
        var profile=catalog.save(null,Map.of("name","共享 GPT","provider","openai","model","gpt-6-sol","credentialId",id));
        var config=Map.<String,Object>of("modelId",profile.get("id"));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(analysis(),"stop"));
        ai.execute(config,input(),new WorkflowPrivacy(),log -> {});
        var index=Map.of("encoding","base64","content",Base64.getEncoder().encodeToString("[最新一期](docs/issue-421.md)".getBytes(StandardCharsets.UTF_8)));
        var issue=Map.of("encoding","base64","content",Base64.getEncoder().encodeToString("# 第421期 原文".getBytes(StandardCharsets.UTF_8)));
        when(http.executeResolved(anyMap(),any())).thenReturn(response(200,index),response(200,issue));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(write(Map.of("summary","概览","highlights",List.of("重点"))),"stop"));
        trending.executeWeekly(config,new WorkflowPrivacy(),log -> {});
        assertEquals(24L,object(catalog.list().stream().filter(m->m.get("id").equals(profile.get("id"))).findFirst().orElseThrow().get("usage")).get("totalTokens"));
        var requests=ArgumentCaptor.forClass(Map.class); verify(http,times(2)).executeResolved(requests.capture(),any(),eq(240000));
        for (var request:requests.getAllValues()) { assertEquals("gpt-6-sol",object(request.get("body")).get("model")); assertEquals(Map.of("Authorization","Bearer "+KEY),request.get("headers")); }
        catalog.save(text(profile.get("id")),Map.of("name","共享 GPT","provider","openai","model","gpt-6-sol","credentialId",id,"enabled",false));
        clearInvocations(http);
        assertThrows(IllegalArgumentException.class,()->ai.execute(config,input(),new WorkflowPrivacy(),log -> {}));
        assertThrows(IllegalArgumentException.class,()->trending.executeWeekly(config,new WorkflowPrivacy(),log -> {}));
        verifyNoInteractions(http);
    }
    @Test void trendingUsesSelectedSharedModelAndPreservesAllMessageFormats() throws Exception {
        var catalog=new WorkflowModels(jdbc,credentials);
        var gpt=catalog.save(null,Map.of("name","Trending GPT","provider","openai","model","gpt-6.1-sol","credentialId",id));
        var deepseek=catalog.save(null,Map.of("name","Trending Pro","provider","deepseek","model","deepseek-v4-pro"));
        var analysis=Map.of("summary","项目概览","projects",List.of(
                Map.of("repo","sample/one","purpose","工具用途","useCases","工作流"),
                Map.of("repo","sample/two","purpose","资料不足","useCases","需进一步确认")));
        when(http.executeResolved(anyMap(),any())).thenReturn(response(200,WorkflowGithubTrendingTest.HTML));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(write(analysis),"stop"));
        for(var profile:List.of(gpt,deepseek)) {
            clearInvocations(http,settings);
            boolean openai="openai".equals(profile.get("provider"));
            when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn(openai?"":"test-trending-deepseek-key");
            clearInvocations(settings);
            var privacy=new WorkflowPrivacy(); var logs=new ArrayList<String>();
            var result=json(trending.execute(Map.of("modelId",profile.get("id"),"sourceUrl","https://github.com/trending?since=monthly"),privacy,logs::add));
            assertEquals("monthly",result.get("period")); assertEquals(2,result.get("count"));
            assertEquals(12L,object(catalog.list().stream().filter(m->m.get("id").equals(profile.get("id"))).findFirst().orElseThrow().get("usage")).get("totalTokens"));
            assertEquals("服务器抓取 + "+(openai?"OpenAI":"DeepSeek")+" 分析",result.get("sourceReadBy"));
            for (String field:List.of("feishuMarkdown","feishuText","emailBody","emailHtml","qqText","qqMarkdown")) {
                assertTrue(text(result.get(field)).contains("https://github.com/sample/one"),field);
                assertFalse(text(result.get(field)).contains(KEY));
            }
            assertTrue(text(result.get("qqMarkdown")).contains("**用途：**")); assertTrue(text(result.get("emailHtml")).contains("工具用途"));
            var fetch=ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(fetch.capture(),any());
            assertEquals("https://github.com/trending?since=monthly",fetch.getValue().get("url")); assertFalse(object(fetch.getValue().get("headers")).containsKey("Authorization"));
            var post=ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(post.capture(),any(),eq(240000));
            assertEquals(openai?WorkflowAiModel.OPENAI_API:WorkflowGithubTrending.API,post.getValue().get("url"));
            assertEquals(Map.of("Authorization","Bearer "+(openai?KEY:"test-trending-deepseek-key")),post.getValue().get("headers"));
            var body=object(post.getValue().get("body")); assertEquals(profile.get("model"),body.get("model"));
            assertEquals(12000,body.get(openai?"max_completion_tokens":"max_tokens"));
            assertEquals(openai?"developer":"system",object(((List<?>)body.get("messages")).getFirst()).get("role"));
            assertFalse(write(body).contains("ignore-this-script")); assertTrue(String.join("\n",logs).contains("模型="+profile.get("model")));
            assertFalse(String.join("\n",logs).contains(KEY));
            if(openai) { assertFalse(body.containsKey("thinking")); assertFalse(body.containsKey("max_tokens")); verify(settings,never()).getValue(AppSettingService.DEEPSEEK_API_KEY); }
        }
        catalog.save(text(gpt.get("id")),Map.of("name","Trending GPT","provider","openai","model","gpt-6.1-sol","credentialId",id,"enabled",false));
        clearInvocations(http);
        assertThrows(IllegalArgumentException.class,()->trending.execute(Map.of("modelId",gpt.get("id")),new WorkflowPrivacy()));
        assertThrows(IllegalArgumentException.class,()->trending.execute(Map.of("modelId","missing"),new WorkflowPrivacy()));
        verifyNoInteractions(http);
    }
    @Test void trendingOpenAiFailureDoesNotFallBackToDeepseekOrExposeProviderReply() throws Exception {
        when(http.executeResolved(anyMap(),any())).thenReturn(response(200,WorkflowGithubTrendingTest.HTML));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(response(401,Map.of("error",Map.of("message","unsafe-provider-reply "+KEY))));
        var failure=assertThrows(IllegalArgumentException.class,()->trending.execute(config(),new WorkflowPrivacy()));
        assertTrue(failure.getMessage().contains("OpenAI")); assertTrue(failure.getMessage().contains("401"));
        assertFalse(failure.getMessage().contains(KEY)); assertFalse(failure.getMessage().contains("unsafe-provider-reply"));
        verify(http).executeResolved(anyMap(),any()); verify(http).executeResolved(anyMap(),any(),eq(240000));
        verify(settings,never()).getValue(AppSettingService.DEEPSEEK_API_KEY);
    }
    @Test void weeklyFetchesOriginalIssueAndCanAnalyzeItWithAnExplicitGptModel() throws Exception {
        var index=Map.of("encoding","base64","content",Base64.getEncoder().encodeToString("[最新一期](docs/issue-421.md)".getBytes(StandardCharsets.UTF_8)));
        var issue=Map.of("encoding","base64","content",Base64.getEncoder().encodeToString("# 第421期\n真实原文".getBytes(StandardCharsets.UTF_8)));
        when(http.executeResolved(anyMap(),any())).thenReturn(response(200,index),response(200,issue));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(write(Map.of("summary","周刊概览","highlights",List.of("工具更新"))),"stop"));
        var c=config(); c.put("model","gpt-6-sol"); var result=json(trending.executeWeekly(c,new WorkflowPrivacy(),log -> {}));
        assertEquals(421,result.get("issue")); assertTrue(text(result.get("emailBody")).contains("https://github.com/ruanyf/weekly/blob/master/docs/issue-421.md"));
        assertTrue(text(result.get("feishuMarkdown")).contains("工具更新"));
        var request=ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(request.capture(),any(),eq(240000));
        assertEquals(WorkflowAiModel.OPENAI_API,request.getValue().get("url")); var body=object(request.getValue().get("body"));
        assertEquals("gpt-6-sol",body.get("model")); assertEquals(8000,body.get("max_completion_tokens")); assertTrue(write(body).contains("真实原文"));
        verify(settings,never()).getValue(AppSettingService.DEEPSEEK_API_KEY);
    }
    @Test void legacyDeepseekKeepsItsOwnAuthenticationAndRequestParameters() throws Exception {
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-deepseek-private-key");
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(analysis(),"stop"));
        ai.execute(Map.of(),input(),new WorkflowPrivacy(),log -> {});
        var request=ArgumentCaptor.forClass(Map.class); verify(http).executeResolved(request.capture(),any(),eq(240000));
        assertEquals(WorkflowGithubTrending.API,request.getValue().get("url")); assertEquals(Map.of("Authorization","Bearer test-deepseek-private-key"),request.getValue().get("headers"));
        var body=object(request.getValue().get("body")); assertEquals(WorkflowGithubTrending.MODEL,body.get("model")); assertEquals(16000,body.get("max_tokens"));
        assertEquals(Map.of("type","disabled"),body.get("thinking")); assertFalse(body.containsKey("max_completion_tokens"));
    }
    @Test void providerHttpErrorsNeverLeakRawRepliesOrRetryWithAnotherProvider() throws Exception {
        for(int status:List.of(401,403,429,500)) {
            when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(response(status,Map.of("error",Map.of("message","unsafe-provider-reply "+KEY))));
            var failure=assertThrows(IllegalArgumentException.class,()->ai.execute(config(),input(),new WorkflowPrivacy(),log -> {}));
            assertTrue(failure.getMessage().contains("OpenAI")); assertTrue(failure.getMessage().contains(String.valueOf(status)));
            assertFalse(failure.getMessage().contains(KEY)); assertFalse(failure.getMessage().contains("unsafe-provider-reply"));
        }
        verify(http,times(4)).executeResolved(anyMap(),any(),eq(240000)); verify(settings,never()).getValue(AppSettingService.DEEPSEEK_API_KEY);
    }
    @Test void jsonRetryIsBoundedAndTruncatedOrRefusedOutputCannotReachDelivery() throws Exception {
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(" ","stop"),completion(analysis(),"stop"));
        assertEquals(1,json(ai.execute(config(),input(),new WorkflowPrivacy(),log -> {})).get("articleCount"));
        verify(http,times(2)).executeResolved(anyMap(),any(),eq(240000));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(analysis(),"length"));
        assertThrows(IllegalArgumentException.class,()->ai.execute(config(),input(),new WorkflowPrivacy(),log -> {}));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(response(200,Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","","refusal","unsafe refusal "+KEY))))));
        var failure=assertThrows(IllegalArgumentException.class,()->ai.execute(config(),input(),new WorkflowPrivacy(),log -> {})); assertFalse(failure.getMessage().contains(KEY));
    }
    @Test void everyRetryAndTruncatedCompletionIsAccountedBeforeContentValidation() throws Exception {
        var catalog=new WorkflowModels(jdbc,credentials);
        var profile=catalog.save(null,Map.of("name","Token 统计","provider","openai","model",WorkflowAiModel.OPENAI_MODEL,"credentialId",id));
        var config=Map.<String,Object>of("modelId",profile.get("id"));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion("not-json","stop"),completion(analysis(),"stop"));
        ai.execute(config,input(),new WorkflowPrivacy(),log->{});
        var usage=object(catalog.list().stream().filter(m->m.get("id").equals(profile.get("id"))).findFirst().orElseThrow().get("usage"));
        assertEquals(24L,usage.get("totalTokens")); assertEquals(2L,usage.get("reportedCalls"));
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion("partial","length"));
        assertThrows(IllegalArgumentException.class,()->ai.execute(config,input(),new WorkflowPrivacy(),log->{}));
        usage=object(catalog.list().stream().filter(m->m.get("id").equals(profile.get("id"))).findFirst().orElseThrow().get("usage"));
        assertEquals(36L,usage.get("totalTokens")); assertEquals(3L,usage.get("reportedCalls"));
        when(settings.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("legacy-test-key");
        when(http.executeResolved(anyMap(),any(),eq(240000))).thenReturn(completion(analysis(),"stop"));
        ai.execute(Map.of(),input(),new WorkflowPrivacy(),log->{});
        assertEquals(12L,object(catalog.list().getFirst().get("usage")).get("totalTokens"));
    }
    @Test void invalidProviderInlineSecretsAndOtherCredentialKindsFailBeforeAnyNetworkRequest() {
        String bearer=text(credentials.create(Map.of("kind","bearer","name","Other","value",Map.of("token","other-token"))).get("id"));
        for(var c:List.<Map<String,Object>>of(Map.of("provider","unknown"),Map.of("provider","openai"),Map.of("provider","openai","credentialId",bearer),Map.of("provider","openai","credentialId",id,"model","invalid model"),Map.of("provider","openai","credentialId",id,"apiKey",KEY),Map.of("provider","deepseek","credentialId",id))) {
            assertThrows(RuntimeException.class,()->ai.execute(c,input(),new WorkflowPrivacy(),log -> {}));
            assertThrows(RuntimeException.class,()->trending.executeWeekly(c,new WorkflowPrivacy(),log -> {}));
        }
        verifyNoInteractions(http);
    }
}
