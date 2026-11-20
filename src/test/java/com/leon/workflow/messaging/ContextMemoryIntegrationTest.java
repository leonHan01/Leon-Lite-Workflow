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
    "spring.datasource.url=jdbc:h2:mem:context-memory-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ContextMemoryIntegrationTest {
    @Autowired AgentRegistry agents; @Autowired AgentChatService chat; @Autowired DeviceMessageStore store;
    @Autowired AgentReplyEngine engine; @Autowired AgentContextManager contexts; @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json; @Autowired ChatBackupService backups;
    @MockitoBean AgentModelClient model;
    private static final String KEY="test-only-context-key";
    @BeforeEach void clean(){
        for(String table:List.of("chat_context_fact","chat_context_archive","chat_context_operation","chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");jdbc.update("DELETE FROM chat_agent");
    }
    private AgentRegistry.Agent agent(String handle){return agents.save(null,new AgentRegistry.Edit(handle,handle,"openai","test-model","角色 "+handle,true,KEY,null));}
    private DeviceMessageStore.Message send(String room,String text){return chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.nameUUIDFromBytes(room.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"Mac","macos",text,room,List.of()));}
    private boolean compression(List<Map<String,String>> input){return input.getFirst().get("content").contains("你是对话记忆压缩器");}

    @Test void shortHistoryNeverCompressesBecauseOfRoundCount() throws Exception {
        var a=agent("memory");var room=chat.direct(a.id());contexts.configure(room.id(),1,false);
        var compressions=new AtomicInteger();var inputs=new ArrayList<List<Map<String,String>>>();
        doAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            if(compression(input)){compressions.incrementAndGet();return "摘要";}
            inputs.add(input);return "ok";
        }).when(model).complete(any(),anyString(),anyList());
        for(int i=0;i<30;i++){send(room.id(),"tiny-"+i);assertTrue(engine.runNext());}
        assertEquals(0,compressions.get(),"short exchanges must not trigger paid compaction by round count");
        assertTrue(inputs.getLast().toString().contains("tiny-0"));
    }
    private String factJson(long source,String value,String evidence,String op){
        return json.writeValueAsString(Map.of("summary","行程规划继续进行。","facts",List.of(Map.of("sourceMessageId",source,"kind","fact","key","travel_budget","value",value,"evidence",evidence,"op",op))));
    }
    private void replyNormally() throws Exception {doReturn("ok").when(model).complete(any(),anyString(),anyList());}
    private DeviceMessageStore.ContextOperation compact(String room,AgentRegistry.Agent a,String response) throws Exception {
        doAnswer(call->{List<Map<String,String>> input=call.getArgument(2);return compression(input)?response:"ok";}).when(model).complete(any(),anyString(),anyList());
        var op=contexts.enqueueCompression(room,a.id(),UUID.randomUUID().toString());assertTrue(engine.runNext());
        return store.page(room,null,null,100).contextOperations().getFirst();
    }
    @Test void compressionTimeoutFallsBackToCompleteOriginalAndRetryFreezesIt() throws Exception {
        var a=agent("fallback");var room=chat.direct(a.id());replyNormally();
        for(int i=0;i<3;i++){send(room.id(),"old-"+i+"中".repeat(600));engine.runNext();}
        var compressions=new AtomicInteger();var replies=new AtomicInteger();
        doAnswer(call->{
            List<Map<String,String>> input=call.getArgument(2);
            if(compression(input)){compressions.incrementAndGet();throw new AgentModelClient.Failure("timeout");}
            for(int i=0;i<3;i++)assertTrue(input.toString().contains("old-"+i));
            if(replies.getAndIncrement()==0)throw new AgentModelClient.Failure("reply timeout");
            return "done";
        }).when(model).complete(any(),anyString(),anyList());
        var question=send(room.id(),"question-"+"中".repeat(400));engine.runNext();
        assertTrue(compressions.get()>0);assertTrue(contexts.info(room.id()).agents().getFirst().compressionFallback());
        String frozen=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=?",String.class,question.id());
        store.retryJob(question.id(),a.id());engine.runNext();
        assertEquals(1,compressions.get());assertEquals(frozen,jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=?",String.class,question.id()));
        assertTrue(store.page(room.id(),null,null,100).jobs().isEmpty());
    }
    @Test void overBudgetCompressionFailureDoesNotSilentlyDiscardOldTurns() throws Exception {
        var a=agent("overbudget");var room=chat.direct(a.id());replyNormally();
        for(int i=0;i<3;i++){send(room.id(),"old-"+i+"中".repeat(600));engine.runNext();}
        doThrow(new AgentModelClient.Failure("timeout")).when(model).complete(any(),anyString(),anyList());
        var question=send(room.id(),"current-"+"中".repeat(1200));engine.runNext();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_snapshot WHERE message_id=?",Integer.class,question.id()));
        assertTrue(store.page(room.id(),null,null,100).jobs().getFirst().error().contains("原始上下文超过预算"));
        assertEquals(7,store.page(room.id(),null,null,100).messages().size());
    }
    @Test void verifiedFactsKeepSourceAndCorrectionsWithoutLeakingFutureIntoRetries() throws Exception {
        var a=agent("facts");var room=chat.direct(a.id());replyNormally();
        var first=send(room.id(),"旅行预算是5000元");engine.runNext();send(room.id(),"继续");engine.runNext();
        assertEquals("SUCCEEDED",compact(room.id(),a,factJson(first.id(),"5000元",first.text(),"set")).status());
        var old=contexts.info(room.id()).agents().getFirst().facts().getFirst();
        assertEquals(first.id(),old.sourceMessageId());assertEquals("5000元",old.value());
        var failed=send(room.id(),"当时预算多少");var work=store.claim();store.fail(work,"before prepare");
        replyNormally();var correction=send(room.id(),"旅行预算改成8000元");engine.runNext();send(room.id(),"继续安排");engine.runNext();
        assertEquals("SUCCEEDED",compact(room.id(),a,factJson(correction.id(),"8000元",correction.text(),"set")).status());
        var latest=contexts.info(room.id()).agents().getFirst().facts();assertEquals(1,latest.size());assertEquals("8000元",latest.getFirst().value());
        var inputs=new ArrayList<List<Map<String,String>>>();
        doAnswer(call->{inputs.add(call.getArgument(2));return "ok";}).when(model).complete(any(),anyString(),anyList());
        store.retryJob(failed.id(),a.id());engine.runNext();
        assertTrue(inputs.getFirst().toString().contains("5000元"));assertFalse(inputs.getFirst().toString().contains("8000元"));
        assertFalse(inputs.getFirst().getFirst().get("content").contains("5000元"));
    }
    @Test void assistantSuggestionsAndFabricatedValuesCannotBecomeUserFacts() throws Exception {
        var a=agent("validation");var room=chat.direct(a.id());replyNormally();
        var source=send(room.id(),"旅行预算是5000元");engine.runNext();send(room.id(),"继续");engine.runNext();
        long assistant=jdbc.queryForObject("SELECT MIN(id) FROM device_message WHERE conversation_id=? AND message_role='assistant'",Long.class,room.id());
        assertEquals("FAILED",compact(room.id(),a,factJson(assistant,"ok","ok","set")).status());
        assertEquals("FAILED",compact(room.id(),a,factJson(source.id(),"9999元",source.text(),"set")).status());
        assertTrue(contexts.info(room.id()).agents().getFirst().facts().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room.id()));
    }
    @Test void aForgottenFactIsRetractedAndNewTopicAndClearIsolateMemory() throws Exception {
        var a=agent("forget");var room=chat.direct(a.id());replyNormally();
        var source=send(room.id(),"旅行预算是5000元");engine.runNext();send(room.id(),"继续");engine.runNext();compact(room.id(),a,factJson(source.id(),"5000元",source.text(),"set"));
        replyNormally();var forget=send(room.id(),"请忘记旅行预算");engine.runNext();send(room.id(),"继续");engine.runNext();
        compact(room.id(),a,factJson(forget.id(),"",forget.text(),"delete"));
        assertTrue(contexts.info(room.id()).agents().getFirst().facts().isEmpty());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room.id()));
        var restored=backups.restore(UUID.randomUUID().toString(),a.id(),backups.export(room.id()));
        assertTrue(contexts.info(restored.id()).agents().getFirst().facts().isEmpty());
        replyNormally();var next=send(restored.id(),"预算是多少");engine.runNext();
        String payload=jdbc.queryForObject("SELECT messages_json FROM chat_context_snapshot WHERE message_id=?",String.class,next.id());
        assertTrue(payload.contains("请忘记旅行预算"));assertTrue(payload.contains("已撤销"));
        contexts.configure(room.id(),10,true);assertTrue(contexts.info(room.id()).agents().getFirst().facts().isEmpty());
        var preview=contexts.clearPreview(room.id());contexts.clear(room.id(),UUID.randomUUID().toString(),preview.latestMessageId(),preview.historyRevision(),true);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room.id()));
    }
    @Test void retrievalRestoresFullOldTextButExcludesOtherRoomsFutureAndNewTopics() throws Exception {
        var a=agent("search");var room=chat.direct(a.id());replyNormally();
        send(room.id(),"上次酒店是青竹旅馆，订单编号 HOTEL-4821");engine.runNext();send(room.id(),"继续行程");engine.runNext();
        compact(room.id(),a,"行程已经讨论过。");
        var other=chat.createDirect(a.id(),UUID.randomUUID().toString(),"other");replyNormally();send(other.id(),"酒店 OTHER-SECRET");engine.runNext();
        var question=send(room.id(),"上次酒店叫什么");send(room.id(),"酒店 FUTURE-SECRET");
        var inputs=new ArrayList<List<Map<String,String>>>();var calls=new AtomicInteger();
        doAnswer(call->{inputs.add(call.getArgument(2));if(calls.getAndIncrement()==0)throw new AgentModelClient.Failure("reply timeout");return "ok";}).when(model).complete(any(),anyString(),anyList());
        engine.runNext();String request=inputs.getFirst().toString();
        assertTrue(request.contains("历史原文检索"));assertTrue(request.contains("HOTEL-4821"));assertFalse(request.contains("OTHER-SECRET"));assertFalse(request.contains("FUTURE-SECRET"));
        assertTrue(contexts.info(room.id()).agents().getFirst().retrievedRounds()>0);
        engine.runNext();store.retryJob(question.id(),a.id());engine.runNext();assertEquals(inputs.getFirst(),inputs.getLast());
        contexts.configure(room.id(),10,true);inputs.clear();send(room.id(),"酒店之前叫什么");engine.runNext();
        assertFalse(inputs.getFirst().toString().contains("HOTEL-4821"));assertFalse(inputs.getFirst().toString().contains("FUTURE-SECRET"));
    }
    @Test void structuredFactsSurviveBackupWithRemappedSourceIds() throws Exception {
        var a=agent("backupfacts");var room=chat.direct(a.id());replyNormally();
        var source=send(room.id(),"旅行预算是5000元");engine.runNext();send(room.id(),"继续");engine.runNext();compact(room.id(),a,factJson(source.id(),"5000元",source.text(),"set"));
        byte[] bytes=backups.export(room.id());var restored=backups.restore(UUID.randomUUID().toString(),a.id(),bytes);
        var fact=contexts.info(restored.id()).agents().getFirst().facts().getFirst();
        assertEquals("5000元",fact.value());assertNotEquals(source.id(),fact.sourceMessageId());
        assertEquals(restored.id(),store.message(fact.sourceMessageId()).conversationId());assertTrue(store.message(fact.sourceMessageId()).text().contains(fact.evidence()));
    }

    @Test void groupFactsKeepSpeakersSeparateEvenWhenTheirKeysMatch() throws Exception {
        var a=agent("groupsources");var room=chat.group("旅行讨论",List.of(a.id()));
        var alice=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Alice","macos","我的预算是5000元",room.id(),List.of()));
        var bob=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Bob","android","我的预算是8000元",room.id(),List.of()));
        String sharedDevice=UUID.randomUUID().toString();
        jdbc.update("UPDATE device_message SET device_id=?,user_id=? WHERE id=?",sharedDevice,UUID.randomUUID().toString(),alice.id());
        jdbc.update("UPDATE device_message SET device_id=?,user_id=? WHERE id=?",sharedDevice,UUID.randomUUID().toString(),bob.id());
        send(room.id(),"继续讨论");
        String answer=json.writeValueAsString(Map.of("summary","两位用户在讨论各自的行程预算。","facts",List.of(
            Map.of("sourceMessageId",alice.id(),"kind","fact","key","travel_budget","value","5000元","evidence",alice.text(),"op","set"),
            Map.of("sourceMessageId",bob.id(),"kind","fact","key","travel_budget","value","8000元","evidence",bob.text(),"op","set"))));
        assertEquals("SUCCEEDED",compact(room.id(),a,answer).status());
        var remembered=contexts.info(room.id()).agents().getFirst().facts();assertEquals(2,remembered.size());
        assertTrue(remembered.stream().anyMatch(f->f.speaker().equals("Alice")&&f.value().equals("5000元")));
        assertTrue(remembered.stream().anyMatch(f->f.speaker().equals("Bob")&&f.value().equals("8000元")));
        assertEquals(remembered,new AgentContextManager(store,jdbc,agents,model,json).info(room.id()).agents().getFirst().facts());
        var restored=backups.restore(UUID.randomUUID().toString(),null,backups.export(room.id()));
        assertEquals(2,contexts.info(restored.id()).agents().getFirst().facts().size());
    }

}
