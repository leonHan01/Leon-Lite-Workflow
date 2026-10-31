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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MonitoringIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitoringService monitoring;

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
