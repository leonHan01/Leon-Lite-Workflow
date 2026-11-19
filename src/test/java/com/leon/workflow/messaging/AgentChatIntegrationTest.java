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
    @Autowired AgentContextManager contexts; @Autowired ChatImageService images;
    @Autowired BrowserSessionService sessions; @Autowired ApiTokenInterceptor auth; @Autowired ObjectMapper json;
    @MockitoBean AgentModelClient model;
    private static final String AUTH="Bearer integration-test-token", KEY="test-only-model-key-never-real";
    private MockMvc mvc(){return MockMvcBuilders.webAppContextSetup(context).build();}
    @BeforeEach void clean(){jdbc.update("DELETE FROM chat_cleared_message");jdbc.update("DELETE FROM chat_cleared_image");jdbc.update("DELETE FROM chat_context_archive");jdbc.update("DELETE FROM chat_context_operation");jdbc.update("DELETE FROM chat_context_snapshot");jdbc.update("DELETE FROM chat_context_summary");jdbc.update("DELETE FROM chat_context_policy");jdbc.update("DELETE FROM chat_agent_job");jdbc.update("DELETE FROM device_message");jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");jdbc.update("DELETE FROM chat_agent");}
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
    @Test void clientUsageRequiresAuthAndIncludesUniqueHistoricalModelsWithoutCredentials() throws Exception {
        var models=context.getBean(com.leon.workflow.automation.WorkflowModels.class);
        jdbc.update("DELETE FROM chat_user_model_usage");
        jdbc.update("DELETE FROM automation_model_usage");
        String leon=context.getBean(ChatUserRegistry.class).getOrCreate("Leon").id();
        try {
            mvc().perform(get("/api/agents/usage")).andExpect(status().isUnauthorized());
            mvc().perform(get("/api/agents/usage").header("Authorization",AUTH))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
            models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",3_000_000_000L,"completion_tokens",20),leon);
            models.recordUsage("deepseek","deepseek-flash",null,leon);
            // Multiple profiles for the same model must not multiply usage.
            models.save(null,Map.of("name","usage alias","provider","deepseek","model","deepseek-flash"));
            models.recordUsage("openai","retired-model",Map.of("prompt_tokens",5,"completion_tokens",2),leon);
            String result=mvc().perform(get("/api/agents/usage").header("Authorization",AUTH))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].inputTokens").value(3_000_000_000L))
                .andExpect(jsonPath("$[0].totalTokens").value(3_000_000_020L))
                .andExpect(jsonPath("$[0].reportedCalls").value(1)).andExpect(jsonPath("$[0].unreportedCalls").value(1))
                .andExpect(jsonPath("$[1].model").value("retired-model"))
                .andExpect(jsonPath("$[1].totalTokens").value(7))
                .andExpect(jsonPath("$[0].lastRecordedAt").isString()).andReturn().getResponse().getContentAsString();
            var rows=json.readTree(result);
            assertEquals(Set.of("provider","model","inputTokens","outputTokens","totalTokens","reportedCalls","unreportedCalls","firstRecordedAt","lastRecordedAt"),
                rows.get(0).propertyNames());
        } finally {
            jdbc.update("DELETE FROM chat_user_model_usage");
        jdbc.update("DELETE FROM automation_model_usage");
            jdbc.update("DELETE FROM automation_model WHERE name='usage alias'");
        }
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
    @Test void aiCompressionKeepsWholeTurnsAndPublishesPhaseAndPersistsMemory() throws Exception {
        var a=agent("memory");var room=chat.direct(a.id());var compression=new ArrayList<List<Map<String,String>>>();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            if(input.getFirst().get("content").contains("你是对话记忆压缩器")) {
                assertEquals("COMPRESSING",store.page(room.id(),null,null,100).jobs().getFirst().phase());
                compression.add(input);return "已确认：用户希望记住暗号 BLUE。";
            }
            return "answer-"+input.getLast().get("content");
        });
        for(int i=1;i<=15;i++) { chat.send(send(room.id(),"question-"+i+(i==1?" BLUE":""),List.of()));assertTrue(engine.runNext()); }
        assertEquals(1,compression.size());
        String compressed=compression.getFirst().getLast().get("content");
        for(int i=1;i<=4;i++) { assertTrue(compressed.contains("question-"+i));assertTrue(compressed.contains("answer-[用户 · Mac]：question-"+i)); }
        assertFalse(compressed.contains("question-5"));
        var info=contexts.info(room.id());assertEquals(10,info.recentRounds());
        assertTrue(info.agents().getFirst().summary().contains("BLUE"));assertEquals(10,info.agents().getFirst().retainedRounds());
        assertTrue(info.agents().getFirst().estimatedInputTokens()>0);
        assertEquals(30,store.page(room.id(),null,null,100).messages().size());
        var restarted=new AgentContextManager(store,jdbc,agents,model,json);
        assertEquals(info.agents().getFirst().summary(),restarted.info(room.id()).agents().getFirst().summary());
        String snapshot=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot ORDER BY message_id DESC LIMIT 1",String.class);
        assertTrue(snapshot.contains("BLUE"));assertTrue(snapshot.contains("question-5"));assertTrue(snapshot.contains("question-15"));assertFalse(snapshot.contains("question-1 BLUE"));
    }
    @Test void compressionFailureRetainsChatAndRetryReusesCompletedSnapshot() throws Exception {
        var a=agent("memory");var room=chat.direct(a.id());contexts.configure(room.id(),1,false);
        when(model.complete(any(),anyString(),anyList())).thenReturn("answer");
        for(int i=0;i<5;i++) { chat.send(send(room.id(),"old-"+i,List.of()));engine.runNext(); }
        var sent=chat.send(send(room.id(),"compress-me",List.of()));
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            if(input.getFirst().get("content").contains("你是对话记忆压缩器")) throw new AgentModelClient.Failure("模型超时");
            return "answer";
        });
        engine.runNext();var job=store.page(room.id(),null,null,100).jobs().getFirst();
        assertEquals("FAILED",job.status());assertTrue(job.error().contains("上下文压缩未完成"));
        assertEquals(11,store.page(room.id(),null,null,100).messages().size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary",Integer.class));
        var compressCalls=new java.util.concurrent.atomic.AtomicInteger();var replyCalls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            if(input.getFirst().get("content").contains("你是对话记忆压缩器")) { compressCalls.incrementAndGet();return "旧历史摘要"; }
            if(replyCalls.getAndIncrement()==0) throw new AgentModelClient.Failure("回复失败");return "成功";
        }).when(model).complete(any(),anyString(),anyList());
        store.retryJob(sent.id(),a.id());engine.runNext();assertEquals(1,compressCalls.get());
        String original=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=?",String.class,sent.id());
        // A future question and its answer must not enter this failed reply's retry.
        chat.send(send(room.id(),"future-question",List.of()));engine.runNext();
        store.retryJob(sent.id(),a.id());engine.runNext();assertEquals(1,compressCalls.get());
        assertEquals(original,jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=?",String.class,sent.id()));
        assertFalse(original.contains("future-question"));
    }
    @Test void summaryNeverLeaksFromFutureIntoEarlierFailedQuestion() throws Exception {
        var a=agent("memory");var room=chat.direct(a.id());contexts.configure(room.id(),1,false);
        when(model.complete(any(),anyString(),anyList())).thenReturn("answer");
        var original=chat.send(send(room.id(),"earlier",List.of()));var work=store.claim();store.fail(work,"failed before preparing");
        for(int i=0;i<8;i++) { chat.send(send(room.id(),"later-"+i,List.of()));engine.runNext(); }
        assertFalse(contexts.info(room.id()).agents().getFirst().summary().isEmpty());
        var inputs=new ArrayList<List<Map<String,String>>>();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{inputs.add(call.getArgument(2));return "old answer";});
        store.retryJob(original.id(),a.id());engine.runNext();
        assertEquals(1,inputs.size());String payload=json.writeValueAsString(inputs.getFirst());assertFalse(payload.contains("later-"));assertFalse(payload.contains("历史对话摘要"));
    }
    @Test void contextSettingsRequireAuthAndResetPreservesChatAndIsolatesAgents() throws Exception {
        var a=agent("writer");var b=agent("coder");var room=chat.direct(a.id());
        mvc().perform(get("/api/conversations/context").param("conversationId",room.id())).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/conversations/context").header("Authorization",AUTH).param("conversationId",room.id())).andExpect(status().isOk()).andExpect(jsonPath("$.recentRounds").value(10)).andExpect(jsonPath("$.agents.length()").value(1));
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        String settings=json.writeValueAsString(Map.of("conversationId",room.id(),"recentRounds",3));
        mvc().perform(post("/api/conversations/context/settings").cookie(cookie).contentType("application/json").content(settings)).andExpect(status().isForbidden());
        mvc().perform(post("/api/conversations/context/settings").header("Authorization",AUTH).contentType("application/json").content(settings)).andExpect(status().isOk()).andExpect(jsonPath("$.recentRounds").value(3));
        when(model.complete(any(),anyString(),anyList())).thenReturn("answer");
        var sent=chat.send(send(room.id(),"old-topic",List.of()));
        assertThrows(Exception.class,()->contexts.configure(room.id(),3,true));engine.runNext();
        contexts.configure(room.id(),3,true);assertEquals(2,store.page(room.id(),null,null,100).messages().size());
        chat.send(send(room.id(),"new-topic",List.of()));engine.runNext();
        String snapshot=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot ORDER BY message_id DESC LIMIT 1",String.class);
        assertFalse(snapshot.contains("old-topic"));assertTrue(snapshot.contains("new-topic"));
        assertThrows(Exception.class,()->store.retryJob(sent.id(),a.id()));
        assertEquals(10,contexts.info(chat.direct(b.id()).id()).recentRounds());
    }
    @Test void groupContextIncludesProactiveMessagesAndLabelsOtherAgents() throws Exception {
        var a=agent("writer");var b=agent("coder");var room=chat.group("讨论",List.of(a.id(),b.id()));
        chat.publishAgentMessage(b.id(),room.id(),"其他 Agent 主动通知",UUID.randomUUID().toString());
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            assertTrue(input.stream().anyMatch(m->m.get("role").equals("user")&&m.get("content").contains("[其他 Agent · coder]：其他 Agent 主动通知")));
            return "answer";
        });
        chat.send(send(room.id(),"请分析通知",List.of(a.id())));engine.runNext();
        verify(model).complete(any(),anyString(),anyList());
    }
    @Test void oversizedCurrentMessageIsNeverSilentlyCut() throws Exception {
        var a=agent("writer");var room=chat.direct(a.id());
        chat.send(send(room.id(),"中".repeat(8000),List.of()));engine.runNext();
        verifyNoInteractions(model);assertTrue(store.page(room.id(),null,null,100).jobs().getFirst().error().contains("过长"));
        assertEquals(8000,store.page(room.id(),null,null,100).messages().getFirst().text().length());
    }
    @Test void tokenPressureCompressesBeforeRoundThresholdAndNeverSplitsMessages() throws Exception {
        var a=agent("writer");var room=chat.direct(a.id());
        var compressionCalls=new java.util.concurrent.atomic.AtomicInteger();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            assertTrue(AgentContextManager.tokens(input)<=5500);
            if(input.getFirst().get("content").contains("你是对话记忆压缩器")) {
                compressionCalls.incrementAndGet();
                String text=input.getLast().get("content");
                assertEquals(text.split("BEGIN-",-1).length,text.split("-END",-1).length);
                return "用户提供了长文本，记住已确认的决定。";
            }
            return "answer";
        });
        for(int i=0;i<8;i++) { chat.send(send(room.id(),"BEGIN-"+"中".repeat(900)+"-END",List.of()));engine.runNext(); }
        assertTrue(compressionCalls.get()>0);assertTrue(store.page(room.id(),null,null,100).jobs().isEmpty());
    }
    @Test void invalidCompressionDoesNotReplacePreviousMemoryOrRemoveHistory() throws Exception {
        var a=agent("writer");var room=chat.direct(a.id());contexts.configure(room.id(),1,false);
        when(model.complete(any(),anyString(),anyList())).thenReturn("answer");
        for(int i=0;i<5;i++) { chat.send(send(room.id(),"old-"+i,List.of()));engine.runNext(); }
        when(model.complete(any(),anyString(),anyList())).thenReturn("中".repeat(2000));
        chat.send(send(room.id(),"current",List.of()));engine.runNext();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary",Integer.class));
        assertTrue(store.page(room.id(),null,null,100).jobs().getFirst().error().contains("AI 摘要为空或过长"));
        assertEquals(11,store.page(room.id(),null,null,100).messages().size());
    }

    @Test void manualCompressionRunsBelowAutomaticThresholdAndNextReplyUsesMemory() throws Exception {
        var a=agent("writer");var room=chat.direct(a.id());
        when(model.complete(any(),anyString(),anyList())).thenReturn("answer");
        for(int i=0;i<3;i++) { chat.send(send(room.id(),"old-question-"+i,List.of()));engine.runNext(); }
        var inputs=new ArrayList<List<Map<String,String>>>();
        doAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);inputs.add(input);
            if(input.getFirst().get("content").contains("你是对话记忆压缩器")) {
                assertEquals("RUNNING",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
                assertEquals("COMPRESSING",contexts.info(room.id()).agents().getFirst().phase());
                assertTrue(input.getLast().get("content").contains("old-question-0"));
                assertFalse(input.getLast().get("content").contains("old-question-2"));
                assertFalse(input.getLast().get("content").contains("future-question"));
                return "手动摘要：记住早期约定 BLUE";
            }
            assertTrue(input.stream().anyMatch(m->m.get("content").contains("手动摘要")));
            assertFalse(input.stream().anyMatch(m->m.get("content").contains("old-question-0")));
            assertTrue(input.getLast().get("content").contains("future-question"));return "next answer";
        }).when(model).complete(any(),anyString(),anyList());
        String id=UUID.randomUUID().toString();
        var op=contexts.enqueueCompression(room.id(),a.id(),id);assertEquals("PENDING",op.status());
        assertEquals(op,contexts.enqueueCompression(room.id(),a.id(),id));assertTrue(inputs.isEmpty());
        // New questions wait behind a queued manual operation without entering its frozen history.
        chat.send(send(room.id(),"future-question",List.of()));assertNull(store.claim());
        assertTrue(engine.runNext());assertEquals(1,inputs.size());
        assertEquals(7,store.page(room.id(),null,null,100).messages().size());
        assertEquals("SUCCEEDED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
        assertEquals(10,contexts.info(room.id()).recentRounds());
        assertTrue(engine.runNext());assertEquals(2,inputs.size());assertEquals(8,store.page(room.id(),null,null,100).messages().size());
        assertFalse(engine.runNext());
    }
    @Test void compactApiRequiresAuthOriginAndRejectsConcurrentOperations() throws Exception {
        var a=agent("writer");var b=agent("outsider");var room=chat.group("test",List.of(a.id()));
        String id=UUID.randomUUID().toString();String body=json.writeValueAsString(Map.of("conversationId",room.id(),"agentId",a.id(),"requestId",id));
        mvc().perform(post("/api/conversations/context/compact").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc().perform(post("/api/conversations/context/compact").cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc().perform(post("/api/conversations/context/compact").header("Authorization",AUTH).contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc().perform(post("/api/conversations/context/compact").header("Authorization",AUTH).contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        assertThrows(Exception.class,()->contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString()));
        assertThrows(Exception.class,()->contexts.enqueueCompression(room.id(),b.id(),UUID.randomUUID().toString()));
        assertThrows(Exception.class,()->contexts.configure(room.id(),10,true));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_operation",Integer.class));
        var claimed=contexts.claimCompression();assertNotNull(claimed);assertNull(contexts.claimCompression());
        contexts.recoverCompressions();assertNull(contexts.claimCompression());
        assertEquals("FAILED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
        verifyNoInteractions(model);
    }
    @Test void manualCompressionResumesCompletedBatchesAfterFailure() throws Exception {
        var a=agent("writer");var room=chat.group("test",List.of(a.id()));
        for(int i=0;i<10;i++) chat.send(send(room.id(),"history-"+i,List.of()));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            if(calls.incrementAndGet()==2)throw new AgentModelClient.Failure("超时");return "已完成第一批";
        });
        contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("FAILED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary",Integer.class));
        doAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);String text=input.getLast().get("content");
            assertTrue(text.contains("已完成第一批")||text.contains("已继续压缩"));assertFalse(text.contains("history-0"));return "已继续压缩";
        }).when(model).complete(any(),anyString(),anyList());
        contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("SUCCEEDED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
        assertEquals(10,store.page(room.id(),null,null,100).messages().size());
    }
    @Test void noEarlierHistoryCompletesWithoutPaidModelCall() {
        var a=agent("writer");var room=chat.group("test",List.of(a.id()));
        chat.send(send(room.id(),"single turn",List.of()));
        contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString());assertTrue(engine.runNext());
        var op=store.page(room.id(),null,null,100).contextOperations().getFirst();
        assertEquals("SUCCEEDED",op.status());assertTrue(op.result().contains("暂无可压缩"));verifyNoInteractions(model);
        assertEquals(1,store.page(room.id(),null,null,100).messages().size());
    }

    @Test void archiveSummarizesWholeRoundWithoutMutatingLiveMemoryAndFinishesAtomically() throws Exception {
        var a=agent("writer");var room=chat.group("round",List.of(a.id()));
        chat.send(send(room.id(),"first original",List.of()));chat.send(send(room.id(),"second original",List.of()));
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);String text=input.getLast().get("content");
            assertTrue(text.contains("first original")&&text.contains("second original"));return "本轮总结：已确认原始事实。";
        });
        String id=UUID.randomUUID().toString();contexts.enqueueArchive(room.id(),a.id(),id);
        assertThrows(Exception.class,()->contexts.finishArchive(id));
        assertTrue(engine.runNext());var archive=contexts.archive(id);
        assertTrue(archive.get("summary").toString().contains("本轮总结"));
        assertEquals(2,json.valueToTree(archive.get("messages")).size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary",Integer.class));
        var updated=contexts.finishArchive(id);assertTrue(updated.topicStartedAfter()>0);
        assertEquals(updated.topicStartedAfter(),contexts.finishArchive(id).topicStartedAfter());
        assertEquals(2,store.page(room.id(),null,null,100).messages().size());
        chat.send(send(room.id(),"new round",List.of()));
        doReturn("新一轮总结").when(model).complete(any(),anyString(),anyList());
        String next=UUID.randomUUID().toString();contexts.enqueueArchive(room.id(),a.id(),next);engine.runNext();
        var messages=json.valueToTree(contexts.archive(next).get("messages"));assertEquals(1,messages.size());assertEquals("new round",messages.get(0).path("text").asText());
    }
    @Test void archiveFinishNeverDropsMessagesArrivingDuringExport() throws Exception {
        var a=agent("writer");var room=chat.group("round",List.of(a.id()));
        chat.send(send(room.id(),"archived",List.of()));when(model.complete(any(),anyString(),anyList())).thenReturn("总结");
        String id=UUID.randomUUID().toString();contexts.enqueueArchive(room.id(),a.id(),id);engine.runNext();
        chat.send(send(room.id(),"arrived during local export",List.of()));
        assertThrows(Exception.class,()->contexts.finishArchive(id));assertEquals(0,contexts.info(room.id()).topicStartedAfter());
        assertEquals(1,json.valueToTree(contexts.archive(id).get("messages")).size());assertEquals(2,store.page(room.id(),null,null,100).messages().size());
    }
    @Test void emptyRoundArchiveIsRejectedWithoutQueueOrModelCall() {
        var a=agent("writer");var room=chat.direct(a.id());
        assertThrows(Exception.class,()->contexts.enqueueArchive(room.id(),a.id(),UUID.randomUUID().toString()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_operation",Integer.class));verifyNoInteractions(model);
    }

    @Test void clearDeletesOnlyTargetHistoryAndContextAndBlocksOldRetries() throws Exception {
        var a=agent("writer");var room=chat.group("clear test",List.of(a.id()));var other=chat.group("other",List.of(a.id()));
        var photo=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(photo,"png",bytes);
        String imageId=UUID.randomUUID().toString();images.upload(imageId,room.id(),bytes.toByteArray());
        var input=new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos","with image",room.id(),List.of(),List.of(imageId));
        var first=chat.send(input);var kept=chat.send(send(other.id(),"keep this",List.of()));
        String proactive=UUID.randomUUID().toString();chat.publishAgentMessage(a.id(),room.id(),"proactive",proactive);
        when(model.complete(any(),anyString(),anyList())).thenReturn("summary");
        chat.send(send(room.id(),"question",List.of(a.id())));engine.runNext();
        contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString());engine.runNext();
        String archive=UUID.randomUUID().toString();contexts.enqueueArchive(room.id(),a.id(),archive);engine.runNext();
        var preview=contexts.clearPreview(room.id());assertTrue(preview.messageCount()>0);assertFalse(preview.hasActiveWork());
        String clearId=UUID.randomUUID().toString();var result=contexts.clear(room.id(),clearId,preview.latestMessageId(),preview.historyRevision(),true);
        assertEquals(preview.messageCount(),result.clearedMessages());assertEquals(1,result.historyRevision());
        var page=store.page(room.id(),null,null,100);assertTrue(page.messages().isEmpty()&&page.jobs().isEmpty()&&page.contextOperations().isEmpty());assertEquals(1,page.historyRevision());
        for(String table:List.of("chat_context_archive","chat_context_summary","chat_context_snapshot","chat_context_operation","chat_agent_job","chat_message_image"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_id=?",Integer.class,room.id()));
        assertTrue(contexts.info(room.id()).agents().getFirst().summary().isEmpty());assertNotNull(agents.get(a.id()));
        assertEquals(kept,store.page(other.id(),null,null,100).messages().getFirst());assertEquals(0,store.page(other.id(),null,null,100).historyRevision());
        assertThrows(Exception.class,()->chat.send(input));assertThrows(Exception.class,()->chat.publishAgentMessage(a.id(),room.id(),"proactive",proactive));
        assertThrows(Exception.class,()->images.upload(imageId,room.id(),bytes.toByteArray()));assertThrows(Exception.class,()->contexts.archive(archive));
        var fresh=chat.send(send(room.id(),"fresh message",List.of()));assertTrue(fresh.id()>preview.latestMessageId());
        assertEquals(result,contexts.clear(room.id(),clearId,preview.latestMessageId(),preview.historyRevision(),true));
        assertEquals(List.of(fresh),store.page(room.id(),null,null,100).messages());assertTrue(first.id()<fresh.id());
    }
    @Test void clearRequiresFreshConfirmationAndRejectsRunningWork() throws Exception {
        var a=agent("writer");var room=chat.group("clear test",List.of(a.id()));
        chat.send(send(room.id(),"first",List.of()));var preview=contexts.clearPreview(room.id());
        assertThrows(Exception.class,()->contexts.clear(room.id(),UUID.randomUUID().toString(),preview.latestMessageId(),preview.historyRevision(),false));
        chat.send(send(room.id(),"arrived after confirmation",List.of()));
        assertThrows(Exception.class,()->contexts.clear(room.id(),UUID.randomUUID().toString(),preview.latestMessageId(),preview.historyRevision(),true));
        var current=contexts.clearPreview(room.id());
        contexts.enqueueCompression(room.id(),a.id(),UUID.randomUUID().toString());
        assertTrue(contexts.clearPreview(room.id()).hasActiveWork());
        assertThrows(Exception.class,()->contexts.clear(room.id(),UUID.randomUUID().toString(),current.latestMessageId(),current.historyRevision(),true));
        assertEquals(2,store.page(room.id(),null,null,100).messages().size());assertEquals(0,store.page(room.id(),null,null,100).historyRevision());
    }
    @Test void clearApiRequiresAuthenticationOriginAndExplicitConfirmation() throws Exception {
        var a=agent("writer");var room=chat.group("clear api",List.of(a.id()));chat.send(send(room.id(),"original",List.of()));
        mvc().perform(get("/api/conversations/context/clear-preview").param("conversationId",room.id())).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/conversations/context/clear-preview").header("Authorization",AUTH).param("conversationId",room.id())).andExpect(status().isOk()).andExpect(jsonPath("$.messageCount").value(1));
        var preview=contexts.clearPreview(room.id());String id=UUID.randomUUID().toString();
        var body=new LinkedHashMap<String,Object>(Map.of("conversationId",room.id(),"requestId",id,"expectedLatestMessageId",preview.latestMessageId(),"expectedHistoryRevision",preview.historyRevision()));
        mvc().perform(post("/api/conversations/context/clear").header("Authorization",AUTH).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("confirm",true);Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc().perform(post("/api/conversations/context/clear").cookie(cookie).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc().perform(post("/api/conversations/context/clear").cookie(cookie).header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest").contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.clearedMessages").value(1)).andExpect(jsonPath("$.historyRevision").value(1));
    }
}
