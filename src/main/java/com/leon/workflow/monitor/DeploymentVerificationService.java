package com.leon.workflow.monitor;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.BrowserSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Tests public authentication using a temporary normal session, revoked immediately after the check. */
@Service
public class DeploymentVerificationService {
    private final DeploymentHistoryService history;
    private final BrowserSessionService sessions;
    private final ApiTokenInterceptor auth;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path control;
    private final String origin;
    private final String alfredKey;

    public DeploymentVerificationService(DeploymentHistoryService history, BrowserSessionService sessions,
            ApiTokenInterceptor auth, ObjectMapper mapper,
            @Value("${leon.deployments.self-verify:false}") boolean enabled,
            @Value("${leon.deployments.control-directory:./data/deploy-control}") String control,
            @Value("${leon.auth.admin-origin:}") String origin,
            @Value("${leon.alfred.api-key:}") String alfredKey) {
        this.history = history; this.sessions = sessions; this.auth = auth; this.mapper = mapper;
        this.enabled = enabled; this.control = Path.of(control); this.origin = origin; this.alfredKey = alfredKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (!enabled) return;
        var data = history.history();
        if (data.records().stream().anyMatch(record -> record.id().equals(data.currentId()) && record.status().equals("DEPLOYING")))
            CompletableFuture.runAsync(() -> verify(data.currentId()));
    }

    void verify(String id) {
        String cookie = null;
        boolean success = false;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            if (!auth.enabled() || !origin.startsWith("https://")) throw new IllegalStateException();
            String signature = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("1+2" + alfredKey).getBytes(StandardCharsets.UTF_8)));
            String body = mapper.writeValueAsString(Map.of("param", "1+2", "key", signature));
            var unauthenticated = HttpRequest.newBuilder(URI.create(origin + "/alfred/query")).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            if (client.send(unauthenticated, HttpResponse.BodyHandlers.discarding()).statusCode() != 401) throw new IllegalStateException();
            cookie = sessions.create(false, auth.fingerprint());
            var authorized = HttpRequest.newBuilder(URI.create(origin + "/alfred/query")).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("Origin", origin).header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", BrowserSessionService.COOKIE_NAME + "=" + cookie)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(authorized, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException();
            for (var item : mapper.readTree(response.body())) {
                if (item.path("title").asText().equals("3") || item.path("msgNeedToCopy").asText().equals("3")) success = true;
            }
        } catch (Exception ignored) { /* Receipt contains only the result, never credentials or response bodies. */ }
        finally { if (cookie != null) sessions.revokeValue(cookie); }
        try {
            Files.createDirectories(control);
            Path temporary = Files.createTempFile(control, ".verification-", ".tmp");
            try {
                Files.writeString(temporary, mapper.writeValueAsString(Map.of("id", id, "success", success, "checkedAt", Instant.now())));
                Files.move(temporary, control.resolve("verification-" + id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (Exception ignored) { /* Missing receipt makes the external runner roll back. */ }
    }
}
