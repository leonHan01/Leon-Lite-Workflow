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
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:conversation-branch;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false", "leon.agents.dispatch-enabled=false", "leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ConversationBranchIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ConversationBranchService branches;
    @Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents;
    @Autowired AgentChatService chat;
    @Autowired ChatImageService images;
    @Autowired ChatDocumentService documents;
    @Autowired AgentContextManager contexts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BrowserSessionService sessions;
    @Autowired ApiTokenInterceptor auth;
    @MockitoBean AgentModelClient model;
    MockMvc mvc;
    String uuid() {return UUID.randomUUID().toString();}
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        for (String table:List.of("chat_conversation_branch","chat_context_archive","chat_context_operation","chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_message_document","chat_agent","chat_conversation_member")) jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");
        clearInvocations(model);
    }
    AgentRegistry.Agent agent() {
        String handle="branch"+uuid().substring(0,8);
        return agents.save(null,new AgentRegistry.Edit(handle,handle,"openai","test-model","branch system",true,"test-only-key",null));
    }
    record User(String id,String token) {}
    User user() throws Exception {
        var response=mvc.perform(post("/api/conversations/account/login").header("Authorization","Bearer integration-test-token")
            .contentType("application/json").content(json.writeValueAsString(Map.of("username","Branch"+uuid().substring(0,8)))))
            .andExpect(status().isOk()).andReturn().getResponse();
        var node=json.readTree(response.getContentAsByteArray());
        return new User(node.path("user").path("id").asText(),"Bearer "+node.path("sessionToken").asText());
    }
    DeviceMessageStore.Conversation direct(User user,AgentRegistry.Agent agent) throws Exception {
        return json.readValue(mvc.perform(post("/api/conversations/direct/"+agent.id()).header("Authorization",user.token()).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),DeviceMessageStore.Conversation.class);
    }
    DeviceMessageStore.Message send(String room,String text,List<String> imageIds,Long quote,List<String> docs) {
        return chat.send(new DeviceMessageStore.Send(uuid(),uuid(),"手机","ios",text,room,List.of(),imageIds,quote,docs),store.conversation(room).ownerId());
    }
    DeviceMessageStore.Conversation fork(User user,String source,long through,String request,String title) throws Exception {
        return json.readValue(mvc.perform(post("/api/conversations/"+source+"/branches").header("Authorization",user.token())
            .contentType("application/json").content(json.writeValueAsString(new ConversationBranchController.Create(through,request,title))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsByteArray(),DeviceMessageStore.Conversation.class);
    }

    @Test void copiesOnlyThePrefixWithNewIdsAndNoJobsOrFutureContext() throws Exception {
        var user=user();var agent=agent();var source=direct(user,agent);
        var first=send(source.id(),"PREFIX_ONLY",List.of(),null,List.of());
        store.complete(Objects.requireNonNull(store.claim()),agent,"PREFIX_REPLY");
        var point=store.page(source.id(),null,null,100).messages().getLast();
        var later=send(source.id(),"FUTURE_SECRET",List.of(),null,List.of());
        store.complete(Objects.requireNonNull(store.claim()),agent,"FUTURE_REPLY");
        jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,5,0,0)",source.id());
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'FUTURE_SUMMARY',1)",source.id(),agent.id(),later.id());
        var branch=fork(user,source.id(),point.id(),uuid(),"方案 B");
        assertEquals(user.id(),branch.ownerId());assertEquals(agent.id(),branch.agentId());
        var copied=store.page(branch.id(),null,null,100);
        assertEquals(List.of("PREFIX_ONLY","PREFIX_REPLY"),copied.messages().stream().map(DeviceMessageStore.Message::text).toList());
        assertNotEquals(first.id(),copied.messages().getFirst().id());
        assertEquals(copied.messages().getFirst().id(),copied.messages().getLast().replyTo());
        assertTrue(copied.jobs().isEmpty());assertTrue(copied.contextOperations().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_summary WHERE conversation_id=?",Integer.class,branch.id()));
        assertEquals(5,contexts.info(branch.id()).recentRounds());
        send(branch.id(),"CONTINUE_BRANCH",List.of(),null,List.of());
        var work=Objects.requireNonNull(store.claim());
        assertEquals(branch.id(),work.conversationId());
        String input=json.writeValueAsString(contexts.prepare(work,agent,"test-only-key"));
        assertTrue(input.contains("PREFIX_ONLY"));assertTrue(input.contains("PREFIX_REPLY"));assertFalse(input.contains("FUTURE"));
        assertEquals(4,store.page(source.id(),null,null,100).messages().size());
        verifyNoInteractions(model);
    }

    @Test void attachmentsQuotesAndAuthorshipSurviveClearingTheSource() throws Exception {
        var user=user();var agent=agent();var source=direct(user,agent);
        String image=uuid(),doc=uuid();images.upload(image,source.id(),ChatImageIntegrationTest.png(30,20));
        documents.upload(doc,source.id(),user.id(),"notes.txt","attachment evidence".getBytes(StandardCharsets.UTF_8));
        var first=send(source.id(),"带附件",List.of(image),null,List.of(doc));
        var second=send(source.id(),"引用附件",List.of(),first.id(),List.of());
        jdbc.update("UPDATE device_message SET user_id=? WHERE id=?",user.id(),first.id());
        var branch=fork(user,source.id(),second.id(),uuid(),"附件分支");
        var copied=store.page(branch.id(),null,null,100).messages();
        assertEquals(2,copied.size());assertEquals(user.id(),copied.getFirst().userId());
        var copyImage=copied.getFirst().images().getFirst();var copyDoc=copied.getFirst().documents().getFirst();
        assertNotEquals(image,copyImage.id());assertNotEquals(doc,copyDoc.id());
        assertEquals(copied.getFirst().id(),copied.getLast().quote().id());
        assertEquals(copyImage,copied.getLast().quote().images().getFirst());
        assertEquals(copyDoc,copied.getLast().quote().documents().getFirst());
        byte[] expectedImage=images.bytes(image),expectedDoc=documents.bytes(doc);
        jdbc.update("DELETE FROM chat_agent_job WHERE conversation_id=?",source.id());
        var preview=contexts.clearPreview(source.id());contexts.clear(source.id(),uuid(),preview.latestMessageId(),preview.historyRevision(),true);
        assertArrayEquals(expectedImage,images.bytes(copyImage.id()));assertArrayEquals(expectedDoc,documents.bytes(copyDoc.id()));
        assertEquals(2,store.page(branch.id(),null,null,100).messages().size());
        assertTrue(jdbc.queryForObject("SELECT extracted_text FROM chat_message_document WHERE id=?",String.class,copyDoc.id()).contains("evidence"));
    }

    @Test void retriesAreDurableScopedAndRejectChangedInputs() throws Exception {
        var user=user();var source=direct(user,agent());var point=send(source.id(),"原消息",List.of(),null,List.of());
        String request=uuid();var branch=fork(user,source.id(),point.id(),request,"方案 A");
        var restarted=new ConversationBranchService(store,jdbc,agents);restarted.initialize();
        assertEquals(branch,restarted.create(source.id(),point.id(),request,"方案 A",user.id()));
        mvc.perform(post("/api/conversations/"+source.id()+"/branches").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(new ConversationBranchController.Create(point.id(),request,"方案 B")))).andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_branch WHERE owner_id=?",Integer.class,user.id()));
        // The already created copy is returned even after its source records are cleared.
        jdbc.update("DELETE FROM device_message WHERE conversation_id=?",source.id());
        assertEquals(branch,fork(user,source.id(),point.id(),request,"方案 A"));
        var other=user();var otherSource=direct(other,source.agentId()==null?agent():agents.get(source.agentId()));
        var otherPoint=send(otherSource.id(),"其他账号",List.of(),null,List.of());
        assertNotEquals(branch.id(),fork(other,otherSource.id(),otherPoint.id(),request,"方案 A").id());
    }

    @Test void accessControlCsrfAndMismatchedOrMissingAnchorsAreEnforced() throws Exception {
        var user=user();var outsider=user();var source=direct(user,agent());var point=send(source.id(),"private",List.of(),null,List.of());
        String path="/api/conversations/"+source.id()+"/branches";
        String body=json.writeValueAsString(new ConversationBranchController.Create(point.id(),uuid(),"分支"));
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization",outsider.token()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc.perform(post(path).cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/conversations/home:"+user.id()+"/branches").header("Authorization",user.token()).contentType("application/json").content(body)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("原消息不存在或已清空，请刷新后重试"));
        for(long invalid:List.of(0L,-1L,Long.MAX_VALUE)) {
            mvc.perform(post(path).header("Authorization",user.token()).contentType("application/json")
                .content(json.writeValueAsString(new ConversationBranchController.Create(invalid,uuid(),"分支"))))
                .andExpect(status().is(invalid<=0?400:404));
        }
        for(String name:List.of(" ","x".repeat(61),"bad\nname")) assertThrows(Exception.class,()->branches.create(source.id(),point.id(),uuid(),name,user.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_branch",Integer.class));
    }

    @Test void groupMembersCanCreatePrivateBranchesWithoutInvitingTheSourceGroup() throws Exception {
        var owner=user();var member=user();var agent=agent();var source=chat.group("群讨论",List.of(agent.id()));
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",owner.id(),source.id());
        jdbc.update("INSERT INTO chat_conversation_member(conversation_id,user_id,joined_at) VALUES(?,?,1)",source.id(),member.id());
        var point=send(source.id(),"群历史",List.of(),null,List.of());
        var branch=fork(member,source.id(),point.id(),uuid(),"我的讨论");
        assertEquals("group",branch.kind());assertEquals(member.id(),branch.ownerId());assertEquals(List.of(agent.id()),branch.agentIds());
        mvc.perform(get("/api/device-messages").header("Authorization",owner.token()).param("conversationId",branch.id())).andExpect(status().isNotFound());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_member WHERE conversation_id=?",Integer.class,branch.id()));
    }

    @Test void topicBoundariesMapToCopiedIdsAndLaterResetsCannotEraseThePrefix() throws Exception {
        var user=user();var agent=agent();var source=direct(user,agent);
        var first=send(source.id(),"旧话题",List.of(),null,List.of());
        var point=send(source.id(),"新话题",List.of(),null,List.of());
        jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,7,?,3)",source.id(),first.id());
        var branch=fork(user,source.id(),point.id(),uuid(),"继承边界");
        long mapped=store.page(branch.id(),null,null,100).messages().getFirst().id();
        assertEquals(mapped,jdbc.queryForObject("SELECT after_id FROM chat_context_policy WHERE conversation_id=?",Long.class,branch.id()));
        jdbc.update("UPDATE chat_context_policy SET after_id=? WHERE conversation_id=?",point.id(),source.id());
        var older=fork(user,source.id(),first.id(),uuid(),"更早分支");
        assertEquals(0,jdbc.queryForObject("SELECT after_id FROM chat_context_policy WHERE conversation_id=?",Long.class,older.id()));
    }

    @Test void failedCopiesRollBackTheConversationMessagesAndAttachmentsTogether() throws Exception {
        var user=user();var source=direct(user,agent());
        String image=uuid();images.upload(image,source.id(),ChatImageIntegrationTest.png(10,10));
        send(source.id(),"first copy",List.of(image),null,List.of());
        var point=send(source.id(),"ROLLBACK_MARKER",List.of(),null,List.of());
        jdbc.execute("ALTER TABLE device_message ADD CONSTRAINT branch_atomic_test CHECK (body<>'ROLLBACK_MARKER' OR conversation_id='"+source.id()+"')");
        int rooms=jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation",Integer.class);
        try {
            assertThrows(Exception.class,()->branches.create(source.id(),point.id(),uuid(),"事务回滚",user.id()));
            assertEquals(rooms,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM device_message",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_branch",Integer.class));
        } finally {jdbc.execute("ALTER TABLE device_message DROP CONSTRAINT branch_atomic_test");}
    }

    @Test void largeHistoriesFailBeforeCreatingAPartialBranch() throws Exception {
        var user=user();var source=direct(user,agent());
        jdbc.update("""
            INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id)
            SELECT RANDOM_UUID(),RANDOM_UUID(),'fixture','ios','prefix',1,? FROM SYSTEM_RANGE(1,10001)
            """,source.id());
        long point=jdbc.queryForObject("SELECT MAX(id) FROM device_message WHERE conversation_id=?",Long.class,source.id());
        mvc.perform(post("/api/conversations/"+source.id()+"/branches").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(new ConversationBranchController.Create(point,uuid(),"过长分支")))).andExpect(status().isPayloadTooLarge());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_branch",Integer.class));
        assertEquals(10001,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE conversation_id=?",Integer.class,source.id()));
    }
}
