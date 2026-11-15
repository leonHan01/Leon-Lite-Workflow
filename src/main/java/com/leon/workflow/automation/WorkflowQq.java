package com.leon.workflow.automation;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.leon.workflow.automation.WorkflowJson.*;

/** One official QQ Bot API message per execution. Credentials and tokens stay server-side. */
@Component
public class WorkflowQq {
    static final String API = "https://api.bot.qq.com";
    static final int MAX_MESSAGE_BYTES = 2000;
    private final WorkflowCredentials credentials;
    private final WorkflowHttp http;
    private final Map<String, CachedToken> tokens = new LinkedHashMap<>(16, .75f, true);
    private static final class CachedToken {
        final String appId, secret, token;
        final long refreshAt;
        CachedToken(String appId, String secret, String token, long refreshAt) {
            this.appId = appId; this.secret = secret; this.token = token; this.refreshAt = refreshAt;
        }
    }
    public WorkflowQq(WorkflowCredentials credentials, WorkflowHttp http) { this.credentials = credentials; this.http = http; }

    static Map<String, Object> credential(Map<String, Object> value) {
        String appId = required(value.get("appId"), "请填写 QQ 机器人 AppID（数字）", 32);
        if (!appId.matches("[0-9]{1,32}")) throw new IllegalArgumentException("QQ 机器人 AppID 必须为数字");
        String secret = required(value.get("appSecret"), "请填写 QQ 机器人 AppSecret，最多 1024 个字符", 1024);
        if (secret.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("QQ 机器人 AppSecret 不能包含空白字符");
        return Map.of("appId", appId, "appSecret", secret);
    }
    static void validateConfig(Map<String, Object> config) {
        required(config.get("credentialId"), "请选择 QQ 机器人凭据", 100);
        if (!Set.of("user", "group").contains(text(config.getOrDefault("targetType", "user"))))
            throw new IllegalArgumentException("请选择 QQ 单聊或 QQ 群聊");
        if (!Set.of("text", "markdown").contains(text(config.getOrDefault("messageType", "text"))))
            throw new IllegalArgumentException("请选择 QQ 文本或 Markdown 消息");
        String target = required(config.get("targetId"), "请填写接收目标 OpenID（不是 QQ 号或群号）", 1000);
        if (WorkflowExpressions.references(target).isEmpty()) targetId(target);
        required(config.get("text"), "请填写 QQ 消息内容，最多 64 KB 模板", 65_536);
        integer(config.get("timeoutMs"), 10_000, 100, 30_000);
        for (String key : List.of("url", "headers", "appId", "appSecret", "secret", "token", "accessToken", "access_token", "clientSecret"))
            if (config.containsKey(key)) throw new IllegalArgumentException("QQ 连接信息请保存在机器人凭据中，节点只引用凭据 ID");
    }
    private static String targetId(Object value) {
        String id = text(value).trim();
        if (!id.matches("[A-Za-z0-9_-]{16,128}") || id.matches("[0-9]+"))
            throw new IllegalArgumentException("QQ 接收目标必须为官方事件中的 OpenID，不能填写 QQ 号、群号或地址");
        return id;
    }
    public Map<String, Object> execute(Map<String, Object> config, Map<String, Object> input, Map<String, Object> ctx, WorkflowPrivacy privacy) throws Exception {
        validateConfig(config);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(integer(config.get("timeoutMs"), 10_000, 100, 30_000));
        String id = text(config.get("credentialId"));
        var credential = credentials.qq(id, privacy);
        String target = targetId(WorkflowExpressions.resolveRequired(config.get("targetId"), input, ctx, "QQ 接收目标"));
        String content = required(WorkflowExpressions.resolveRequired(config.get("text"), input, ctx, "QQ 正文"), "QQ 消息内容解析后不能为空", 65_536);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("QQ 消息正文不能超过 2000 UTF-8 字节，请发送摘要；不会自动拆分或截断");
        Map<String, Object> payload = "markdown".equals(config.get("messageType"))
                ? Map.of("msg_type", 2, "markdown", Map.of("content", content)) : Map.of("msg_type", 0, "content", content);
        String token = accessToken(id, credential, privacy, deadline);
        String path = "group".equals(config.get("targetType")) ? "/v2/groups/" : "/v2/users/";
        try {
            var result = request(path + target + "/messages", payload, Map.of("Authorization", "QQBot " + token), privacy, deadline, "发送消息");
            if (!(result.get("id") instanceof String messageId) || messageId.isBlank())
                throw new IllegalArgumentException("QQ 发送消息响应无消息 ID，无法确认结果；请确认接收情况后手动重跑");
        } catch (Exception error) {
            // A rejected or indeterminate send is never retried. Refresh credentials only on a future execution.
            synchronized (tokens) { if (tokens.get(id) != null && tokens.get(id).token.equals(token)) tokens.remove(id); }
            throw error;
        }
        return input;
    }
    private String accessToken(String id, Map<String, Object> credential, WorkflowPrivacy privacy, long deadline) throws Exception {
        String appId = text(credential.get("appId")), secret = text(credential.get("appSecret"));
        synchronized (tokens) {
            CachedToken cached = tokens.get(id);
            if (cached != null && cached.appId.equals(appId) && cached.secret.equals(secret) && System.nanoTime() < cached.refreshAt) {
                privacy.secret(cached.token); privacy.secret("QQBot " + cached.token); return cached.token;
            }
            tokens.remove(id);
            long requestedAt = System.nanoTime();
            var result = request("/app/getAppAccessToken", Map.of("appId", appId, "clientSecret", secret), Map.of(), privacy, deadline, "获取访问令牌");
            if (!(result.get("access_token") instanceof String token) || !token.matches("[A-Za-z0-9._~+/=-]{3,8192}"))
                throw new IllegalArgumentException("QQ 访问令牌响应格式无效");
            int expires = integer(result.get("expires_in"), 0, 1, 7200);
            privacy.secret(token); privacy.secret("QQBot " + token);
            // Refresh early, and never extend lifetime by the duration of the HTTP request.
            long refreshAt = requestedAt + TimeUnit.MILLISECONDS.toNanos(expires * 1000L - Math.min(60_000, expires * 200L));
            if (tokens.size() >= 64) tokens.remove(tokens.keySet().iterator().next());
            tokens.put(id, new CachedToken(appId, secret, token, refreshAt));
            return token;
        }
    }
    String bindingAccessToken(String id) throws Exception {
        var privacy = new WorkflowPrivacy();
        return accessToken(id, credentials.qq(id, privacy), privacy, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
    }
    URI bindingGateway(String token) throws Exception {
        var privacy = new WorkflowPrivacy(); privacy.secret(token);
        var result = request("/gateway", "GET", Map.of(), Map.of("Authorization", "QQBot " + token), privacy,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), "获取绑定网关");
        try {
            URI url = URI.create(text(result.get("url")));
            if ("wss".equals(url.getScheme()) && "api.bot.qq.com".equals(url.getHost()) && (url.getPort() == -1 || url.getPort() == 443)
                    && url.getUserInfo() == null && url.getRawQuery() == null && url.getFragment() == null && "/websocket/".equals(url.getRawPath())) return url;
        } catch (IllegalArgumentException ignored) {}
        throw new IllegalArgumentException("QQ 返回的绑定网关地址无效，请稍后重试");
    }
    private Map<String, Object> request(String path, Object body, Map<String, String> headers, WorkflowPrivacy privacy, long deadline, String action) throws Exception {
        return request(path, "POST", body, headers, privacy, deadline, action);
    }
    private Map<String, Object> request(String path, String method, Object body, Map<String, String> headers, WorkflowPrivacy privacy, long deadline, String action) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining < 100) throw new IllegalArgumentException("QQ 节点超时，请确认接收情况后再运行");
        var response = http.executeResolved(Map.of("url", API + path, "method", method, "body", body, "headers", headers,
                "failOnHttpError", false, "timeoutMs", (int)Math.min(30_000, remaining)), privacy);
        var json = object(object(((List<?>)response.get("items")).getFirst()).get("json"));
        Object raw = json.get("body");
        privacy.collect(raw);
        int status = integer(json.get("status"), 0, 100, 599);
        if (raw instanceof Map<?, ?> result && result.containsKey("code")) {
            int code;
            try { code = Integer.parseInt(text(result.get("code"))); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("QQ " + action + "响应格式无效"); }
            if (code != 0) throw new IllegalArgumentException("QQ " + action + "失败（错误码 " + code + "）：" + switch (code) {
                case 100007, 100016, 10004 -> "请检查机器人 AppID、AppSecret 及启用状态";
                case 40034105 -> "没有主动消息权限，请检查开放平台权限及 QQ 中允许主动发送的设置";
                case 40054004 -> "没有好友关系，请先使用接收 QQ 添加此机器人";
                case 40054013 -> "接收方已拒收消息，请开启机器人消息接收";
                case 100001, 22009, 40034100 -> "发送频率或额度受限，请稍后手动运行";
                case 40034006 -> "消息内容被平台拒绝，请调整正文";
                case 40034127, 304036, 50056 -> "没有 Markdown 权限，请改用文本消息";
                case 40054007, 40054018 -> "消息过长或异常，请缩短正文";
                default -> "请检查机器人权限、接收目标及平台设置";
            });
        }
        if (status < 200 || status >= 300) throw new IllegalArgumentException("QQ " + action + "失败，HTTP 状态码 " + status + "；请检查认证、权限或平台状态");
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("QQ " + action + "响应格式无效");
        return object(raw);
    }
}
