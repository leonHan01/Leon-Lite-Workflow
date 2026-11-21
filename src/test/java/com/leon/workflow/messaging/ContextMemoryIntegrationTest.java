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
    @Autowired ConversationFactMemory factStore;
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

    private void remember(AgentRegistry.Agent agent,DeviceMessageStore.Message source,String key,String value,boolean deleted){
        factStore.save(source.conversationId(),0,List.of(new ConversationFactMemory.Fact(agent.id(),
            ConversationFactMemory.subject(source),source.sender(),"fact",key,value,source.id(),source.text(),deleted)));
    }
    private String ask(String room,AgentRegistry.Agent agent,String text) throws Exception {
        var question=send(room,text);
        return contexts.prepare(new DeviceMessageStore.Work(question.id(),agent.id(),room),agent,KEY).toString();
    }
    @Test void suppressedSourcesStayOutOfRecallAndBackupsAcrossMultiplePages() throws Exception {
        var a=agents.save(null,new AgentRegistry.Edit("mask","mask","openai","gpt-6-luna","",true,KEY,null));
        String room=chat.group("旅行",List.of(a.id())).id();
        var first=send(room,"盒子上贴着代号 ORCHID779");
        remember(a,first,"code","ORCHID779",false);
        var deleted=send(room,"请忘记代号");remember(a,deleted,"code","",true);
        var beforeCorrection=send(room,"礼物编号 WREN177");remember(a,beforeCorrection,"gift","WREN177",false);
        var corrected=send(room,"礼物编号是 FINCH234");remember(a,corrected,"gift","FINCH234",false);
        long through=0;
        for(int i=0;i<260;i++){
            var next=send(room,"座位"+i);remember(a,next,"seat"+i,next.text(),false);through=next.id();
        }
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'旧摘要提到 ORCHID779 和 WREN177',1)",room,a.id(),through);
        var restored=backups.restore(UUID.randomUUID().toString(),null,backups.export(room));
        for(String id:List.of(room,restored.id())){
            var info=contexts.info(id).agents().getFirst();
            assertEquals(261,info.facts().size());
            assertEquals(264,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,id));
            assertTrue(info.facts().stream().anyMatch(f->f.key().equals("seat0")));
            String payload=ask(id,a,"盒子上贴的是什么？礼物编号是什么？");
            assertFalse(payload.contains("ORCHID779"));assertFalse(payload.contains("WREN177"));
            assertTrue(payload.contains("FINCH234"));
        }
        var again=send(room,"我重新确认代号 ORCHID779");remember(a,again,"code","ORCHID779",false);
        assertTrue(ask(room,a,"代号是什么？").contains("ORCHID779"));
    }
    @Test void oldSnapshotAndQuotedTextRespectRetractionsButOtherSpeakersKeepTheirFacts() throws Exception {
        var a=agent("quotes");var room=chat.group("小组",List.of(a.id())).id();
        var old=send(room,"我的编号是 FOX445");remember(a,old,"number","FOX445",false);
        var other=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Bob","macos","Bob 的编号是 FOX445",room,List.of()));
        remember(a,other,"number","FOX445",false);
        var forget=send(room,"请忘记我的编号 FOX445");remember(a,forget,"number","",true);
        var q=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),old.deviceId(),"Mac","macos","请继续",room,List.of(),List.of(),old.id()));
        jdbc.update("INSERT INTO chat_context_snapshot(message_id,agent_id,conversation_id,epoch,messages_json,estimated_tokens,retained_rounds,created_at) VALUES(?,?,?,0,?,100,0,1)",
            q.id(),a.id(),room,json.writeValueAsString(List.of(Map.of("role","user","content","OLD-SNAPSHOT 我的编号是 FOX445"))));
        var input=contexts.prepare(new DeviceMessageStore.Work(q.id(),a.id(),room),a,KEY);
        assertFalse(input.toString().contains("OLD-SNAPSHOT"));assertFalse(input.toString().contains(old.text()));
        assertFalse(input.toString().contains(forget.text()));assertTrue(input.toString().contains(other.text()));
        assertTrue(input.getLast().get("content").contains(q.text()));assertFalse(input.getLast().get("content").contains("FOX445"));
    }

    @Test void retriesUseRetractionsKnownBeforeTheOriginalQuestionOnly() throws Exception {
        var a=agent("retrymask");String room=chat.group("测试",List.of(a.id())).id();
        var first=send(room,"包裹代码 ECHO447");remember(a,first,"code","ECHO447",false);
        var early=send(room,"包裹上写了什么");
        var earlyWork=new DeviceMessageStore.Work(early.id(),a.id(),room);
        var before=contexts.prepare(earlyWork,a,KEY);assertTrue(before.toString().contains("ECHO447"));
        var withdrawal=send(room,"请忘记包裹代码");
        var later=send(room,"包裹代码还记得吗");
        var laterWork=new DeviceMessageStore.Work(later.id(),a.id(),room);
        assertTrue(contexts.prepare(laterWork,a,KEY).toString().contains("ECHO447"));
        remember(a,withdrawal,"code","",true);
        var rebuilt=contexts.prepare(laterWork,a,KEY);assertFalse(rebuilt.toString().contains("ECHO447"));
        assertEquals(rebuilt,contexts.prepare(laterWork,a,KEY));
        assertEquals(before,contexts.prepare(earlyWork,a,KEY));
    }
    @Test void legacyDeleteWithoutSourceDoesNotExposeUnattributedHistory() throws Exception {
        var a=agent("legacyforget");String room=chat.group("导入聊天",List.of(a.id())).id();
        send(room,"旧盒子标有 NOTE333");
        var deleted=send(room,"忘记我的盒子编号");remember(a,deleted,"code","",true);
        var third=chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Bob","macos","另一个盒子标有 BIRD550",room,List.of()));
        remember(a,third,"code","BIRD550",false);
        String payload=ask(room,a,"盒子上标着什么");
        assertFalse(payload.contains("NOTE333"));assertTrue(payload.contains("BIRD550"));
    }

    @Test void wholeIdentifiersBeatNewerWordsSubstringsAndCompoundCodeFragments() throws Exception {
        var a=agent("ids");String room=chat.group("编号",List.of(a.id())).id();
        var exact=send(room,"计划使用 HoTel-4821/A、000731 和 ARCH-IV。");
        for(int i=0;i<265;i++)send(room,"plan 计划 HOTEL-48210/A PRE-HOTEL-4821/A HOTEL-4821/A-X 0007319 ARCH-IVE");
        var boundary=send(room,"已讨论");
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'行程',1)",room,a.id(),boundary.id());
        var search=new ContextHistorySearch(jdbc);
        for(String code:List.of("hotel-4821/a","000731","ARCH-IV")){
            var q=send(room,"plan 计划编号 "+code+" 怎么安排？");
            var work=new DeviceMessageStore.Work(q.id(),a.id(),room);
            var hits=search.find(work,0,Set.of(),q.text(),new ConversationFactMemory.Recall());
            assertEquals(exact.id(),hits.getFirst());
            assertFalse(search.find(work,exact.id(),Set.of(),q.text(),new ConversationFactMemory.Recall()).contains(exact.id()));
            assertFalse(search.find(work,0,Set.of(exact.id()),q.text(),new ConversationFactMemory.Recall()).contains(exact.id()));
            var firstReference=contexts.prepare(work,a,KEY).stream().filter(m->m.get("content").startsWith("历史原文检索")).findFirst().orElseThrow();
            assertTrue(firstReference.get("content").contains(exact.text()));
        }
    }

    @Test void multiBatchCompactionStagesVerifiedFactsAndRollsBackOnFailure() throws Exception {
        var a=agent("staging");String room=chat.group("分批",List.of(a.id())).id();
        var baseline=send(room,"旧进展");remember(a,baseline,"progress","旧进展",false);
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'原始进展',1)",room,a.id(),baseline.id());
        var first=send(room,"预算是5000元");
        for(int i=0;i<31;i++)send(room,"短轮次"+i);
        var correction=send(room,"预算改为8000元");send(room,"保留最后一轮");
        var calls=new AtomicInteger();
        doAnswer(call->{
            List<Map<String,String>> request=call.getArgument(2);String input=request.getLast().get("content");
            var info=contexts.info(room).agents().getFirst();
            assertEquals("原始进展",info.summary());assertEquals(1,info.facts().size());
            int n=calls.incrementAndGet();
            if(n%2==1){assertTrue(input.contains(first.text()));return factJson(first.id(),"5000元",first.text(),"set");}
            assertTrue(input.contains("已有结构化事实"));assertTrue(input.contains("5000元"));assertTrue(input.contains(correction.text()));
            if(n==2)throw new AgentModelClient.Failure("第二批超时");
            return factJson(correction.id(),"8000元",correction.text(),"set");
        }).when(model).complete(any(),anyString(),anyList());
        contexts.enqueueCompression(room,a.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("FAILED",store.page(room,null,null,100).contextOperations().getFirst().status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room));
        contexts.enqueueCompression(room,a.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals("SUCCEEDED",store.page(room,null,null,100).contextOperations().getFirst().status());assertEquals(4,calls.get());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room));
        assertTrue(contexts.info(room).agents().getFirst().facts().stream().anyMatch(f->f.value().equals("8000元")));
    }

    @Test void thirtyTwoTurnsReadOnePageEvenWhenSplitAcrossModelCalls() throws Exception {
        var a=agent("reads");String room=chat.group("读库",List.of(a.id())).id();
        for(int i=0;i<33;i++)send(room,"正文-"+i+" "+"中".repeat(120));
        var counted=spy(store);
        var manager=new AgentContextManager(counted,jdbc,agents,model,json);
        var calls=new AtomicInteger();var material=new StringBuilder();
        doAnswer(call->{
            List<Map<String,String>> request=call.getArgument(2);
            assertTrue(AgentContextManager.tokens(request)<=AgentContextManager.inputBudget(a));
            material.append(request.getLast().get("content"));calls.incrementAndGet();return "进展";
        }).when(model).complete(any(),anyString(),anyList());
        manager.enqueueCompression(room,a.id(),UUID.randomUUID().toString());manager.executeCompression(manager.claimCompression());
        assertEquals("SUCCEEDED",store.page(room,null,null,100).contextOperations().getFirst().status());assertTrue(calls.get()>1);
        verify(counted,times(1)).contextPage(any(),anyLong(),anyLong(),eq(32),eq(false),eq(false));
        verify(counted,never()).contextTurn(any(),anyLong());
        for(int i=0;i<32;i++)assertEquals(2,material.toString().split("正文-"+i+" ",-1).length);
        assertFalse(material.toString().contains("正文-32 "));
    }

    private Map<String,Object> change(DeviceMessageStore.Message m,String key,String value,String op){
        return Map.of("sourceMessageId",m.id(),"kind","fact","key",key,"value",value,"evidence",m.text(),"op",op);
    }
    @Test void archiveCombinesCommittedAndNewFactsAcrossBatchesUsingFinalVersionsOnly() throws Exception {
        var a=agent("exportfacts");String room=chat.group("合同",List.of(a.id())).id();
        var place=send(room,"已经确认交付地点为青竹园");remember(a,place,"交付地点","青竹园",false);
        var id=send(room,"合同编号是 ORCHID779");
        var seat=send(room,"座位代号为 WREN533");
        for(int i=0;i<29;i++)send(room,"进展"+i);
        var corrected=send(room,"合同编号改为 MAPLE842");var withdrawn=send(room,"忘记座位代号");
        var calls=new AtomicInteger();
        doAnswer(call->{
            List<Map<String,String>> request=call.getArgument(2);assertFalse(request.toString().contains("FUTURE999"));
            if(calls.incrementAndGet()==1)return json.writeValueAsString(Map.of("summary","已讨论合同。","facts",List.of(
                change(id,"合同编号","ORCHID779","set"),change(seat,"座位代号","WREN533","set"))));
            assertTrue(request.getLast().get("content").contains("ORCHID779"));
            return json.writeValueAsString(Map.of("summary","已讨论合同 ORCHID779 和座位 WREN533。","facts",List.of(
                change(corrected,"合同编号","MAPLE842","set"),change(withdrawn,"座位代号","","delete"))));
        }).when(model).complete(any(),anyString(),anyList());
        var job=contexts.enqueueArchive(room,a.id(),UUID.randomUUID().toString());send(room,"未来合同 FUTURE999");engine.runNext();
        var frozenWork=new DeviceMessageStore.Work(withdrawn.id()+1,a.id(),room);
        var page1=store.contextPage(frozenWork,0,frozenWork.messageId(),32,false,true);
        var page2=store.contextPage(frozenWork,page1.turns().getLast().getFirst().id(),frozenWork.messageId(),32,false,true);
        assertEquals(32,page1.turns().size(),"page1 hasMore="+page1.hasMore()+" page2="+page2.turns().size()+" calls="+calls.get());
        assertTrue(page1.hasMore(),"page2="+page2.turns().size()+" calls="+calls.get());
        assertEquals(2,page2.turns().size());
        assertEquals(2,calls.get());
        var result=contexts.archive(job.id());String summary=result.get("summary").toString();
        assertEquals("SUCCEEDED",store.page(room,null,null,100).contextOperations().getFirst().status(),result.get("operation").toString());
        assertTrue(summary.contains("青竹园"),summary);
        assertTrue(summary.contains("MAPLE842"),summary);
        assertTrue(summary.contains(corrected.sender()),summary);
        assertTrue(summary.contains("#"+corrected.id()),summary);
        assertFalse(summary.contains("ORCHID779"));assertFalse(summary.contains("WREN533"));assertFalse(summary.contains("FUTURE999"));
        assertTrue(result.get("messages").toString().contains("ORCHID779"));assertEquals(2,calls.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room));
    }

    @Test void automaticSecondBatchFailureFallsBackWithNoPartialFactsOrSummary() throws Exception {
        var a=agent("autobatch");String room=chat.group("自动回退",List.of(a.id())).id();
        var first=send(room,"盒子编号为 WREN485");
        for(int i=0;i<100;i++)send(room,"自动轮"+i+"甲".repeat(8));
        var q=send(room,"继续处理");
        var calls=new AtomicInteger();
        doAnswer(call->{
            List<Map<String,String>> request=call.getArgument(2);assertTrue(compression(request));
            if(calls.incrementAndGet()==1)return factJson(first.id(),"WREN485",first.text(),"set");
            assertTrue(request.getLast().get("content").contains("WREN485"));
            throw new AgentModelClient.Failure("第二批超时");
        }).when(model).complete(any(),anyString(),anyList());
        var counted=spy(store);
        var manager=new AgentContextManager(counted,jdbc,agents,model,json);
        var request=manager.prepare(new DeviceMessageStore.Work(q.id(),a.id(),room),a,KEY);
        verify(counted,times(4)).contextPage(any(),anyLong(),anyLong(),eq(32),eq(false),eq(false));
        verify(counted,times(1)).contextTurn(any(),anyLong());
        assertEquals(2,calls.get());assertTrue(contexts.info(room).agents().getFirst().compressionFallback());
        assertTrue(request.toString().contains(first.text()));assertTrue(request.toString().contains("自动轮99"));
        assertTrue(AgentContextManager.tokens(request)<=AgentContextManager.inputBudget(a));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_fact WHERE conversation_id=?",Integer.class,room));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room));
    }

    @Test void abortedTaskRollsBackFactsAndCheckpointsAtPublication() throws Exception {
        var a=agent("atomicpublish");String room=chat.group("提交",List.of(a.id())).id();
        var source=send(room,"代号是 MAPLE554");
        for(int i=0;i<33;i++)send(room,"短消息"+i);
        var count=new AtomicInteger();
        doAnswer(call->{
            if(count.incrementAndGet()==1)return factJson(source.id(),"MAPLE554",source.text(),"set");
            contexts.recoverCompressions();
            return "两批都完成";
        }).when(model).complete(any(),anyString(),anyList());
        contexts.enqueueCompression(room,a.id(),UUID.randomUUID().toString());engine.runNext();
        assertEquals(2,count.get());
        assertEquals("FAILED",store.page(room,null,null,100).contextOperations().getFirst().status());
        assertTrue(contexts.info(room).agents().getFirst().facts().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,room));
    }

    @Test void archiveFactSectionSurvivesBeyondNarrativeColumnSize() throws Exception {
        var a=agent("longarchive");String room=chat.group("大归档",List.of(a.id())).id();
        for(int i=0;i<48;i++){
            String value="reference-"+i+"-"+"x".repeat(160);
            var source=send(room,value);remember(a,source,"reference"+i,value,false);
        }
        doReturn("核对完毕").when(model).complete(any(),anyString(),anyList());
        var op=contexts.enqueueArchive(room,a.id(),UUID.randomUUID().toString());engine.runNext();
        String summary=contexts.archive(op.id()).get("summary").toString();
        assertTrue(summary.startsWith("核对完毕"));assertTrue(summary.length()>8000);
        for(int i=0;i<48;i++)assertTrue(summary.contains("reference-"+i+"-"+"x".repeat(160)));
    }

}
