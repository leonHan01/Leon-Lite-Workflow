package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:luna-context-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class LunaContextIntegrationTest {
    @Autowired AgentRegistry agents; @Autowired AgentChatService chat; @Autowired DeviceMessageStore store;
    @Autowired AgentReplyEngine engine; @Autowired AgentContextManager contexts; @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean AgentModelClient model;
    @Autowired ChatDocumentService documents;
    @Autowired ChatUserRegistry users;
    @BeforeEach void clean(){
        for(String table:List.of("chat_context_fact","chat_context_archive","chat_context_operation","chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");jdbc.update("DELETE FROM chat_agent");
    }
    private AgentRegistry.Agent agent(String handle,String modelName){return agents.save(null,new AgentRegistry.Edit(handle,handle,"openai",modelName,"角色 "+handle,true,"test-only-key",null));}
    private DeviceMessageStore.Message send(String room,String text){return chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.nameUUIDFromBytes(room.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"Mac","macos",text,room,List.of()));}
    private boolean compression(List<Map<String,String>> messages){return messages.getFirst().get("content").contains("你是对话记忆压缩器");}

    @Test void lunaUsesSmallerBudgetWhileOtherModelsKeepTheirBudget(){
        var luna=agent("luna","GPT-6-LUNA");var regular=agent("regular","gpt-6-example");
        assertEquals(3000,contexts.info(chat.direct(luna.id()).id()).inputTokenBudget());
        assertEquals(12000,contexts.info(chat.direct(regular.id()).id()).inputTokenBudget());
    }
    @Test void oversizedOldSnapshotIsRejectedEvenWithoutUserBackground() throws Exception {
        var luna=agent("cached","gpt-6-luna");var room=chat.direct(luna.id());var question=send(room.id(),"继续");
        var snapshot=List.of(Map.of("role","developer","content","角色"),Map.of("role","user","content","a".repeat(4800)),Map.of("role","assistant","content","b".repeat(4800)),Map.of("role","user","content","继续"));
        assertTrue(AgentContextManager.tokens(snapshot)>3000&&AgentContextManager.tokens(snapshot)<4000);
        jdbc.update("INSERT INTO chat_context_snapshot(message_id,agent_id,conversation_id,epoch,messages_json,estimated_tokens,retained_rounds,created_at) VALUES(?,?,?,0,?,?,1,0)",question.id(),luna.id(),room.id(),json.writeValueAsString(snapshot),AgentContextManager.tokens(snapshot));
        when(model.complete(any(),anyString(),anyList())).thenReturn("should not call");engine.runNext();
        var jobs=store.page(room.id(),null,null,100).jobs();assertEquals(1,jobs.size());assertTrue(jobs.getFirst().error().contains("预算"));verifyNoInteractions(model);
    }
    @Test void lunaCompactsEarlierAndKeepsEveryModelRequestWithinItsBudget() throws Exception {
        var luna=agent("early","gpt-6-luna");var room=chat.direct(luna.id());var compressed=new AtomicInteger();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);assertTrue(AgentContextManager.tokens(input)<=3000);
            if(compression(input)){assertTrue(input.getFirst().get("content").contains("明确确认"));compressed.incrementAndGet();return "{\"summary\":\"保留当前进展。\",\"facts\":[]}";}
            return "ok";
        });
        for(int i=0;i<3;i++){send(room.id(),"第"+i+"次"+"中".repeat(300));engine.runNext();}
        assertTrue(compressed.get()>0,"Luna should compact before the old 3200-token trigger");
        assertTrue(store.page(room.id(),null,null,100).jobs().isEmpty());
    }
    @Test void lunaRejectsAnOversizedSummaryWithoutReplacingMemoryButRegularModelsAcceptIt() throws Exception {
        var luna=agent("summary","gpt-6-luna");var regular=agent("normal","test-model");
        when(model.complete(any(),anyString(),anyList())).thenReturn("ok");
        var room=chat.direct(luna.id());send(room.id(),"早期内容");engine.runNext();send(room.id(),"最近内容");engine.runNext();
        var other=chat.direct(regular.id());send(other.id(),"早期内容");engine.runNext();send(other.id(),"最近内容");engine.runNext();
        doReturn("中".repeat(260)).when(model).complete(any(),anyString(),anyList());
        contexts.enqueueCompression(room.id(),luna.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("FAILED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());assertEquals("",contexts.info(room.id()).agents().getFirst().summary());
        contexts.enqueueCompression(other.id(),regular.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("SUCCEEDED",store.page(other.id(),null,null,100).contextOperations().getFirst().status());
    }
    @Test void lunaRetrievalAddsAtMostOneSmallWholeTurn() throws Exception {
        var luna=agent("retrieve","gpt-6-luna");var room=chat.direct(luna.id());
        when(model.complete(any(),anyString(),anyList())).thenAnswer(call->{List<Map<String,String>> input=call.getArgument(2);return compression(input)?"{\"summary\":\"行程已讨论。\",\"facts\":[]}":"ok";});
        send(room.id(),"酒店 A，编号 A123");engine.runNext();send(room.id(),"酒店 B，编号 B123");engine.runNext();send(room.id(),"继续");engine.runNext();
        contexts.enqueueCompression(room.id(),luna.id(),UUID.randomUUID().toString());engine.runNext();
        var calls=new ArrayList<List<Map<String,String>>>();doAnswer(call->{calls.add(call.getArgument(2));return "ok";}).when(model).complete(any(),anyString(),anyList());
        send(room.id(),"酒店叫什么");engine.runNext();assertEquals(1,contexts.info(room.id()).agents().getFirst().retrievedRounds());
        var references=calls.getFirst().stream().filter(m->m.get("content").startsWith("历史原文检索")).toList();assertEquals(1,references.size());
        assertTrue(AgentContextManager.tokens(references)<=350);
    }
    @Test void lunaLimitsFactPromptWithoutDeletingStoredFacts() throws Exception {
        var luna=agent("factsize","gpt-6-luna");var room=chat.direct(luna.id());
        when(model.complete(any(),anyString(),anyList())).thenReturn("ok");
        var budget=send(room.id(),"预算5000元");engine.runNext();var goal=send(room.id(),"目的地北京");engine.runNext();var preference=send(room.id(),"喜欢靠窗");engine.runNext();send(room.id(),"继续");engine.runNext();
        var material=List.of(Map.of("sourceMessageId",budget.id(),"kind","fact","key","budget","value","5000元","evidence",budget.text(),"op","set"),Map.of("sourceMessageId",goal.id(),"kind","goal","key","destination","value","北京","evidence",goal.text(),"op","set"),Map.of("sourceMessageId",preference.id(),"kind","preference","key","seat","value","靠窗","evidence",preference.text(),"op","set"));
        String answer=json.writeValueAsString(Map.of("summary","行程规划中。","facts",material));
        doReturn(answer).when(model).complete(any(),anyString(),anyList());contexts.enqueueCompression(room.id(),luna.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("SUCCEEDED",store.page(room.id(),null,null,100).contextOperations().getFirst().status());
        assertEquals(3,contexts.info(room.id()).agents().getFirst().facts().size());
        var inputs=new ArrayList<List<Map<String,String>>>();doAnswer(call->{inputs.add(call.getArgument(2));return "ok";}).when(model).complete(any(),anyString(),anyList());
        send(room.id(),"预算多少");engine.runNext();
        var facts=inputs.getFirst().stream().filter(m->m.get("content").startsWith("已确认事实")).toList();assertEquals(1,facts.size());
        assertTrue(AgentContextManager.estimate(facts.getFirst().get("content"))+8<=500);assertTrue(facts.getFirst().get("content").contains("5000元"));
        assertEquals(3,contexts.info(room.id()).agents().getFirst().facts().size());
    }

    @Test void solAndTerraCarryMoreThanTwelveThousandTokensAndCompactAtSixteenThousand() throws Exception {
        for(String modelName:List.of("gpt-6.2-sol","GPT-6.1-TERRA")){
            var selected=agent(modelName.contains("sol")?"sol-budget":"terra-budget",modelName);
            var room=chat.direct(selected.id());var compacted=new AtomicInteger();var replies=new AtomicInteger();
            doAnswer(call->{
                List<Map<String,String>> input=call.getArgument(2);assertTrue(AgentContextManager.tokens(input)<=20000);
                if(compression(input)){compacted.incrementAndGet();return "{\"summary\":\"保留当前进展。\",\"facts\":[]}";}
                if(replies.getAndIncrement()==0){assertTrue(AgentContextManager.tokens(input)>12000);assertTrue(AgentContextManager.tokens(input)<16000);}
                return "ok";
            }).when(model).complete(any(),anyString(),anyList());
            assertEquals(20000,contexts.info(room.id()).inputTokenBudget());
            send(room.id(),"原文-"+"中".repeat(6500));engine.runNext();assertEquals(0,compacted.get());assertEquals(1,replies.get());
            send(room.id(),"后续-"+"中".repeat(1700));engine.runNext();assertEquals(1,compacted.get());assertEquals(2,replies.get());
            assertTrue(store.page(room.id(),null,null,100).jobs().isEmpty());
        }
    }

    @Test void chineseAttachmentsUseRemainingTokensAcrossModelsAndRetryReusesTheExcerpt() throws Exception {
        for(var name:List.of("luna","sol","terra")){
            var a=agent(name,"gpt-6.1-"+name);String room=chat.direct(a.id()).id();
            String user=users.getOrCreate("doc-test-"+name).id(),device=UUID.randomUUID().toString();
            users.updateBackground(user,"阅读背景"+"文".repeat(100),users.background(user).version());
            int chars=name.equals("luna")?2000:12000;
            var doc=documents.upload(UUID.randomUUID().toString(),room,user,"中文.txt",("开头🌍"+"中".repeat(chars)+"附件末尾").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String question="请用中文总结。"+"保留问题".repeat(10)+"问题末尾";
            var send=new DeviceMessageStore.Send(UUID.randomUUID().toString(),device,"用户","macos",question,room,List.of(),List.of(),null,List.of(doc.id()));
            var q=chat.send(send,user);
            var calls=new ArrayList<List<Map<String,String>>>();
            doAnswer(call->{calls.add(call.getArgument(2));if(calls.size()==1)throw new AgentModelClient.Failure("timeout");return "ok";}).when(model).complete(any(),anyString(),anyList());
            engine.runNext();store.retryJob(q.id(),a.id());engine.runNext();
            assertEquals(2,calls.size());assertEquals(calls.getFirst(),calls.getLast());
            assertTrue(AgentContextManager.tokens(calls.getFirst())<=AgentContextManager.inputBudget(a));
            assertTrue(calls.getFirst().toString().contains("阅读背景"));
            String material=calls.getFirst().getLast().get("content");
            assertTrue(material.contains(question));assertTrue(material.contains("开头🌍"));assertTrue(material.contains("仅提供部分内容"));
            assertFalse(material.contains("附件末尾"));assertFalse(material.contains("�"));
            send(room,"请再看前面的附件");engine.runNext();
            assertTrue(calls.getLast().stream().anyMatch(m->m.get("content").contains("开头🌍")));
            assertTrue(AgentContextManager.tokens(calls.getLast())<=AgentContextManager.inputBudget(a));
        }
    }

}
