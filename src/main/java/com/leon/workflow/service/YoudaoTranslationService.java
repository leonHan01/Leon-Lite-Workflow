package com.leon.workflow.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class YoudaoTranslationService {
    public record Translation(String value, String source) {}

    private final String appKey;
    private final String appSecret;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;
    private final Supplier<String> salt;

    @Autowired
    public YoudaoTranslationService(@Value("${leon.youdao.app-key:}") String appKey,
                                    @Value("${leon.youdao.app-secret:}") String appSecret, ObjectMapper mapper) {
        this(appKey, appSecret, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    YoudaoTranslationService(String appKey, String appSecret, ObjectMapper mapper,
                             HttpClient client, Clock clock, Supplier<String> salt) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.mapper = mapper;
        this.client = client;
        this.clock = clock;
        this.salt = salt;
    }

    public boolean configured() { return !appKey.isBlank() && !appSecret.isBlank(); }

    public List<Translation> translate(String text) {
        if (!configured() || text == null || text.isBlank()) return List.of();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://openapi.youdao.com/api"))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form(text), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().isBlank()) return List.of();
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | tools.jackson.core.JacksonException e) {
            return List.of();
        }
    }

    private String form(String text) {
        boolean chinese = text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        String nonce = salt.get();
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String input = text.length() <= 20 ? text : text.substring(0, 10) + text.length() + text.substring(text.length() - 10);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("q", text);
        values.put("from", chinese ? "zh-CHS" : "en");
        values.put("to", chinese ? "en" : "zh-CHS");
        values.put("appKey", appKey);
        values.put("salt", nonce);
        values.put("curtime", timestamp);
        values.put("signType", "v3");
        values.put("sign", sha256(appKey + input + nonce + timestamp + appSecret));
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private List<Translation> parse(String response) {
        JsonNode payload = mapper.readTree(response);
        List<Translation> results = new ArrayList<>();
        for (JsonNode value : payload.path("translation")) {
            if (value.isString() && !value.asText().isBlank()) results.add(new Translation(value.asText(), null));
        }
        for (JsonNode entry : payload.path("web")) {
            if (!entry.path("key").isString() || !entry.path("value").isArray()) continue;
            for (JsonNode value : entry.path("value")) {
                if (value.isString() && !value.asText().isBlank()) results.add(new Translation(value.asText(), entry.path("key").asText()));
            }
        }
        return List.copyOf(results);
    }
}
