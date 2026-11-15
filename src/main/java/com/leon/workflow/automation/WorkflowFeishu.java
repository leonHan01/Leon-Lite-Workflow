package com.leon.workflow.automation;

import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Sends one custom-bot message; webhook credentials never become part of the workflow definition. */
@Component
public class WorkflowFeishu {
    private static final int MAX_MESSAGE_BYTES = 20 * 1024;
    private final WorkflowCredentials credentials;
    private final WorkflowHttp http;
    public WorkflowFeishu(WorkflowCredentials credentials, WorkflowHttp http) { this.credentials = credentials; this.http = http; }

    static String webhookUrl(Object value) {
        String url = required(value, "请填写飞书机器人 Webhook 地址", 8192);
        try {
            URI uri = URI.create(url);
            if ("https".equals(uri.getScheme()) && "open.feishu.cn".equals(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getRawPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]{1,200}")) return url;
        } catch (IllegalArgumentException ignored) {}
        throw new IllegalArgumentException("请使用 https://open.feishu.cn/open-apis/bot/v2/hook/ 开头的飞书机器人地址");
    }
    static void validateConfig(Map<String, Object> config) {
        required(config.get("credentialId"), "请选择飞书机器人凭据", 100);
        if (!Set.of("text", "card").contains(text(config.getOrDefault("messageType", "text")))) throw new IllegalArgumentException("请选择文本或 Markdown 卡片");
        required(config.get("text"), "请填写飞书消息内容", MAX_MESSAGE_BYTES);
        if ("card".equals(config.get("messageType"))) required(config.get("title"), "请填写卡片标题", 200);
        integer(config.get("timeoutMs"), 10_000, 100, 30_000);
        for (String key : List.of("url", "webhook", "webhookUrl", "secret", "signingSecret"))
            if (config.containsKey(key)) throw new IllegalArgumentException("Webhook 地址和签名密钥请保存在飞书机器人凭据中");
    }
    static String sign(String secret, String timestamp) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }
    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> input, Map<String, Object> ctx, WorkflowPrivacy privacy) throws Exception {
        validateConfig(config);
        var credential = credentials.feishu(text(config.get("credentialId")), privacy);
        String content = required(WorkflowExpressions.resolveRequired(config.get("text"), input, ctx, "飞书正文"), "飞书消息内容解析后不能为空", MAX_MESSAGE_BYTES);
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("card".equals(config.get("messageType"))) {
            String title = required(WorkflowExpressions.resolveRequired(config.get("title"), input, ctx, "飞书标题"), "飞书卡片标题解析后不能为空", 200);
            payload.put("msg_type", "interactive");
            payload.put("card", Map.of("schema", "2.0", "config", Map.of("update_multi", true),
                    "header", Map.of("title", Map.of("tag", "plain_text", "content", title), "template", "blue"),
                    "body", Map.of("elements", List.of(Map.of("tag", "markdown", "content", content)))));
        } else {
            payload.put("msg_type", "text"); payload.put("content", Map.of("text", content));
        }
        if (!credential.secret().isEmpty()) {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String sign = sign(credential.secret(), timestamp);
            privacy.secret(sign); payload.put("timestamp", timestamp); payload.put("sign", sign);
        }
        if (write(payload).getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("飞书消息请求体不能超过 20 KB，请缩短消息内容");
        var response = http.executeResolved(Map.of("url", credential.url(), "method", "POST", "body", payload,
                "timeoutMs", integer(config.get("timeoutMs"), 10_000, 100, 30_000)), privacy);
        Object body = object(object(((List<?>)response.get("items")).getFirst()).get("json")).get("body");
        if (!(body instanceof Map<?, ?> result)) throw new IllegalArgumentException("飞书返回的响应格式无效");
        int code;
        try { code = Integer.parseInt(text(result.containsKey("code") ? result.get("code") : result.get("StatusCode"))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("飞书返回的响应格式无效"); }
        if (code != 0) throw new IllegalArgumentException(switch (code) {
            case 19021 -> "飞书签名校验失败，请检查机器人签名密钥及服务器时间";
            case 19022 -> "飞书 IP 白名单校验失败，请检查机器人安全设置";
            case 19024 -> "飞书关键词校验失败，请在消息中包含机器人配置的关键词";
            case 11232 -> "飞书发送频率受限，请稍后重新运行";
            default -> "飞书消息发送失败，错误码 " + code;
        });
        return input;
    }
}
