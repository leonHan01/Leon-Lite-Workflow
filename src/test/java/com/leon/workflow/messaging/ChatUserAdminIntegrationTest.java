package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    "spring.datasource.url=jdbc:h2:mem:user-admin;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false", "leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true", "leon.auth.admin-origin=https://example.test"})
class ChatUserAdminIntegrationTest {
    @DynamicPropertySource static void token(DynamicPropertyRegistry registry) {
        registry.add("leon.auth.token-sha256", () -> BrowserSessionService.hash("integration-test-token"));
    }
    @Autowired WebApplicationContext context; @Autowired ChatUserRegistry users; @Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents; @Autowired AgentContextManager contexts; @Autowired JdbcTemplate jdbc;
    @Autowired BrowserSessionService sessions; @Autowired ApiTokenInterceptor auth; @Autowired ObjectMapper json;
    @Autowired AgentReplyEngine engine;
    @MockitoBean AgentModelClient model;

    @Test void adminCanInspectAnalyzeAndDeleteWithIsolationAndConcurrencyChecks() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        var alice=users.getOrCreate("AdminAlice"); var bob=users.getOrCreate("AdminBob");
        var agent=agents.save(null,new AgentRegistry.Edit("Analysis Agent","analysisagent","openai","test-model","",true,"secret-key",null));
        String room="thread:"+UUID.randomUUID();
        store.createNamedDirect(agent,room,"Test room");
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",alice.id(),room);
        jdbc.update("INSERT INTO chat_conversation_member(conversation_id,user_id,joined_at) VALUES(?,?,?)",room,bob.id(),1L);
        var a=new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Alice","android","喜欢写代码",room,List.of());
        var b=new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Bob","android","Bob 的私密内容",room,List.of());
        store.submit(a,List.of(),alice.id()); store.submit(b,List.of(),bob.id());
        String base="/api/agent-admin/users", path=base+"/"+alice.id();
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get(base).header("Authorization","Bearer integration-test-token")).andExpect(status().isForbidden());
        mvc.perform(get(base).header("Authorization","Bearer "+users.login("AdminAlice").sessionToken())).andExpect(status().isUnauthorized());
        mvc.perform(get(base).cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(path+"/conversations").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(room));
        mvc.perform(get(path+"/messages").cookie(cookie).param("conversationId",room)).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2));
        var analysis=json.writeValueAsString(Map.of("agentId",agent.id()));
        mvc.perform(post(path+"/analysis").cookie(cookie).contentType("application/json").content(analysis)).andExpect(status().isForbidden());
        when(model.complete(any(),anyString(),anyList())).thenReturn("偏好写代码");
        mvc.perform(post(path+"/analysis").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(analysis))
            .andExpect(status().isOk()).andExpect(jsonPath("$.messageCount").value(1));
        verify(model).complete(any(),anyString(),argThat(input -> input.toString().contains("喜欢写代码") && !input.toString().contains("Bob 的私密内容")));
        var preview=contexts.clearPreview(room);
        var body=new HashMap<String,Object>(Map.of("conversationId",room,"requestId",UUID.randomUUID().toString(),"expectedLatestMessageId",preview.latestMessageId(),"expectedHistoryRevision",preview.historyRevision(),"confirm",true));
        store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Alice","android","新消息",room,List.of()),List.of(),alice.id());
        mvc.perform(post(path+"/delete").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
        assertEquals(3,store.page(room,null,null,100).messages().size());
        body.put("expectedLatestMessageId",contexts.clearPreview(room).latestMessageId());
        mvc.perform(post(path+"/delete").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.clearedMessages").value(3)).andExpect(jsonPath("$.homeRetained").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation WHERE id=?",Integer.class,room));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_member WHERE conversation_id=?",Integer.class,room));
        assertThrows(Exception.class,()->store.submit(a,List.of(),alice.id()));
    }

    @Test void personalHomeIsRetainedAndActiveWorkBlocksDeletion() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        var leon=users.getOrCreate("Leon"); String room="shared", path="/api/agent-admin/users/"+leon.id();
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        var sent=store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Leon","android","首页内容",room,List.of()),List.of(),leon.id());
        var preview=contexts.clearPreview(room);
        var body=Map.of("conversationId",room,"requestId",UUID.randomUUID().toString(),"expectedLatestMessageId",preview.latestMessageId(),"expectedHistoryRevision",preview.historyRevision(),"confirm",true);
        String agentId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_agent_job(message_id,agent_id,conversation_id,agent_name,status) VALUES(?,?,?,?,?)",sent.id(),agentId,room,"busy","PENDING");
        mvc.perform(post(path+"/delete").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
        jdbc.update("DELETE FROM chat_agent_job WHERE message_id=? AND agent_id=?",sent.id(),agentId);
        mvc.perform(post(path+"/delete").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.homeRetained").value(true));
        assertTrue(store.page(room,null,null,100).messages().isEmpty());
        assertEquals(room,store.conversation(room).id());
    }

    @Test void backgroundsRequireAdminCookieAndProtectAgainstOverwrites() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).build(); var user=users.getOrCreate("BackgroundAdminTest");
        String path="/api/agent-admin/users/"+user.id()+"/background";
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        String body=json.writeValueAsString(Map.of("background","背景：偏好中文","version",0));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer integration-test-token")).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+users.login("BackgroundAdminTest").sessionToken())).andExpect(status().isUnauthorized());
        mvc.perform(put(path).cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.background").value("背景：偏好中文"));
        mvc.perform(put(path).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(put(path).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content("{\"background\":\"missing version\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(path).cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(Map.of("background","a".repeat(1001),"version",1)))).andExpect(status().isBadRequest());
        assertEquals("背景：偏好中文",users.background(user.id()).background());
    }

    @Test void replyUsesCurrentSpeakersBackgroundAndRefreshesItForRetries() throws Exception {
        var alice=users.getOrCreate("BackgroundSpeakerAlice"); var bob=users.getOrCreate("BackgroundSpeakerBob");
        users.updateBackground(alice.id(),"Alice 喜欢骑行",0L); users.updateBackground(bob.id(),"Bob 喜欢绘画",0L);
        var agent=agents.save(null,new AgentRegistry.Edit("Profile Agent","profileagent","openai","test-model","",true,"test-key",null));
        var room=store.createGroup("背景群",List.of(agent.id()));
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",alice.id(),room.id());
        jdbc.update("INSERT INTO chat_conversation_member(conversation_id,user_id,joined_at) VALUES(?,?,?)",room.id(),bob.id(),1L);
        var inputs=new ArrayList<String>();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{inputs.add(call.getArgument(2).toString());return "回答";});
        for(var user:List.of(alice,bob)) {
            store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),user.username(),"android","问题",room.id(),List.of(agent.id())),List.of(agent),user.id());
            assertTrue(engine.runNext());
        }
        assertTrue(inputs.get(0).contains("Alice 喜欢骑行")); assertFalse(inputs.get(0).contains("Bob 喜欢绘画"));
        assertTrue(inputs.get(1).contains("Bob 喜欢绘画")); assertFalse(inputs.get(1).contains("Alice 喜欢骑行"));
        reset(model); inputs.clear();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{inputs.add(call.getArgument(2).toString());if(inputs.size()==1)throw new AgentModelClient.Failure("temporary");return "回答";});
        var sent=store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Alice","android","重试问题",room.id(),List.of(agent.id())),List.of(agent),alice.id());
        assertTrue(engine.runNext()); assertTrue(inputs.getFirst().contains("Alice 喜欢骑行"));
        users.updateBackground(alice.id(),"Alice 喜欢游泳",1L);
        store.retryJob(sent.id(),agent.id()); assertTrue(engine.runNext());
        assertTrue(inputs.get(1).contains("Alice 喜欢游泳")); assertFalse(inputs.get(1).contains("Alice 喜欢骑行"));
        String snapshot=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=? AND agent_id=?",String.class,sent.id(),agent.id());
        assertFalse(snapshot.contains("Alice 喜欢骑行")); assertFalse(snapshot.contains("Alice 喜欢游泳"));
        users.updateBackground(alice.id(),"",2L);
        var next=store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Alice","android","清空后",room.id(),List.of(agent.id())),List.of(agent),alice.id());
        assertTrue(engine.runNext()); assertFalse(inputs.getLast().contains("Alice 喜欢游泳"));
        assertNotNull(next);
    }

    @Test void backgroundCountsAgainstTheModelInputBudget() {
        var user=users.getOrCreate("BackgroundBudgetUser");
        users.updateBackground(user.id(),"背".repeat(1000),0L);
        var agent=agents.save(null,new AgentRegistry.Edit("Budget Agent","budgetagent","openai","luna-test","",true,"test-key",null));
        var room=store.createGroup("背景长度测试",List.of(agent.id()));
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",user.id(),room.id());
        var sent=store.submit(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),user.username(),"android","问".repeat(1100),room.id(),List.of(agent.id())),List.of(agent),user.id());
        assertTrue(engine.runNext());
        var job=jdbc.queryForMap("SELECT status,error FROM chat_agent_job WHERE message_id=? AND agent_id=?",sent.id(),agent.id());
        assertEquals("FAILED",job.get("status")); assertTrue(job.get("error").toString().contains("背景"));
        verifyNoInteractions(model);
    }
}
