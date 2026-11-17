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
        ai=new WorkflowAiSummary(http,settings,credentials); trending=new WorkflowGithubTrending(http,settings,credentials);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    Map<String,Object> config() { return new LinkedHashMap<>(Map.of("provider","openai","credentialId",id)); }
    Map<String,Object> input() { return Map.of("items",List.of(Map.of("json",Map.of("title","原文","url","https://example.com/original","content","待分析资料")))); }
    Map<String,Object> json(Map<String,Object> output) { return object(object(((List<?>)output.get("items")).getFirst()).get("json")); }
    Map<String,Object> response(int status,Object body) { return Map.of("items",List.of(Map.of("json",Map.of("status",status,"body",body)))); }
    Map<String,Object> completion(Object content,String reason) { return response(200,Map.of("choices",List.of(Map.of("finish_reason",reason,"message",Map.of("content",content))))); }
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
    @Test void invalidProviderInlineSecretsAndOtherCredentialKindsFailBeforeAnyNetworkRequest() {
        String bearer=text(credentials.create(Map.of("kind","bearer","name","Other","value",Map.of("token","other-token"))).get("id"));
        for(var c:List.<Map<String,Object>>of(Map.of("provider","unknown"),Map.of("provider","openai"),Map.of("provider","openai","credentialId",bearer),Map.of("provider","openai","credentialId",id,"model","invalid model"),Map.of("provider","openai","credentialId",id,"apiKey",KEY),Map.of("provider","deepseek","credentialId",id))) {
            assertThrows(RuntimeException.class,()->ai.execute(c,input(),new WorkflowPrivacy(),log -> {}));
            assertThrows(RuntimeException.class,()->trending.executeWeekly(c,new WorkflowPrivacy(),log -> {}));
        }
        verifyNoInteractions(http);
    }
}
