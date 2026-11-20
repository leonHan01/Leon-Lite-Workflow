package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:databasemanagement;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test"})
class DatabaseManagementIntegrationTest {
    @DynamicPropertySource static void token(DynamicPropertyRegistry registry) {
        registry.add("leon.auth.token-sha256", () -> BrowserSessionService.hash("integration-test-token"));
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test void tablesAndQueriesRequireAdminAndCannotWrite() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.execute("CREATE TABLE DB_MANAGER_TEST (ID INT, NAME VARCHAR(40))");
        jdbc.update("INSERT INTO DB_MANAGER_TEST VALUES (1, 'hello'), (2, 'world')");
        mvc.perform(get("/api/admin/database-backup?tables")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/database-backup?tables").header("Authorization", "Bearer integration-test-token"))
                .andExpect(status().isForbidden());
        String header = mvc.perform(post("/auth/login").header("Origin", "https://example.test")
                .header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                .content("{\"token\":\"integration-test-token\",\"remember\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie(BrowserSessionService.COOKIE_NAME, header.split(";", 2)[0].split("=", 2)[1]);
        String tables = mvc.perform(get("/api/admin/database-backup?tables").cookie(cookie))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(tables.contains("\"name\":\"DB_MANAGER_TEST\",\"rows\":2,\"bytes\":12"), tables);
        mvc.perform(post("/api/admin/database-backup").cookie(cookie).contentType("application/json")
                .content("{\"sql\":\"SELECT * FROM DB_MANAGER_TEST\"}"))
                .andExpect(status().isForbidden());
        var query = post("/api/admin/database-backup").cookie(cookie).header("Origin", "https://example.test")
                .header("X-Requested-With", "XMLHttpRequest").contentType("application/json");
        String result = mvc.perform(query.content("{\"sql\":\"SELECT * FROM DB_MANAGER_TEST ORDER BY ID\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(result.contains("hello"), result);
        for (String sql : new String[]{"DELETE FROM DB_MANAGER_TEST", "SELECT * FROM DB_MANAGER_TEST; DELETE FROM DB_MANAGER_TEST",
                "SELECT * FROM FINAL TABLE (DELETE FROM DB_MANAGER_TEST)", "SELECT CSVWRITE('/tmp/should-not-exist.csv', 'SELECT 1')"}) {
            mvc.perform(post("/api/admin/database-backup").cookie(cookie).header("Origin", "https://example.test")
                    .header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                    .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("sql", sql))))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM DB_MANAGER_TEST", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME LIKE 'DB_VIEW_%'", Integer.class));
    }
}
