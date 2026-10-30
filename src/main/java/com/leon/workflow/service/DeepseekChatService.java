package com.leon.workflow.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeepseekChatService {
    private static final String URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public DeepseekChatService(AppSettingService appSettingService, ObjectMapper objectMapper) {
        this(appSettingService, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    DeepseekChatService(AppSettingService appSettingService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.appSettingService = appSettingService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public String complete(String system, String user) {
        String apiKey = appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY);
        if (apiKey.isBlank()) {
            throw new IllegalStateException("未配置 DeepSeek API Key");
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", user));

            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "disabled");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("messages", messages);
            body.put("thinking", thinking);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() >= 400) {
                String message = payload.path("error").path("message").asText(payload.path("message").asText("HTTP " + response.statusCode()));
                throw new IllegalStateException(message);
            }
            String content = payload.path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) {
                throw new IllegalStateException("LLM 返回空内容");
            }
            return content;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek 调用已中断", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用 DeepSeek 失败", exception);
        }
    }
}
