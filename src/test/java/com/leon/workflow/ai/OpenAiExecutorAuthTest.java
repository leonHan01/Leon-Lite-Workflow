package com.leon.workflow.ai;

import com.example.helloservice.HelloServiceApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={"spring.datasource.url=jdbc:h2:mem:executor-auth;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true","leon.auth.admin-origin=https://example.test","leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69",
        "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false"})
class OpenAiExecutorAuthTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc; Cookie cookie;
    @BeforeEach void setup() throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        String header=mvc.perform(post("/auth/login").header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"integration-test-token\",\"remember\":false}")).andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        cookie=new Cookie("__Host-leon_session",header.split(";",2)[0].split("=",2)[1]);
    }
    @Test void executorTokensCannotManageApplicationAndAdminTokensCannotClaimAiKeys() throws Exception {
        mvc.perform(get("/api/ai-executor-admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai-executor-admin").header("Authorization","Bearer integration-test-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai-executor-admin/workers").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mac\"}")).andExpect(status().isForbidden());
        String raw=mvc.perform(post("/api/ai-executor-admin/workers").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mac\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var worker=read(raw);String token=text(worker.get("token"));
        String backup=mvc.perform(get("/api/admin/database-backup").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(backup.contains("INSERT INTO \"PUBLIC\".\"AI_EXECUTOR_"));
        for(String header:List.of("Bearer wrong","Bearer integration-test-token")) mvc.perform(post("/api/ai-executor/claim").header("Authorization",header).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai-executor/claim").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ai-executor/heartbeat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}")) .andExpect(status().isOk());
        mvc.perform(post("/api/ai-executor/claim").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isOk());
        for(String path:List.of("/api/workflows","/api/agent-admin","/api/ai-executor-admin")) mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai-executor-admin").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.workers[0].token").doesNotExist()).andExpect(jsonPath("$.workers[0].tokenHash").doesNotExist());
        mvc.perform(delete("/api/ai-executor-admin/workers/"+worker.get("id")).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest")) .andExpect(status().isOk());
        mvc.perform(post("/api/ai-executor/claim").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized());
    }
}
