package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.leon.workflow.automation.WorkflowModels;
import com.leon.workflow.ai.OpenAiExecutor;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficialAgentModelClientTest {
    private final ObjectMapper json=new ObjectMapper();
    @SuppressWarnings("unchecked")
    @Test void sendsProviderSpecificRequestsToOfficialEndpointsAndRedactsKey() throws Exception {
        for(String provider:List.of("openai","deepseek")) {
            HttpClient http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer test-secret\"}}]}".getBytes(StandardCharsets.UTF_8));
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(call->{
                HttpRequest request=call.getArgument(0);
                assertEquals(provider.equals("openai")?"https://api.openai.com/v1/chat/completions":"https://api.deepseek.com/chat/completions",request.uri().toString());
                assertEquals("Bearer test-secret",request.headers().firstValue("Authorization").orElseThrow());
                var body=json.readTree(body(request));assertEquals("test-model",body.path("model").asText());
                assertFalse(body.path("stream").asBoolean(true));
                if(provider.equals("openai")){assertFalse(body.path("store").asBoolean(true));assertEquals(2048,body.path("max_completion_tokens").asInt());}
                else {assertEquals(2048,body.path("max_tokens").asInt());assertEquals("disabled",body.path("thinking").path("type").asText());}
                return response;
            });
            var models=mock(WorkflowModels.class); var client=new OfficialAgentModelClient(json,http,models,mock(OpenAiExecutor.class));
            var agent=new AgentRegistry.Agent("id","name","handle",provider,"test-model","",true,true,1);
            assertEquals("answer [已隐藏凭据]",client.complete(agent,"test-secret",List.of(Map.of("role","user","content","question"))));
            verify(models).recordUsage(provider,"test-model",Map.of("prompt_tokens",20,"completion_tokens",5,"total_tokens",25));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void errorsNeverReturnRawProviderBodyAndTruncatedRepliesFail() throws Exception {
        HttpClient http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(401);when(response.body()).thenReturn("echo test-secret".getBytes(StandardCharsets.UTF_8));
        var models=mock(WorkflowModels.class); var client=new OfficialAgentModelClient(json,http,models,mock(OpenAiExecutor.class));var agent=new AgentRegistry.Agent("id","name","handle","openai","test-model","",true,true,1);
        var error=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));assertFalse(error.getMessage().contains("test-secret"));
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));
        verify(models).recordUsage("openai","test-model",null);
    }
    @Test void distinguishesConnectionAndReplyTimeoutsWithoutRetryingOrInventingUsage() throws Exception {
        for(String provider:List.of("openai","deepseek")) {
            var http=mock(HttpClient.class); var models=mock(WorkflowModels.class);
            var client=new OfficialAgentModelClient(json,http,models,mock(OpenAiExecutor.class));
            var agent=new AgentRegistry.Agent("id","name","handle",provider,"test-model","",true,true,1);
            String name=provider.equals("openai")?"OpenAI":"DeepSeek";
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpConnectTimeoutException("private transport detail test-secret"));
            var connection=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));
            assertTrue(connection.getMessage().startsWith("连接 "+name+" 超时"),connection.getMessage());
            assertFalse(connection.getMessage().contains("test-secret"));
            verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); verifyNoInteractions(models);
            reset(http);
            when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("private transport detail test-secret"));
            var reply=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));
            assertTrue(reply.getMessage().startsWith("等待 "+name+" 回复超时"),reply.getMessage());
            assertFalse(reply.getMessage().contains("test-secret"));
            verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); verifyNoInteractions(models);
        }
    }
    @Test void onlyOpenAiIsSentToTheMacAndUsageIsNotRecordedTwice() throws Exception {
        var http=mock(HttpClient.class);var models=mock(WorkflowModels.class);var executor=mock(OpenAiExecutor.class);
        when(executor.enabled()).thenReturn(true);
        when(executor.execute(anyMap(),eq("test-secret"),eq(180000))).thenReturn(Map.of("status",200,"body",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","Mac answer"))))));
        var client=new OfficialAgentModelClient(json,http,models,executor);
        var agent=new AgentRegistry.Agent("id","name","handle","openai","gpt-6-luna","",true,true,1);
        assertEquals("Mac answer",client.complete(agent,"test-secret",List.of(Map.of("role","user","content","question"))));
        verify(executor).execute(anyMap(),eq("test-secret"),eq(180000));verifyNoInteractions(http,models);
        when(executor.execute(anyMap(),eq("test-secret"),eq(180000))).thenThrow(new IllegalArgumentException("Mac OpenAI 执行器离线，请先启动执行器后重试"));
        var offline=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));
        assertTrue(offline.getMessage().contains("Mac OpenAI 执行器离线"));verifyNoInteractions(http);
        clearInvocations(executor);
        var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"direct answer\"}}]}".getBytes(StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var deepseek=new AgentRegistry.Agent("id","name","handle","deepseek","deepseek-flash","",true,true,1);
        assertEquals("direct answer",client.complete(deepseek,"test-secret",List.of()));
        verify(executor,never()).execute(anyMap(),anyString(),anyInt());verify(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void boundsResponseBody() {
        var subscriber=new OfficialAgentModelClient.LimitedBody();Flow.Subscription subscription=mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1_048_577])));
        assertTrue(subscriber.result.isCompletedExceptionally());verify(subscription).cancel();
    }
    @Test void drawingUsesMacResponsesWhileSummariesStayOnTextEndpoint() throws Exception {
        var http=mock(HttpClient.class);var models=mock(WorkflowModels.class);var executor=mock(OpenAiExecutor.class);
        when(executor.enabled()).thenReturn(true);
        when(executor.executeResponses(anyMap(),eq("test-secret"),eq(240000),isNull())).thenReturn(Map.of("status",200,"body",Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","普通聊天 test-secret")))))));
        when(executor.execute(anyMap(),eq("test-secret"),eq(180000))).thenReturn(Map.of("status",200,"body",Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","摘要"))))));
        var client=new OfficialAgentModelClient(json,http,models,executor);
        var agent=new AgentRegistry.Agent("id","name","handle","openai","gpt-6.1-sol","",true,true,1,"alfred",null,"",true,AgentRegistry.DEFAULT_IMAGE_MODEL);
        assertEquals("普通聊天 [已隐藏凭据]",client.reply(agent,"test-secret",List.of(Map.of("role","user","content","你好"))).text());
        assertEquals("摘要",client.complete(agent,"test-secret",List.of(Map.of("role","user","content","总结"))));
        var request=org.mockito.ArgumentCaptor.forClass(Map.class);verify(executor).executeResponses(request.capture(),eq("test-secret"),eq(240000),isNull());
        assertTrue(request.getValue().containsKey("tools"));assertFalse(request.getValue().containsKey("messages"));
        var summary=org.mockito.ArgumentCaptor.forClass(Map.class);verify(executor).execute(summary.capture(),eq("test-secret"),eq(180000));assertFalse(summary.getValue().containsKey("tools"));
        verifyNoInteractions(http,models);
    }
    @Test void invalidImagesIncompleteRepliesAndProviderErrorsNeverBecomeSuccessfulMessages() throws Exception {
        var executor=mock(OpenAiExecutor.class);when(executor.enabled()).thenReturn(true);
        var client=new OfficialAgentModelClient(json,mock(HttpClient.class),mock(WorkflowModels.class),executor);
        var agent=new AgentRegistry.Agent("id","name","handle","openai","gpt-6.1-sol","",true,true,1,"alfred",null,"",true,AgentRegistry.DEFAULT_IMAGE_MODEL);
        for(Map<String,Object> payload:List.<Map<String,Object>>of(
                Map.of("status","incomplete","output",List.of()),
                Map.of("status","completed","output",List.of(Map.of("type","image_generation_call","status","completed","result","not base64!"))),
                Map.of("status","completed","output",List.of(Map.of("type","image_generation_call","status","failed","result","YWJj"))),
                Map.of("status","completed","output",List.of()))){
            when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenReturn(Map.of("status",200,"body",payload));
            assertThrows(AgentModelClient.Failure.class,()->client.reply(agent,"test-secret",List.of()));
        }
        when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenReturn(Map.of("status",403,"body",Map.of("error","test-secret")));
        var failure=assertThrows(AgentModelClient.Failure.class,()->client.reply(agent,"test-secret",List.of()));assertFalse(failure.getMessage().contains("test-secret"));
        when(executor.executeResponses(anyMap(),anyString(),anyInt(),isNull())).thenReturn(Map.of("status",200,"body",Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","refusal","refusal","请换一个图片主题。")))))));
        assertEquals("请换一个图片主题。",client.reply(agent,"test-secret",List.of()).text());
    }
    private String body(HttpRequest request) { return requestBody(request); }
    static String requestBody(HttpRequest request) {
        var result=new CompletableFuture<String>();var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}
            public void onError(Throwable error){result.completeExceptionally(error);}
            public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return result.join();
    }
}
