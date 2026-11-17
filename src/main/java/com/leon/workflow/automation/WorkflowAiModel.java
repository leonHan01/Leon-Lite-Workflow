package com.leon.workflow.automation;

import com.leon.workflow.service.AppSettingService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Fixed provider endpoints, private authentication and bounded JSON completions for built-in AI nodes. */
final class WorkflowAiModel {
    static final String OPENAI_API = "https://api.openai.com/v1/chat/completions";
    static final String OPENAI_MODEL = "gpt-6.1-sol";
    private final WorkflowModels catalog;
    private final WorkflowHttp http;
    private final AppSettingService settings;
    private final WorkflowCredentials credentials;
    WorkflowAiModel(WorkflowHttp http, AppSettingService settings, WorkflowCredentials credentials, WorkflowModels catalog) {
        this.catalog = catalog; this.http = http; this.settings = settings; this.credentials = credentials;
    }
    static String provider(Map<String,Object> config) {
        String value = text(config.getOrDefault("provider", "deepseek"));
        if (!Set.of("deepseek", "openai").contains(value)) throw new IllegalArgumentException("请选择 DeepSeek 或 OpenAI 模型服务");
        return value;
    }
    static String model(Map<String,Object> config) {
        String value = text(config.getOrDefault("model", "openai".equals(provider(config)) ? OPENAI_MODEL : WorkflowGithubTrending.MODEL));
        if (!value.matches("[A-Za-z0-9_.:-]{1,100}")) throw new IllegalArgumentException("请填写有效的模型名称，最多 100 字符");
        return value;
    }
    static void validate(Map<String,Object> config) {
        for (String field : List.of("apiKey", "api_key", "OPENAI_API_KEY", "token", "accessToken", "authorization", "apiUrl", "baseUrl", "url", "headers", "secret", "password", "key", "openaiApiKey"))
            if (config.containsKey(field)) throw new IllegalArgumentException("节点不保存密钥或自定义 API 地址；请使用模型配置和加密凭据");
        if (config.containsKey("modelId")) {
            if (!required(config.get("modelId"), "请选择可用模型", 36).matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("模型配置引用无效");
            if (List.of("provider","model","credentialId").stream().anyMatch(config::containsKey)) throw new IllegalArgumentException("请选择统一模型配置，不能同时填写节点模型参数");
            return;
        }
        String provider = provider(config); model(config);
        if (provider.equals("openai")) required(config.get("credentialId"), "请选择 OpenAI API Key 凭据", 100);
        else if (config.containsKey("credentialId")) throw new IllegalArgumentException("DeepSeek 使用高级设置中的 API Key，不使用节点凭据");
    }

    Client connect(Map<String,Object> config, WorkflowPrivacy privacy) {
        validate(config); config = catalog.resolve(config); validate(config); boolean openai = "openai".equals(provider(config));
        String key = openai ? credentials.openai(text(config.get("credentialId")), privacy) : settings.getValue(AppSettingService.DEEPSEEK_API_KEY);
        if (key.isBlank()) throw new IllegalArgumentException("请先在高级设置配置 DeepSeek API Key");
        privacy.secret(key); privacy.secret("Bearer " + key);
        return new Client(openai, model(config), key, privacy);
    }
    final class Client {
        private final boolean openai;
        private final String model, key;
        private final WorkflowPrivacy privacy;
        private Client(boolean openai, String model, String key, WorkflowPrivacy privacy) { this.openai=openai; this.model=model; this.key=key; this.privacy=privacy; }
        String name() { return openai ? "OpenAI" : "DeepSeek"; }
        String model() { return model; }
        Map<String,Object> completeJson(String system, String user, long deadline, int maxTokens, Consumer<String> trace) throws Exception {
            var body = new LinkedHashMap<String,Object>(); body.put("model", model); body.put("stream", false);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(Map.of("role", openai ? "developer" : "system", "content", system), Map.of("role", "user", "content", user)));
            if (openai) { body.put("max_completion_tokens", maxTokens); body.put("store", false); }
            else { body.put("max_tokens", maxTokens); body.put("thinking", Map.of("type", "disabled")); }
            for (int attempt=1; attempt<=2; attempt++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if (remaining<100) throw new IllegalArgumentException(name()+" 等待超时，请稍后重试");
                var response=http.executeResolved(Map.of("url", openai ? OPENAI_API : WorkflowGithubTrending.API, "method", "POST", "failOnHttpError", false,
                        "headers", Map.of("Authorization", "Bearer "+key), "body", body, "timeoutMs", (int)Math.min(240000,remaining)),privacy,240000);
                var json=object(object(((List<?>)response.get("items")).getFirst()).get("json"));
                int status=integer(json.get("status"),200,100,599);
                if(status<200 || status>=300) throw new IllegalArgumentException(name()+" 请求未完成（HTTP "+status+"）："+switch(status) {
                    case 401,403 -> "请检查 API Key、模型访问权限及账户状态";
                    case 429 -> "请求限流或额度不足，请检查账户后重试";
                    default -> status>=500 ? "模型服务暂不可用，请稍后重试" : "请检查模型名称和账户支持情况";
                });
                if (!(json.get("body") instanceof Map<?,?> payload) || !(payload.get("choices") instanceof List<?> choices) || choices.isEmpty())
                    throw new IllegalArgumentException(name()+" 响应缺少总结结果");
                var choice=object(choices.getFirst());
                if (!"stop".equals(choice.get("finish_reason"))) throw new IllegalArgumentException(name()+" 未返回完整内容，请减少输入条数或更换模型后重试");
                var message=object(choice.get("message"));
                if (!text(message.get("refusal")).isBlank()) throw new IllegalArgumentException(name()+" 未能提供本次内容，请调整输入后重试");
                if (!(message.get("content") instanceof String content)) throw new IllegalArgumentException(name()+" 返回内容类型无效");
                trace.accept(name()+" 第 "+attempt+" 次响应："+content.length()+" 字符");
                try { return read(content); }
                catch (Exception invalidJson) {
                    if (attempt==2) throw new IllegalArgumentException(name()+" 连续两次返回空文本或无效 JSON，请稍后重试");
                    trace.accept("响应未通过 JSON 解析，将在剩余总超时内重试一次；不会触发发送");
                }
            }
            throw new IllegalStateException("AI 总结未完成");
        }
    }
}
