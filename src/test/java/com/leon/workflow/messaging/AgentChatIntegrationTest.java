package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:agent-chat-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class AgentChatIntegrationTest {
    @Autowired WebApplicationContext context; @Autowired AgentRegistry agents; @Autowired AgentChatService chat;
    @Autowired DeviceMessageStore store; @Autowired AgentReplyEngine engine; @Autowired JdbcTemplate jdbc;
    @Autowired BrowserSessionService sessions; @Autowired ApiTokenInterceptor auth; @Autowired ObjectMapper json;
    @MockitoBean AgentModelClient model;
    private static final String AUTH="Bearer integration-test-token", KEY="test-only-model-key-never-real";
    private MockMvc mvc(){return MockMvcBuilders.webAppContextSetup(context).build();}
    @BeforeEach void clean(){jdbc.update("DELETE FROM chat_agent_job");jdbc.update("DELETE FROM device_message");jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");jdbc.update("DELETE FROM chat_agent");}
    private AgentRegistry.Agent agent(String handle){return agents.save(null,new AgentRegistry.Edit(handle,handle,"openai","test-model","角色 "+handle,true,KEY,null));}
    private DeviceMessageStore.Send send(String room,String text,List<String> ids){return new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos",text,room,ids);}

    @Test void adminRequiresCookieAndCsrfAndNeverExposesKeys() throws Exception {
        var input=new AgentRegistry.Edit("写作助手","writer","openai","test-model","私有提示",true,KEY,null);
        String body=json.writeValueAsString(input);
        mvc().perform(get("/api/agent-admin").header("Authorization",AUTH)).andExpect(status().isForbidden());
        mvc().perform(post("/api/agent-admin").header("Authorization",AUTH).contentType("application/json").content(body)).andExpect(status().isForbidden());
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc().perform(post("/api/agent-admin").cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        String response=mvc().perform(post("/api/agent-admin").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasApiKey").value(true)).andExpect(jsonPath("$.apiKey").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(KEY));
        String publicResponse=mvc().perform(get("/api/agents").header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$[0].systemPrompt").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(publicResponse.contains(KEY));
        assertFalse(jdbc.queryForObject("SELECT encrypted_key FROM chat_agent",String.class).contains(KEY));
    }
    @Test void directConversationRepliesAndIsolatesContext() throws Exception {
        var a=agent("writer");var b=agent("coder");var ca=chat.direct(a.id());var cb=chat.direct(b.id());
        when(model.complete(any(),anyString(),anyList())).thenReturn("只给 writer 的回复");
        var input=send(ca.id(),"只给 writer 的问题",List.of());chat.send(input);chat.send(input);
        assertTrue(engine.runNext());assertFalse(engine.runNext());
        var page=store.page(ca.id(),null,null,100);assertEquals(2,page.messages().size());assertTrue(page.jobs().isEmpty());
        assertEquals("assistant",page.messages().getLast().role());assertEquals(a.id(),page.messages().getLast().agentId());
        assertTrue(store.page(cb.id(),null,null,100).messages().isEmpty());assertTrue(store.page(null,null,100).messages().isEmpty());
        verify(model,times(1)).complete(argThat(x->x.id().equals(a.id())),eq(KEY),anyList());
    }
    @Test void groupsOnlyInvokeMentionedMembersAndNeverRecurse() throws Exception {
        var a=agent("writer");var b=agent("coder");var group=chat.group("讨论组",List.of(a.id(),b.id()));
        chat.send(send(group.id(),"大家好",List.of()));assertFalse(engine.runNext());
        when(model.complete(any(),anyString(),anyList())).thenReturn("@coder 不应自动触发另一个 Agent");
        chat.send(send(group.id(),"@writer 帮我写一段话",List.of()));assertTrue(engine.runNext());assertFalse(engine.runNext());
        verify(model).complete(argThat(x->x.id().equals(a.id())),eq(KEY),anyList());
        var outside=agent("outside");assertThrows(Exception.class,()->chat.send(send(group.id(),"问题",List.of(outside.id()))));
        assertEquals(3,store.page(group.id(),null,null,100).messages().size());
    }
    @Test void explicitMentionUsesStableIdAndUnknownTextMentionsDoNotInvoke() throws Exception {
        var a=agent("writer");when(model.complete(any(),anyString(),anyList())).thenReturn("回复");
        chat.send(send("shared","name@example.com @unknown",List.of()));assertFalse(engine.runNext());
        chat.send(send("shared","请回答",List.of(a.id())));assertTrue(engine.runNext());assertFalse(engine.runNext());
        verify(model,times(1)).complete(any(),anyString(),anyList());
    }
    @Test void failuresAreSanitizedAndManualRetryDoesNotDuplicateUserMessage() throws Exception {
        var a=agent("writer");var c=chat.direct(a.id());
        when(model.complete(any(),anyString(),anyList())).thenThrow(new RuntimeException("provider echoed "+KEY));
        var sent=chat.send(send(c.id(),"测试失败",List.of()));assertTrue(engine.runNext());
        var failed=store.page(c.id(),null,null,100);assertEquals("FAILED",failed.jobs().getFirst().status());assertFalse(failed.jobs().getFirst().error().contains(KEY));assertFalse(engine.runNext());
        doReturn("重试成功").when(model).complete(any(),anyString(),anyList());store.retryJob(sent.id(),a.id());assertTrue(engine.runNext());
        assertEquals(2,store.page(c.id(),null,null,100).messages().size());assertTrue(store.page(c.id(),null,null,100).jobs().isEmpty());
    }
    @Test void rapidMessagesKeepPriorAnswerAndExcludeFutureAndOtherConversations() throws Exception {
        var a=agent("writer");var c=chat.direct(a.id());
        when(model.complete(any(),anyString(),anyList())).thenReturn("第一次回答");
        chat.send(send(c.id(),"第一个问题",List.of()));chat.send(send(c.id(),"第二个问题",List.of()));
        assertTrue(engine.runNext());
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(2);
            assertTrue(messages.stream().anyMatch(m->m.get("role").equals("assistant")&&m.get("content").equals("第一次回答")));
            assertTrue(messages.getLast().get("content").contains("第二个问题"));return "第二次回答";
        });
        assertTrue(engine.runNext());assertFalse(engine.runNext());
    }
    @Test void providerSwitchRequiresNewKeyAndDisabledAgentDoesNotRun() throws Exception {
        var a=agent("writer");
        assertThrows(Exception.class,()->agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),"deepseek","test-model","",true,"",a.version())));
        agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),a.provider(),a.model(),"",false,"",a.version()));
        assertThrows(Exception.class,()->chat.direct(a.id()));assertEquals(KEY,agents.apiKey(a.id()));
        assertThrows(Exception.class,()->agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),a.provider(),a.model(),"",true,"",a.version())));
    }
    @Test void interruptedJobsFailWithoutAutomaticPaidRetry() {
        var a=agent("writer");var c=chat.direct(a.id());chat.send(send(c.id(),"中断",List.of()));assertNotNull(store.claim());store.recoverInterrupted();
        assertNull(store.claim());assertEquals("FAILED",store.page(c.id(),null,null,100).jobs().getFirst().status());
    }
}
