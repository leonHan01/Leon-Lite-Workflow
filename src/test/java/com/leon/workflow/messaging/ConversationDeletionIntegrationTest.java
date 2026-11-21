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
    "spring.datasource.url=jdbc:h2:mem:conversation-delete;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false", "leon.agents.dispatch-enabled=false", "leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ConversationDeletionIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ConversationBranchService branches;
    @Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents;
    @Autowired AgentChatService chat;
    @Autowired ChatImageService images;
    @Autowired ChatDocumentService documents;
    @Autowired AgentContextManager contexts;
    @Autowired ConversationDeletionService deletion;
    @Autowired ChatFavorites favorites;
    @Autowired ChatBackupService backups;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BrowserSessionService sessions;
    @Autowired ApiTokenInterceptor auth;
    @MockitoBean AgentModelClient model;
    MockMvc mvc;
    String uuid() {return UUID.randomUUID().toString();}
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        for (String table:List.of("chat_deleted_conversation","chat_favorite_document","chat_favorite_image","chat_favorite","chat_cleared_message","chat_cleared_image","chat_cleared_document","chat_context_fact","chat_helper_memory","chat_backup_import","chat_conversation_branch","chat_context_archive","chat_context_operation","chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_message_document","chat_agent","chat_conversation_member")) jdbc.update("DELETE FROM "+table);
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

    AgentContextController.Clear confirmation(String room) {
        var p=contexts.clearPreview(room);
        return new AgentContextController.Clear(room,uuid(),p.latestMessageId(),p.historyRevision(),true);
    }
    void idle(String room) {jdbc.update("UPDATE chat_agent_job SET status='FAILED' WHERE conversation_id=?",room);}
    org.springframework.test.web.servlet.ResultActions delete(User user,AgentContextController.Clear input) throws Exception {
        return mvc.perform(post("/api/conversations/context/delete").header("Authorization",user.token())
            .contentType("application/json").content(json.writeValueAsString(input)));
    }
    void favorite(User user,long message) throws Exception {
        mvc.perform(post("/api/device-messages/"+message+"/favorite").header("Authorization",user.token()).contentType("application/json").content("{}"))
            .andExpect(status().isOk());
    }

    @Test void physicallyRemovesRoomMessagesAttachmentsContextAndFavoriteCopies() throws Exception {
        var user=user();var agent=agent();var room=direct(user,agent);var other=direct(user,agent());
        String image=uuid(),doc=uuid();images.upload(image,room.id(),ChatImageIntegrationTest.png(30,20));
        documents.upload(doc,room.id(),user.id(),"private.txt","PRIVATE_DOCUMENT".getBytes(StandardCharsets.UTF_8));
        var first=send(room.id(),"PRIVATE_BODY",List.of(image),null,List.of(doc));
        send(room.id(),"PRIVATE_QUOTE",List.of(),first.id(),List.of());
        favorite(user,first.id());idle(room.id());
        send(other.id(),"KEEP_OTHER",List.of(),null,List.of());idle(other.id());
        jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,10,0,0)",room.id());
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'PRIVATE_SUMMARY',1)",room.id(),agent.id(),first.id());
        jdbc.update("INSERT INTO chat_context_snapshot(message_id,agent_id,conversation_id,epoch,messages_json,estimated_tokens,retained_rounds,created_at) VALUES(?,?,?,0,'PRIVATE_SNAPSHOT',1,1,1)",first.id(),agent.id(),room.id());
        jdbc.update("INSERT INTO chat_context_fact(conversation_id,agent_id,epoch,subject_id,speaker,kind,fact_key,fact_value,source_message_id,evidence,deleted) VALUES(?,?,0,?,'speaker','fact','key','PRIVATE_FACT',?,'PRIVATE_EVIDENCE',FALSE)",room.id(),agent.id(),user.id(),first.id());
        jdbc.update("INSERT INTO chat_helper_memory(conversation_id,history_revision,epoch,after_id,through_id,summary) VALUES(?,0,0,0,?,'PRIVATE_MEMORY')",room.id(),first.id());
        jdbc.update("INSERT INTO chat_context_archive(id,conversation_id,agent_id,epoch,after_id,through_id,title,summary,messages_json,created_at) VALUES(?,?,?,0,0,?,'PRIVATE_TITLE','PRIVATE_ARCHIVE','PRIVATE_JSON',1)",uuid(),room.id(),agent.id(),first.id());
        delete(user,confirmation(room.id())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.deletedMessages").value(2));
        for(String table:List.of("device_message","chat_message_image","chat_message_document","chat_agent_job","chat_context_policy","chat_context_summary","chat_context_snapshot","chat_context_fact","chat_helper_memory","chat_context_archive","chat_context_operation","chat_conversation_member"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE conversation_id=?",Integer.class,room.id()),table);
        for(String table:List.of("chat_favorite","chat_favorite_image","chat_favorite_document"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
        assertThrows(Exception.class,()->store.conversation(room.id()));
        assertEquals("KEEP_OTHER",store.page(other.id(),null,null,100).messages().getFirst().text());
        assertNotNull(agents.get(agent.id()));
        mvc.perform(get("/api/conversations").header("Authorization",user.token())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(room.id()))));
        verifyNoInteractions(model);
    }

    @Test void deletesFavoritesRetainedByAnEarlierClear() throws Exception {
        var user=user();var room=direct(user,agent());var message=send(room.id(),"CLEARED_FAVORITE",List.of(),null,List.of());
        favorite(user,message.id());idle(room.id());
        jdbc.update("UPDATE chat_favorite SET conversation_id=NULL");
        favorites.initialize();
        assertEquals(room.id(),jdbc.queryForObject("SELECT conversation_id FROM chat_favorite",String.class));
        var p=contexts.clearPreview(room.id());
        contexts.clear(room.id(),uuid(),p.latestMessageId(),p.historyRevision(),true);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_favorite",Integer.class));
        delete(user,confirmation(room.id())).andExpect(status().isOk()).andExpect(jsonPath("$.deletedMessages").value(0));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_favorite",Integer.class));
    }

    @Test void requiresOwnerConfirmationAndProtectsHomeAndBrowserOrigin() throws Exception {
        var owner=user();var outsider=user();var room=direct(owner,agent());var input=confirmation(room.id());
        delete(outsider,input).andExpect(status().isNotFound());
        delete(owner,new AgentContextController.Clear(room.id(),uuid(),0,0,false)).andExpect(status().isBadRequest());
        delete(owner,confirmation("home:"+owner.id())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations/context/delete").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc.perform(post("/api/conversations/context/delete").cookie(cookie).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        assertEquals(room,store.conversation(room.id()));
    }

    @Test void activeWorkAndChangedHistoryRejectDeletionWithoutPartialWrites() throws Exception {
        var user=user();var room=direct(user,agent());var first=send(room.id(),"KEEP_BUSY",List.of(),null,List.of());
        favorite(user,first.id());var input=confirmation(room.id());
        mvc.perform(get("/api/conversations/context/delete-preview").header("Authorization",user.token()).param("conversationId",room.id()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasActiveWork").value(true));
        delete(user,input).andExpect(status().isConflict());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_favorite",Integer.class));
        idle(room.id());send(room.id(),"NEW_MESSAGE",List.of(),null,List.of());idle(room.id());
        delete(user,input).andExpect(status().isConflict());
        assertEquals(2,store.page(room.id(),null,null,100).messages().size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_favorite",Integer.class));
        jdbc.update("INSERT INTO chat_context_operation(id,conversation_id,agent_id,agent_name,before_id,status,created_at) VALUES(?,?,?,'agent',0,'RUNNING',1)",uuid(),room.id(),room.agentId());
        delete(user,confirmation(room.id())).andExpect(status().isConflict());
    }

    @Test void sameDeletionCanBeRetriedButCannotBeReplayedByAnotherUser() throws Exception {
        var owner=user();var other=user();var room=direct(owner,agent());send(room.id(),"DELETE_ONCE",List.of(),null,List.of());idle(room.id());
        var input=confirmation(room.id());
        delete(owner,input).andExpect(status().isOk()).andExpect(jsonPath("$.deletedMessages").value(1));
        delete(owner,input).andExpect(status().isOk()).andExpect(jsonPath("$.deletedMessages").value(1));
        delete(other,input).andExpect(status().isNotFound());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_deleted_conversation",Integer.class));
    }

    @Test void reopeningDefaultChatUsesFreshIdentityAndOldThreadRequestCannotResurrect() throws Exception {
        var user=user();var agent=agent();var room=direct(user,agent());
        delete(user,confirmation(room.id())).andExpect(status().isOk());
        var reopened=direct(user,agents.get(room.agentId()));
        assertNotEquals(room.id(),reopened.id());assertEquals(reopened,direct(user,agents.get(room.agentId())));
        mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(new DeviceMessageStore.Send(uuid(),uuid(),"mac","macos","STALE_OUTBOX",room.id(),List.of(),List.of())))).andExpect(status().isNotFound());
        String request=uuid();var thread=store.createNamedDirect(agent,"thread:"+user.id()+":"+request,"PRIVATE_THREAD");
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",user.id(),thread.id());
        delete(user,confirmation(thread.id())).andExpect(status().isOk());
        mvc.perform(post("/api/conversations/direct/"+agent.id()+"/threads").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("requestId",request,"name","PRIVATE_THREAD")))).andExpect(status().isConflict());
        assertThrows(Exception.class,()->store.conversation(thread.id()));
    }

    @Test void branchCopiesSurviveButDeletedBranchCreationRequestsCannotResurrect() throws Exception {
        var user=user();var room=direct(user,agent());var point=send(room.id(),"BRANCH_COPY",List.of(),null,List.of());idle(room.id());
        String request=uuid();var branch=fork(user,room.id(),point.id(),request,"PRIVATE_BRANCH_TITLE");
        delete(user,confirmation(branch.id())).andExpect(status().isOk());
        mvc.perform(post("/api/conversations/"+room.id()+"/branches").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(new ConversationBranchController.Create(point.id(),request,"PRIVATE_BRANCH_TITLE")))).andExpect(status().isConflict());
        assertEquals("",jdbc.queryForObject("SELECT initial_name FROM chat_conversation_branch WHERE conversation_id=?",String.class,branch.id()));
        var retained=fork(user,room.id(),point.id(),uuid(),"Retained branch");
        delete(user,confirmation(room.id())).andExpect(status().isOk());
        assertEquals("BRANCH_COPY",store.page(retained.id(),null,null,100).messages().getFirst().text());
    }

    @Test void transactionFailureRestoresMessagesFavoritesAndConversation() throws Exception {
        var user=user();var room=direct(user,agent());var message=send(room.id(),"ROLLBACK_CONTENT",List.of(),null,List.of());idle(room.id());favorite(user,message.id());
        jdbc.execute("ALTER TABLE chat_deleted_conversation ADD CONSTRAINT reject_delete CHECK(deleted_messages<0)");
        try {
            assertThrows(Exception.class,()->deletion.delete(new ChatUserRegistry.User(user.id(),"test"),confirmation(room.id())));
            assertEquals(room,store.conversation(room.id()));assertEquals("ROLLBACK_CONTENT",store.message(message.id()).text());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_favorite",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_cleared_message",Integer.class));
        } finally {jdbc.execute("ALTER TABLE chat_deleted_conversation DROP CONSTRAINT reject_delete");}
    }

    @Test void deletedRestoreCannotBeRecreatedByTheSameImportRequest() throws Exception {
        var user=user();var source=direct(user,agent());send(source.id(),"RESTORE_CONTENT",List.of(),null,List.of());idle(source.id());
        byte[] backup=backups.export(source.id());String request=uuid();
        var restored=backups.restore(request,null,backup,user.id(),false);
        delete(user,confirmation(restored.id())).andExpect(status().isOk());
        assertThrows(Exception.class,()->backups.restore(request,null,backup,user.id(),false));
        assertThrows(Exception.class,()->store.conversation(restored.id()));
        assertEquals("",jdbc.queryForObject("SELECT source_hash FROM chat_backup_import WHERE conversation_id=?",String.class,restored.id()));
        assertEquals("RESTORE_CONTENT",store.page(source.id(),null,null,100).messages().getFirst().text());
    }

    @Test void reuseOfAnEarlierClearRequestStillDeletesAllCurrentContent() throws Exception {
        var user=user();var room=direct(user,agent());send(room.id(),"FIRST",List.of(),null,List.of());idle(room.id());
        var old=confirmation(room.id());contexts.clear(room.id(),old.requestId(),old.expectedLatestMessageId(),old.expectedHistoryRevision(),true);
        send(room.id(),"NEW_AFTER_CLEAR",List.of(),null,List.of());idle(room.id());var current=confirmation(room.id());
        delete(user,new AgentContextController.Clear(room.id(),old.requestId(),current.expectedLatestMessageId(),current.expectedHistoryRevision(),true))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deletedMessages").value(1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE conversation_id=?",Integer.class,room.id()));
    }
}
