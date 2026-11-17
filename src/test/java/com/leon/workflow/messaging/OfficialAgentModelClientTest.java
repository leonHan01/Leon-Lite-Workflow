package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
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
            when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer test-secret\"}}]}".getBytes(StandardCharsets.UTF_8));
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
            var client=new OfficialAgentModelClient(json,http);
            var agent=new AgentRegistry.Agent("id","name","handle",provider,"test-model","",true,true,1);
            assertEquals("answer [已隐藏凭据]",client.complete(agent,"test-secret",List.of(Map.of("role","user","content","question"))));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void errorsNeverReturnRawProviderBodyAndTruncatedRepliesFail() throws Exception {
        HttpClient http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(401);when(response.body()).thenReturn("echo test-secret".getBytes(StandardCharsets.UTF_8));
        var client=new OfficialAgentModelClient(json,http);var agent=new AgentRegistry.Agent("id","name","handle","openai","test-model","",true,true,1);
        var error=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));assertFalse(error.getMessage().contains("test-secret"));
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".getBytes(StandardCharsets.UTF_8));
        assertThrows(AgentModelClient.Failure.class,()->client.complete(agent,"test-secret",List.of()));
    }
    @Test void boundsResponseBody() {
        var subscriber=new OfficialAgentModelClient.LimitedBody();Flow.Subscription subscription=mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1_048_577])));
        assertTrue(subscriber.result.isCompletedExceptionally());verify(subscription).cancel();
    }
    private String body(HttpRequest request) {
        var result=new CompletableFuture<String>();var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}
            public void onError(Throwable error){result.completeExceptionally(error);}
            public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return result.join();
    }
}
