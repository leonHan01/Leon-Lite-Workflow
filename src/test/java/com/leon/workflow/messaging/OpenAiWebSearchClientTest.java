package com.leon.workflow.messaging;

import com.leon.workflow.ai.OpenAiExecutor;
import com.leon.workflow.automation.WorkflowModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenAiWebSearchClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private AgentRegistry.Agent agent(boolean drawing){return new AgentRegistry.Agent("id","name","handle","openai","test-model","私有角色",true,true,1,"alfred",null,"",drawing,AgentRegistry.DEFAULT_IMAGE_MODEL,true);}
    static Map<String,Object> payload(){
        return Map.of("status","completed","usage",Map.of("input_tokens",20,"output_tokens",5,"total_tokens",25),"output",List.of(
                Map.of("type","web_search_call","status","completed","action",Map.of("type","search","query","最新版本")),
                Map.of("type","message","content",List.of(Map.of("type","output_text","text","新版本发布。","annotations",List.of(Map.of("type","url_citation","start_index",0,"end_index",6,"url","https://example.test/release","title","官方发布")))))));
    }
    @Test void directSearchUsesLiveWebToolAndPersistsVisibleCitationsWithUsage() throws Exception {
        var http=mock(HttpClient.class);var response=mock(HttpResponse.class);var models=mock(WorkflowModels.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(json.writeValueAsBytes(payload()));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client=new OfficialAgentModelClient(json,http,models,mock(OpenAiExecutor.class));
        var reply=client.reply(agent(false),"test-secret",List.of(Map.of("role","system","content","私有角色"),Map.of("role","user","content","联网查最新版本")));
        assertTrue(reply.text().contains("新版本发布。 [来源1](https://example.test/release)"));
        assertTrue(reply.text().contains("[1. 官方发布](https://example.test/release)"));assertTrue(reply.images().isEmpty());
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(1)).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("https://api.openai.com/v1/responses",request.getValue().uri().toString());
        var body=json.readTree(OfficialAgentModelClientTest.requestBody(request.getValue()));
        assertEquals("web_search",body.path("tools").path(0).path("type").asText());assertEquals(1,body.path("tools").size());
        assertTrue(body.path("tools").path(0).path("external_web_access").asBoolean());assertEquals("auto",body.path("tool_choice").asText());
        assertFalse(body.path("store").asBoolean(true));assertFalse(body.path("stream").asBoolean(true));
        assertEquals("私有角色",body.path("input").path(0).path("content").asText());
        assertTrue(body.path("instructions").asText().contains("北京时间"));
        verify(models).recordOpenAiResponseUsage(eq("test-model"),anyMap(),eq(""),isNull());
    }
    @Test void macSupportsSearchAndDrawingTogetherWhileBackgroundCallsRemainTextOnly() throws Exception {
        var http=mock(HttpClient.class);var models=mock(WorkflowModels.class);var executor=mock(OpenAiExecutor.class);
        when(executor.enabled()).thenReturn(true);
        when(executor.executeResponses(anyMap(),eq("test-secret"),eq(240000),isNull())).thenReturn(Map.of("status",200,"body",payload()));
        when(executor.execute(anyMap(),eq("test-secret"),eq(180000))).thenReturn(Map.of("status",200,"body",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","摘要"))))));
        var client=new OfficialAgentModelClient(json,http,models,executor);
        for(boolean drawing:List.of(false,true))assertTrue(client.reply(agent(drawing),"test-secret",List.of()).text().contains("来源1"));
        assertEquals("摘要",client.complete(agent(true),"test-secret",List.of()));
        var calls=org.mockito.ArgumentCaptor.forClass(Map.class);verify(executor,times(2)).executeResponses(calls.capture(),eq("test-secret"),eq(240000),isNull());
        var combined=json.valueToTree(calls.getAllValues().getLast());
        assertEquals(2,combined.path("tools").size());assertEquals("image_generation",combined.path("tools").path(0).path("type").asText());
        assertEquals("web_search",combined.path("tools").path(1).path("type").asText());assertTrue(combined.path("max_tool_calls").asInt()>1);
        var summary=org.mockito.ArgumentCaptor.forClass(Map.class);verify(executor).execute(summary.capture(),eq("test-secret"),eq(180000));
        assertFalse(summary.getValue().containsKey("tools"));verifyNoInteractions(http,models);
    }
    @Test void ordinaryConversationMayAnswerWithoutSearching() throws Exception {
        var executor=mock(OpenAiExecutor.class);when(executor.enabled()).thenReturn(true);
        when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenReturn(Map.of("status",200,"body",Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","你好")))))));
        var client=new OfficialAgentModelClient(json,mock(HttpClient.class),mock(WorkflowModels.class),executor);
        assertEquals("你好",client.reply(agent(false),"test-secret",List.of()).text());
    }
    @Test void failedSearchAndMissingSourcesNeverLookLikeVerifiedAnswers() throws Exception {
        var executor=mock(OpenAiExecutor.class);when(executor.enabled()).thenReturn(true);
        var client=new OfficialAgentModelClient(json,mock(HttpClient.class),mock(WorkflowModels.class),executor);
        for(String state:List.of("failed","in_progress","completed")){
            when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenReturn(Map.of("status",200,"body",Map.of("status","completed","output",List.of(Map.of("type","web_search_call","status",state),Map.of("type","message","content",List.of(Map.of("type","output_text","text","没有实际来源的回答")))))));
            var failure=assertThrows(AgentModelClient.Failure.class,()->client.reply(agent(false),"test-secret",List.of()));assertTrue(failure.getMessage().contains("联网搜索"));
        }
    }
    @Test void errorsTimeoutsAndOfflineMacDoNotRetryOrFallBackToUnsupportedAnswers() throws Exception {
        var http=mock(HttpClient.class);var response=mock(HttpResponse.class);var models=mock(WorkflowModels.class);var executor=mock(OpenAiExecutor.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.body()).thenReturn("private test-secret".getBytes());
        var client=new OfficialAgentModelClient(json,http,models,executor);
        for(int status:List.of(400,401,403,429,500)){
            when(response.statusCode()).thenReturn(status);
            var failure=assertThrows(AgentModelClient.Failure.class,()->client.reply(agent(false),"test-secret",List.of()));assertFalse(failure.getMessage().contains("test-secret"));
        }
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("private test-secret"));
        assertTrue(assertThrows(AgentModelClient.Failure.class,()->client.reply(agent(false),"test-secret",List.of())).getMessage().contains("超时"));
        verify(http,times(6)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));verifyNoInteractions(models);
        clearInvocations(http);when(executor.enabled()).thenReturn(true);
        when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenThrow(new IllegalArgumentException("Mac OpenAI 执行器离线"));
        assertThrows(AgentModelClient.Failure.class,()->client.reply(agent(false),"test-secret",List.of()));verifyNoInteractions(http);
    }
}
