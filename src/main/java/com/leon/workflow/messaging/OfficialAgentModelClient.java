package com.leon.workflow.messaging;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class OfficialAgentModelClient implements AgentModelClient {
    private final ObjectMapper json;
    private final HttpClient http;
    @Autowired public OfficialAgentModelClient(ObjectMapper json) { this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()); }
    OfficialAgentModelClient(ObjectMapper json,HttpClient http) { this.json=json;this.http=http; }
    @Override public String complete(AgentRegistry.Agent agent,String key,List<Map<String,String>> messages) throws Exception {
        boolean openai=agent.provider().equals("openai");
        var body=new LinkedHashMap<String,Object>(); body.put("model",agent.model()); body.put("messages",messages); body.put("stream",false);
        if (openai) { body.put("max_completion_tokens",2048); body.put("store",false); }
        else { body.put("max_tokens",2048); body.put("thinking",Map.of("type","disabled")); }
        var request=HttpRequest.newBuilder(URI.create(openai ? "https://api.openai.com/v1/chat/completions" : "https://api.deepseek.com/chat/completions"))
                .timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response;
        try { response=http.send(request, info -> new LimitedBody()); }
        catch (HttpTimeoutException e) { throw new AgentModelClient.Failure("模型请求超时，请确认后手动重试"); }
        if (response.statusCode()<200 || response.statusCode()>=300) throw new AgentModelClient.Failure(switch(response.statusCode()) {
            case 401,403 -> "模型认证失败，请检查 Agent 的 API Key 和模型权限";
            case 429 -> "模型限流或额度不足，请稍后重试";
            default -> "模型服务请求失败（HTTP "+response.statusCode()+"）";
        });
        var choice=json.readTree(response.body()).path("choices").path(0);
        if (!"stop".equals(choice.path("finish_reason").asText())) throw new AgentModelClient.Failure("模型未生成完整回复，请缩短问题后重试");
        String result=choice.path("message").path("content").asText("").strip();
        if (result.isEmpty() || result.length()>8000) throw new AgentModelClient.Failure("模型回复为空或超过 8000 字符，请缩短问题后重试");
        // Never persist a credential even if a provider unexpectedly echoes it.
        if (!key.isEmpty()) result=result.replace(key,"[已隐藏凭据]");
        return result;
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream buffer=new ByteArrayOutputStream(); Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if ((long)buffer.size()+chunk.remaining()>1_048_576) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("模型响应过大")); return; }
                byte[] bytes=new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
