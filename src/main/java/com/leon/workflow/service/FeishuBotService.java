package com.leon.workflow.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeishuBotService {
    private static final Logger log = LoggerFactory.getLogger(FeishuBotService.class);
    private static final int TEXT_LIMIT = 2000;

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public FeishuBotService(AppSettingService appSettingService, ObjectMapper objectMapper) {
        this(appSettingService, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
    }

    FeishuBotService(AppSettingService appSettingService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.appSettingService = appSettingService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public boolean sendCard(String title, String markdown, String template) {
        return sendCardToWebhook(title, markdown, template, appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL));
    }

    public boolean sendCardToWebhook(String title, String markdown, String template, String webhook) {
        if (webhook.isBlank()) {
            log.info("飞书 Webhook 未配置，跳过通知：{}", title);
            return false;
        }
        try {
            Map<String, Object> payload = cardPayload(title, markdown, template);
            String secret = appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_SECRET);
            if (!secret.isBlank()) {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                payload.put("timestamp", timestamp);
                payload.put("sign", sign(secret, timestamp));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = objectMapper.readTree(response.body().isBlank() ? "{}" : response.body());
            int code = body.path("code").isMissingNode() ? body.path("StatusCode").asInt(0) : body.path("code").asInt(0);
            if (response.statusCode() >= 400 || code != 0) {
                throw new IllegalStateException(body.path("msg").asText(body.path("StatusMessage").asText("HTTP " + response.statusCode())));
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            log.warn("飞书通知失败：{}", title);
            return false;
        }
    }

    private Map<String, Object> cardPayload(String title, String markdown, String template) {
        String headerTitle = title == null || title.isBlank() ? "Leon Tools 通知" : title;
        String color = template == null || template.isBlank() ? "blue" : template;
        Map<String, Object> markdownNode = dict(
                "tag", "markdown",
                "content", clip(markdown),
                "text_align", "left",
                "text_size", "normal_v2",
                "margin", "0px 0px 0px 0px"
        );
        Map<String, Object> card = dict(
                "schema", "2.0",
                "config", dict("update_multi", true),
                "header", dict(
                        "title", dict("tag", "plain_text", "content", headerTitle),
                        "subtitle", dict("tag", "plain_text", "content", ""),
                        "template", color,
                        "padding", "12px 12px 12px 12px"
                ),
                "body", dict(
                        "direction", "vertical",
                        "padding", "12px 12px 12px 12px",
                        "elements", List.of(markdownNode)
                )
        );
        return dict("msg_type", "interactive", "card", card);
    }

    private static Map<String, Object> dict(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    private String sign(String secret, String timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

    private String clip(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return "(无内容)";
        }
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT) + "…";
    }
}
