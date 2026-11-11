package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.monitor.MonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MonitoringIntegrationTest {
    private static final Path HISTORY;
    static {
        try { HISTORY = Files.createTempDirectory("monitor-deployments-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("leon.deployments.directory", HISTORY::toString);
        registry.add("leon.deployments.current-id", () -> "test-release");
        registry.add("leon.deployments.enabled", () -> "true");
        registry.add("leon.deployments.control-directory", () -> HISTORY.resolve("control").toString());
        registry.add("leon.deployments.lock-file", () -> HISTORY.resolve("lock").toString());
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitoringService monitoring;

    @Test void oneClickDeploymentRequiresAnAdminCookieAndOriginAndRejectsDuplicateRequests() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post("/api/monitor/deploy")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/monitor/deploy").header("Authorization", "Bearer integration-test-token"))
                .andExpect(status().isForbidden());
        String header = mvc.perform(post("/auth/login").contentType("application/json")
                .header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest")
                .content("{\"token\":\"integration-test-token\",\"remember\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        var cookie = new jakarta.servlet.http.Cookie("__Host-leon_session", header.split(";", 2)[0].split("=", 2)[1]);
        Path request = HISTORY.resolve("control/request.json"), state = HISTORY.resolve("control/job.json");
        try {
            mvc.perform(post("/api/monitor/deploy").cookie(cookie).header("Origin", "https://evil.test")
                    .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isForbidden());
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(request));
            mvc.perform(post("/api/monitor/deploy").cookie(cookie).header("Origin", "https://example.test")
                    .header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.stage").value("QUEUED"));
            org.junit.jupiter.api.Assertions.assertTrue(Files.readString(request).contains("web-release-"));
            mvc.perform(post("/api/monitor/deploy").cookie(cookie).header("Origin", "https://example.test")
                    .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isConflict());
            mvc.perform(get("/api/monitor").header("Authorization", "Bearer integration-test-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.deploymentControl.busy").value(true));
        } finally { Files.deleteIfExists(request); Files.deleteIfExists(state); }
    }

    @Test void deploymentHistoryUsesTheExistingAuthenticationAndDoesNotDuplicateOnRefresh() throws Exception {
        Path record = HISTORY.resolve("test-release.json");
        String manifest = """
                {"id":"test-release","version":"2026.11.12.1","commit":"bbd8d1f",
                 "startedAt":"2026-11-11T16:00:00Z","finishedAt":"2026-11-11T16:01:00Z",
                 "dirty":false,"description":"监控部署记录","status":"SUCCESS","jarSha256":"abc"}
                """;
        Files.writeString(record, manifest);
        try {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/monitor")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.deployments").doesNotExist());
            for (int index = 0; index < 2; index++) {
                mvc.perform(get("/api/monitor").header("Authorization", "Bearer integration-test-token"))
                        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.deployments.currentId").value("test-release"))
                        .andExpect(jsonPath("$.deployments.total").value(1))
                        .andExpect(jsonPath("$.deployments.records[0].version").value("2026.11.12.1"))
                        .andExpect(jsonPath("$.deployments.records[0].status").value("SUCCESS"));
            }
            org.junit.jupiter.api.Assertions.assertEquals(manifest, Files.readString(record));
        } finally { Files.delete(record); }
    }

    @Test void protectsMonitoringAndPersistsDailyViews() throws Exception {
        for (int attempt = 0; attempt < 40 && monitoring.dashboard().current() == null; attempt++) Thread.sleep(50);
        var sample = monitoring.dashboard().current();
        org.junit.jupiter.api.Assertions.assertNotNull(sample);
        org.junit.jupiter.api.Assertions.assertTrue(sample.diskTotalBytes() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(sample.diskPercent() >= 0 && sample.diskPercent() <= 100);
        if (sample.cpuPercent() != null) org.junit.jupiter.api.Assertions.assertTrue(sample.cpuPercent() >= 0 && sample.cpuPercent() <= 100);
        if (sample.memoryPercent() != null) org.junit.jupiter.api.Assertions.assertTrue(sample.memoryPercent() >= 0 && sample.memoryPercent() <= 100);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM monitor_page_views");
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("leon:integration-test-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/monitor")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/monitor/page-view")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/monitor/page-view").header("Authorization", basic)).andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/monitor/page-view").header("Authorization", basic)
                    .header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/monitor").header("Authorization", basic))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pageViews.length()").value(30))
                .andExpect(jsonPath("$.pageViews[29].views").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(2L, jdbc.queryForObject("SELECT SUM(views) FROM monitor_page_views", Long.class));
    }
}
