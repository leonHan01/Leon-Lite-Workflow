package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ai.OpenAiExecutor;
import com.leon.workflow.automation.WorkflowModels;
import com.leon.workflow.automation.WorkflowCredentials;
import com.leon.workflow.service.BrowserSessionService;
import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:web-search-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class WebSearchIntegrationTest {
    static final String KEY="web-test-secret",AUTH="Bearer integration-test-token";
    @Autowired WebApplicationContext context;@Autowired AgentRegistry agents;@Autowired AgentChatService chat;
    @Autowired DeviceMessageStore store;@Autowired AgentReplyEngine engine;@Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;@Autowired WorkflowModels usage;@Autowired WorkflowCredentials credentials;
    @Autowired BrowserSessionService sessions;@Autowired ApiTokenInterceptor auth;
    @MockitoBean AgentModelClient model;
    @BeforeEach void clean(){
        for(String table:List.of("chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_agent","chat_user_model_usage","automation_model_usage"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");
    }
    AgentRegistry.Agent agent(){return agents.save(null,new AgentRegistry.Edit("Sol","sol","openai","test-model","私有提示词",true,KEY,null,null,null,"联网助手",false,AgentRegistry.DEFAULT_IMAGE_MODEL,true));}
    @Test void searchSourcesSurviveReplyPersistenceAndAuthenticatedHistoryWithoutClientChanges() throws Exception {
        var a=agent();String room=chat.direct(a.id()).id();
        var http=mock(HttpClient.class);var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(json.writeValueAsBytes(OpenAiWebSearchClientTest.payload()));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client=new OfficialAgentModelClient(json,http,usage,mock(OpenAiExecutor.class));
        when(model.reply(any(),anyString(),anyList())).thenAnswer(call->client.reply(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        var question=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos","联网查最新版本",room,List.of()));
        assertTrue(engine.runNext());
        var saved=store.page(room,null,null,100).messages().getLast();
        assertEquals("assistant",saved.role());assertEquals(question.id(),saved.replyTo());assertTrue(saved.images().isEmpty());
        assertTrue(saved.text().contains("[来源1](https://example.test/release)"));assertTrue(saved.text().contains("[1. 官方发布](https://example.test/release)"));
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/device-messages").param("conversationId",room)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/device-messages").header("Authorization",AUTH).param("conversationId",room))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[1].text").value(saved.text()));
        assertEquals(25,jdbc.queryForObject("SELECT total_tokens FROM automation_model_usage WHERE model='test-model'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM automation_model_usage WHERE model LIKE 'gpt-image-%'",Integer.class));
        verify(model,never()).complete(any(),anyString(),anyList());verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void defaultsOffPreservesOmittedFieldsAndKeepsSearchIndependentFromDrawing(){
        var a=agent();assertTrue(agents.runtime(a.id()).agent.webSearchEnabled());assertFalse(a.imageGenerationEnabled());
        var saved=agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),a.provider(),a.model(),a.systemPrompt(),true,null,a.version()));
        assertTrue(saved.webSearchEnabled());assertFalse(saved.imageGenerationEnabled());
        agents.initialize();assertTrue(agents.get(a.id()).webSearchEnabled());
        var plain=agents.save(null,new AgentRegistry.Edit("Text","text","openai","test-model","",true,KEY,null));assertFalse(plain.webSearchEnabled());
        var both=agents.save(saved.id(),new AgentRegistry.Edit(saved.name(),saved.handle(),saved.provider(),saved.model(),saved.systemPrompt(),true,null,saved.version(),null,null,null,true,AgentRegistry.DEFAULT_IMAGE_MODEL,true));
        assertTrue(both.webSearchEnabled());assertTrue(both.imageGenerationEnabled());
        var off=agents.save(both.id(),new AgentRegistry.Edit(both.name(),both.handle(),both.provider(),both.model(),both.systemPrompt(),true,null,both.version(),null,null,null,null,null,false));
        assertFalse(off.webSearchEnabled());assertTrue(off.imageGenerationEnabled());
        assertThrows(Exception.class,()->agents.save(null,new AgentRegistry.Edit("Deep","deep","deepseek","deepseek-chat","",true,KEY,null,null,null,"",false,AgentRegistry.DEFAULT_IMAGE_MODEL,true)));
    }
    @Test void sharedModelResolutionRetainsSearchCapability(){
        var credential=credentials.create(Map.of("kind","openai","name","search-test","value",Map.of("apiKey",KEY)));
        var profile=usage.save(null,Map.of("name","search-model","provider","openai","model","test-search-model","credentialId",credential.get("id")));
        var a=agents.save(null,new AgentRegistry.Edit("Profile","profile",null,null,"角色",true,null,null,null,(String)profile.get("id"),"",false,AgentRegistry.DEFAULT_IMAGE_MODEL,true));
        var runtime=agents.runtime(a.id());assertTrue(runtime.agent.webSearchEnabled());assertEquals("test-search-model",runtime.agent.model());assertEquals(KEY,runtime.key);
    }
    @Test void adminApiRoundTripsSearchSettingWithCookieAndOriginWhileBearerCannotChangeIt() throws Exception {
        var a=agent();var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        var input=Map.of("name",a.name(),"handle",a.handle(),"systemPrompt",a.systemPrompt(),"enabled",true,"version",a.version(),"webSearchEnabled",false);
        mvc.perform(put("/api/agent-admin/"+a.id()).header("Authorization",AUTH).contentType("application/json").content(json.writeValueAsBytes(input))).andExpect(status().isForbidden());
        mvc.perform(put("/api/agent-admin/"+a.id()).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webSearchEnabled").value(false)).andExpect(jsonPath("$.apiKey").doesNotExist());
        assertFalse(agents.get(a.id()).webSearchEnabled());
        mvc.perform(get("/api/agent-admin").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.agents[0].webSearchEnabled").value(false));
    }
}
