package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:helper-outreach;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class HelperOutreachIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents;
    @Autowired AgentChatService chat;
    @Autowired ChatAccess access;
    @Autowired ChatUserRegistry users;
    @Autowired HelperConversationMemory memory;
    @Autowired ConversationFactMemory facts;
    @Autowired AgentContextManager contexts;
    @MockitoBean AgentModelClient model;
    HelperOutreachService service;
    MutableClock clock;
    AgentRegistry.Agent helper;
    final List<List<Map<String,String>>> calls=new ArrayList<>();

    static class MutableClock extends Clock {
        Instant now=at("10:00");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    static Instant at(String time){return LocalDateTime.parse("2026-11-20T"+time+":00").atZone(HelperOutreachService.ZONE).toInstant();}
    @BeforeEach void setUp() throws Exception {
        for(String table:List.of("chat_context_fact","chat_helper_outreach","chat_helper_memory","chat_context_summary","chat_context_archive","chat_context_snapshot",
                "chat_context_operation","chat_context_policy","chat_agent_job","device_message","chat_agent","chat_cleared_message"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation_member");jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");
        clock=new MutableClock();service=new HelperOutreachService(jdbc,store,agents,chat,access,memory,model,users,"",clock);
        helper=agent("Helper","helper");calls.clear();
        when(model.complete(any(),anyString(),anyList())).thenAnswer(invocation->{
            assertNotNull(AgentUsageContext.user());
            List<Map<String,String>> input=invocation.getArgument(2);calls.add(input);
            assertTrue(AgentContextManager.tokens(input)<=AgentContextManager.inputBudget(invocation.getArgument(0)));
            return input.getFirst().get("content").contains("正在主动发起")?"上午好，之前聊到的旅行计划进展如何？":"用户计划旅行，群聊中其他成员讨论了面试；小助手上次问过进展。";
        });
    }
    String uuid(){return UUID.randomUUID().toString();}
    AgentRegistry.Agent agent(String name,String handle){return agents.save(null,new AgentRegistry.Edit(name,handle,"openai","test-model","",true,"helper-test-key",null));}
    String direct(ChatUserRegistry.User user,AgentRegistry.Agent agent){
        String room="dm:"+user.id()+":"+agent.id();store.direct(agent,room);access.setOwner(room,user);return room;
    }
    void exchange(ChatUserRegistry.User user,String room,String text,List<String> mentions){
        chat.send(new DeviceMessageStore.Send(uuid(),uuid(),user.username(),"android",text,room,mentions),user.id());
        var work=store.claim();assertNotNull(work);store.complete(work,agents.get(work.agentId()),"收到你的消息");
    }
    void due(ChatUserRegistry.User user,int slot,Instant when){
        jdbc.update("UPDATE chat_helper_outreach SET due_at=? WHERE user_id=? AND local_date='2026-11-20' AND slot=?",when.toEpochMilli(),user.id(),slot);
    }
    String state(ChatUserRegistry.User user,int slot){return jdbc.queryForObject("SELECT status FROM chat_helper_outreach WHERE user_id=? AND local_date='2026-11-20' AND slot=?",String.class,user.id(),slot);}

    @Test void manualOutreachIsIdempotentWorksAfterHoursAndDoesNotConsumeDailySlots() {
        var alice=users.getOrCreate("ManualAlice");exchange(alice,direct(alice,helper),"你好",List.of());
        clock.now=at("08:00");String request=uuid();var delivery=service.startManual(alice,request);
        assertNotNull(delivery);assertNull(service.startManual(alice,request));
        assertEquals("RUNNING",service.manualStatus(alice,request).status());
        service.deliver(delivery);service.deliver(delivery);
        assertEquals("SENT",service.manualStatus(alice,request).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE client_message_id=?",Integer.class,request));
        service.plan(clock.instant());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_helper_outreach WHERE user_id=? AND slot>0",Integer.class,alice.id()));
        var bob=users.getOrCreate("ManualBob");assertThrows(Exception.class,()->service.manualStatus(bob,request));
        assertThrows(Exception.class,()->service.startManual(bob,uuid()));
    }

    @Test void plansOnlyAfterAnExchangeAndPersistsTwoDistinctDaytimeSlots() {
        var alice=users.getOrCreate("HelperAlice");var bob=users.getOrCreate("HelperBob");var eve=users.getOrCreate("HelperEve");
        String room=direct(alice,helper);direct(eve,helper);
        chat.send(new DeviceMessageStore.Send(uuid(),uuid(),bob.username(),"android","尚未回复",direct(bob,helper),List.of()),bob.id());
        var pending=store.claim();assertNotNull(pending);store.fail(pending,"测试失败");
        exchange(alice,room,"你好",List.of());service.plan(at("08:00"));
        var slots=jdbc.queryForList("SELECT user_id,due_at FROM chat_helper_outreach ORDER BY due_at");assertEquals(2,slots.size());
        for(var slot:slots){assertEquals(alice.id(),slot.get("user_id"));long time=((Number)slot.get("due_at")).longValue();assertTrue(time>=at("09:00").toEpochMilli()&&time<at("22:00").toEpochMilli());}
        assertTrue(((Number)slots.get(1).get("due_at")).longValue()-((Number)slots.get(0).get("due_at")).longValue()>=3_600_000);
        service.plan(at("12:00"));assertEquals(slots,jdbc.queryForList("SELECT user_id,due_at FROM chat_helper_outreach ORDER BY due_at"));
    }

    @Test void summarizesOtherAgentsAndJoinedGroupsWithoutReadingOtherUsersPrivateRooms() {
        var alice=users.getOrCreate("HelperAlice");var bob=users.getOrCreate("HelperBob");
        String target=direct(alice,helper);exchange(alice,target,"ALICE_HELPER_HISTORY",List.of());
        var writer=agent("Writer","writer");exchange(alice,direct(alice,writer),"ALICE_WRITER_TRAVEL",List.of());
        exchange(bob,direct(bob,writer),"BOB_PRIVATE_SECRET",List.of());
        var group=chat.group("面试群",List.of(writer.id()));access.setOwner(group.id(),bob);access.addMembers(group.id(),List.of(alice));
        exchange(bob,group.id(),"BOB_GROUP_INTERVIEW",List.of(writer.id()));
        var hidden=chat.group("无权访问的群",List.of(writer.id()));access.setOwner(hidden.id(),bob);
        exchange(bob,hidden.id(),"HIDDEN_GROUP_SECRET",List.of(writer.id()));
        service.plan(clock.instant());due(alice,1,clock.instant());
        var delivery=service.claim(clock.instant());assertNotNull(delivery);service.deliver(delivery);service.deliver(delivery);
        assertEquals("SENT",state(alice,1));
        String inputs=calls.toString();assertTrue(inputs.contains("ALICE_WRITER_TRAVEL"));assertTrue(inputs.contains("BOB_GROUP_INTERVIEW"));
        assertFalse(inputs.contains("BOB_PRIVATE_SECRET"));assertFalse(inputs.contains("HIDDEN_GROUP_SECRET"));
        assertFalse(calls.getLast().getLast().get("content").contains("ALICE_WRITER_TRAVEL"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE client_message_id=? AND conversation_id=? AND message_role='assistant' AND reply_to IS NULL",Integer.class,delivery.messageId(),target));
        assertNull(AgentUsageContext.user());
    }

    @Test void acceptsAGroupExchangeAndSendsPrivatelyInsteadOfToTheGroup() {
        var alice=users.getOrCreate("HelperAlice");var bob=users.getOrCreate("HelperBob");
        var group=chat.group("Helper 群",List.of(helper.id()));access.setOwner(group.id(),bob);access.addMembers(group.id(),List.of(alice));
        exchange(alice,group.id(),"@helper 帮我看看计划",List.of(helper.id()));
        service.plan(clock.instant());due(alice,1,clock.instant());var delivery=service.claim(clock.instant());assertNotNull(delivery);
        service.deliver(delivery);assertEquals("SENT",state(alice,1));
        assertEquals("dm:"+alice.id()+":"+helper.id(),jdbc.queryForObject("SELECT conversation_id FROM device_message WHERE client_message_id=?",String.class,delivery.messageId()));
        assertEquals(2,store.page(group.id(),null,null,100).messages().size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_helper_outreach WHERE user_id=?",Integer.class,bob.id()));
    }

    @Test void skipsMissedSlotsAndNeverPublishesAfterNightfallOrRestart() {
        var alice=users.getOrCreate("HelperAlice");exchange(alice,direct(alice,helper),"你好",List.of());service.plan(clock.instant());
        due(alice,1,at("09:00"));due(alice,2,at("21:59"));assertNull(service.claim(at("08:00")));
        assertNull(service.claim(at("10:00")));assertEquals("SKIPPED",state(alice,1));
        clock.now=at("21:59");var delivery=service.claim(clock.instant());assertNotNull(delivery);
        clock.now=at("22:00");service.deliver(delivery);assertEquals("FAILED",state(alice,2));verifyNoInteractions(model);
        jdbc.update("UPDATE chat_helper_outreach SET status='RUNNING' WHERE user_id=? AND slot=2",alice.id());
        service.recover();assertEquals("FAILED",state(alice,2));assertNull(service.claim(at("21:59")));
    }

    @Test void clearingASourceDuringGenerationPreventsSending() throws Exception {
        var alice=users.getOrCreate("HelperAlice");String room=direct(alice,helper);exchange(alice,room,"原始记录",List.of());
        service.plan(clock.instant());due(alice,1,clock.instant());var delivery=service.claim(clock.instant());assertNotNull(delivery);
        doAnswer(invocation->{
            List<Map<String,String>> input=invocation.getArgument(2);
            if(input.getFirst().get("content").contains("正在主动发起")) {
                var preview=contexts.clearPreview(room);contexts.clear(room,uuid(),preview.latestMessageId(),preview.historyRevision(),true);
                return "这条消息不应发送";
            }
            return "原始记录摘要";
        }).when(model).complete(any(),anyString(),anyList());
        service.deliver(delivery);assertEquals("SKIPPED",state(alice,1));
        assertEquals(0,store.page(room,null,null,100).messages().size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_helper_memory WHERE conversation_id=?",Integer.class,room));
    }

    @Test void cachedSummariesAreIncrementalAndTopicResetInvalidatesThem() {
        var alice=users.getOrCreate("HelperAlice");String room=direct(alice,helper);exchange(alice,room,"FIRST_TOPIC",List.of());
        service.plan(clock.instant());due(alice,1,clock.instant());service.deliver(Objects.requireNonNull(service.claim(clock.instant())));
        calls.clear();clock.now=at("12:00");due(alice,2,clock.instant());service.deliver(Objects.requireNonNull(service.claim(clock.instant())));
        assertEquals("SENT",state(alice,2));assertFalse(calls.toString().contains("FIRST_TOPIC"));
        contexts.configure(room,10,true);calls.clear();
        AgentUsageContext.run(alice.id(),()->{
            try { assertEquals("",memory.digest(alice,agents.runtime(helper.id()),()->{}).text()); }
            catch(Exception error){throw new AssertionError(error);}
        });
        assertTrue(calls.isEmpty());assertNull(service.claim(at("13:00")));
    }

    @Test void disabledHelperAndModelFailuresDoNotSendOrRetry() throws Exception {
        var alice=users.getOrCreate("HelperAlice");exchange(alice,direct(alice,helper),"你好",List.of());service.plan(clock.instant());due(alice,1,clock.instant());
        jdbc.update("UPDATE chat_agent SET enabled=false WHERE id=?",helper.id());assertNull(service.claim(clock.instant()));
        jdbc.update("UPDATE chat_agent SET enabled=true WHERE id=?",helper.id());var delivery=service.claim(clock.instant());assertNotNull(delivery);
        doThrow(new AgentModelClient.Failure("模型不可用")).when(model).complete(any(),anyString(),anyList());
        service.deliver(delivery);assertEquals("FAILED",state(alice,1));service.deliver(delivery);
        verify(model,times(1)).complete(any(),anyString(),anyList());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE client_message_id=?",Integer.class,delivery.messageId()));
    }

    @Test void revokingGroupMembershipDuringGenerationDiscardsTheResult() throws Exception {
        var alice=users.getOrCreate("HelperAlice");var bob=users.getOrCreate("HelperBob");
        exchange(alice,direct(alice,helper),"你好",List.of());
        var writer=agent("Writer","writer");
        var group=chat.group("可退出的群",List.of(writer.id()));access.setOwner(group.id(),bob);access.addMembers(group.id(),List.of(alice));
        exchange(bob,group.id(),"群里的内容",List.of(writer.id()));
        service.plan(clock.instant());due(alice,1,clock.instant());var delivery=service.claim(clock.instant());assertNotNull(delivery);
        doAnswer(invocation->{
            List<Map<String,String>> input=invocation.getArgument(2);
            if(input.getFirst().get("content").contains("正在主动发起")) {
                jdbc.update("DELETE FROM chat_conversation_member WHERE conversation_id=? AND user_id=?",group.id(),alice.id());
                return "不应发送已退出群的信息";
            }
            return "群聊摘要";
        }).when(model).complete(any(),anyString(),anyList());
        service.deliver(delivery);assertEquals("SKIPPED",state(alice,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE client_message_id=?",Integer.class,delivery.messageId()));
    }

    @Test void reusesExistingCompressedHistoryInsteadOfReadingOldMessages() throws Exception {
        var alice=users.getOrCreate("HelperAlice");String room=direct(alice,helper);exchange(alice,room,"OLD_RAW_HISTORY STRUCTURED_PREFERENCE",List.of());
        long through=jdbc.queryForObject("SELECT MAX(id) FROM device_message WHERE conversation_id=?",Long.class,room);
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'EXISTING_MEMORY',1)",room,helper.id(),through);
        long userMessage=jdbc.queryForObject("SELECT MIN(id) FROM device_message WHERE conversation_id=? AND message_role='user'",Long.class,room);
        var original=store.message(userMessage);
        facts.save(room,0,List.of(new ConversationFactMemory.Fact(helper.id(),ConversationFactMemory.subject(original),original.sender(),"preference","reply_style","STRUCTURED_PREFERENCE",userMessage,"STRUCTURED_PREFERENCE",false)));
        service.plan(clock.instant());due(alice,1,clock.instant());service.deliver(Objects.requireNonNull(service.claim(clock.instant())));
        assertEquals("SENT",state(alice,1));assertTrue(calls.toString().contains("EXISTING_MEMORY"));assertTrue(calls.toString().contains("STRUCTURED_PREFERENCE"));assertFalse(calls.toString().contains("OLD_RAW_HISTORY"));
    }

    @Test void longUnicodeMessagesAreSummarizedInBoundedPiecesIncludingTheirEnd() {
        var alice=users.getOrCreate("HelperAlice");String room=direct(alice,helper);
        exchange(alice,room,"旅行🙂".repeat(1500)+"END_OF_LONG_MESSAGE",List.of());
        service.plan(clock.instant());due(alice,1,clock.instant());service.deliver(Objects.requireNonNull(service.claim(clock.instant())));
        assertEquals("SENT",state(alice,1));assertTrue(calls.toString().contains("END_OF_LONG_MESSAGE"));
        assertTrue(calls.size()>5);
    }

    @Test void aModelResponseArrivingAfter22IsNotPublished() throws Exception {
        var alice=users.getOrCreate("HelperAlice");exchange(alice,direct(alice,helper),"你好",List.of());
        clock.now=at("21:59");service.plan(clock.instant());due(alice,1,clock.instant());
        var delivery=service.claim(clock.instant());assertNotNull(delivery);
        doAnswer(invocation->{
            List<Map<String,String>> input=invocation.getArgument(2);
            if(input.getFirst().get("content").contains("正在主动发起"))clock.now=at("22:00");
            return "晚到的模型响应";
        }).when(model).complete(any(),anyString(),anyList());
        service.deliver(delivery);assertEquals("FAILED",state(alice,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE client_message_id=?",Integer.class,delivery.messageId()));
    }
}
