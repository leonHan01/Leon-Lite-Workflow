package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:authtest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test:8080",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ApiAuthenticationIntegrationTest {
    @Autowired WebApplicationContext context;
    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test void protectsReadsWritesSecretsAndAlfred() throws Exception {
        for (String path : new String[]{"/api/web-collections", "/api/app-settings/DEEPSEEK_API_KEY", "/api/json-record/search"}) {
            mvc().perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
            mvc().perform(get(path).header("Authorization", "Bearer wrong")) .andExpect(status().isUnauthorized());
        }
        mvc().perform(post("/api/notes").contentType("application/json").content("{\"content\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        mvc().perform(post("/alfred/query").contentType("application/json").content("{\"param\":\"1+2\"}"))
                .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/web-collections").header("Authorization", "Bearer integration-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc().perform(post("/alfred/query").header("Authorization", "Bearer integration-test-token")
                .contentType("application/json").content("{\"param\":\"1+2\"}"))
                .andExpect(status().isOk());
    }

    @Test void preflightAndUnauthorizedCorsWork() throws Exception {
        String origin = "chrome-extension://abcdefghijklmnop";
        mvc().perform(options("/api/web-collections").header("Origin", origin)
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        mvc().perform(get("/api/web-collections").header("Origin", origin))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test void requiredAuthenticationFailsClosedWithoutAValidHash() {
        assertThrows(IllegalStateException.class, () -> new ApiTokenInterceptor("", true, "", null));
        assertThrows(IllegalStateException.class, () -> new ApiTokenInterceptor("invalid", false, "", null));
    }

    @Test void browserBasicAuthenticationRequiresSameOriginAndExplicitHeaderForWrites() throws Exception {
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("leon:integration-test-token".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc().perform(get("/api/simple-kv/list").header("Authorization", basic)).andExpect(status().isOk());
        mvc().perform(post("/api/simple-kv/delete").header("Authorization", basic)
                .contentType("application/json").content("{\"id\":1}"))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/simple-kv/delete").header("Authorization", basic)
                .header("Origin", "https://attacker.test").header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json").content("{\"id\":1}"))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/simple-kv/delete").header("Authorization", basic)
                .header("Origin", "https://example.test:8080").header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json").content("{\"id\":1}"))
                .andExpect(status().isOk());
    }
}
