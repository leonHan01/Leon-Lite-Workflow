package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.StringReader;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:backuptest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test"})
class DatabaseBackupIntegrationTest {
    @DynamicPropertySource
    static void token(DynamicPropertyRegistry registry) {
        registry.add("leon.auth.token-sha256", () -> BrowserSessionService.hash("integration-test-token"));
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedBackupRestoresDataWithoutSessionsOrPasswords() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/admin/database-backup")).andExpect(status().isUnauthorized());
        String setCookie = mvc.perform(post("/auth/login").header("Origin", "https://example.test")
                        .header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                        .content("{\"token\":\"integration-test-token\",\"remember\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie(BrowserSessionService.COOKIE_NAME, setCookie.split(";", 2)[0].split("=", 2)[1]);
        jdbc.execute("CREATE TABLE BACKUP_TEST (ID BIGINT PRIMARY KEY, VALUE_TEXT VARCHAR(200))");
        jdbc.update("INSERT INTO BACKUP_TEST VALUES (?, ?)", 42L, "备份 'data'\nsecond line");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM BROWSER_SESSION", Integer.class) > 0);

        var response = mvc.perform(get("/api/admin/database-backup").cookie(cookie))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString(".sql")))
                .andReturn().getResponse();
        String sql = response.getContentAsString();
        assertFalse(sql.contains("INSERT INTO \"PUBLIC\".\"BROWSER_SESSION\""));
        assertFalse(sql.contains(BrowserSessionService.hash("integration-test-token")));
        assertFalse(sql.contains(BrowserSessionService.hash(cookie.getValue())));
        assertFalse(sql.contains(" SALT "));
        try (var restored = DriverManager.getConnection("jdbc:h2:mem:backuprestore", "sa", "")) {
            RunScript.execute(restored, new StringReader(sql));
            try (var result = restored.createStatement().executeQuery("SELECT ID, VALUE_TEXT FROM BACKUP_TEST")) {
                assertTrue(result.next());
                assertEquals(42L, result.getLong(1));
                assertEquals("备份 'data'\nsecond line", result.getString(2));
            }
            try (var result = restored.createStatement().executeQuery("SELECT COUNT(*) FROM BROWSER_SESSION")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
