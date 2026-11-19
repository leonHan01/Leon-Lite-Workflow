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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:chat-backup-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ChatBackupIntegrationTest {
    @Autowired WebApplicationContext context;@Autowired ChatBackupService backups;@Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents;@Autowired AgentChatService chat;@Autowired ChatImageService images;@Autowired AgentContextManager contexts;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired BrowserSessionService sessions;@Autowired ApiTokenInterceptor auth;
    @MockitoBean AgentModelClient model;
    private static final String AUTH="Bearer integration-test-token";
    @BeforeEach void clean(){
        for(String table:List.of("chat_backup_import","chat_context_archive","chat_context_operation","chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_agent","chat_cleared_message","chat_cleared_image"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");
    }
    String uuid(){return UUID.randomUUID().toString();}
    AgentRegistry.Agent agent(String handle){return agents.save(null,new AgentRegistry.Edit(handle,handle,"openai","test-model","private-system-prompt",true,"test-only-key-excluded-from-backup",null));}
    byte[] zip(Map<String,byte[]> entries)throws Exception{
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var item:entries.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    ChatBackupService.Backup manifest(byte[] archive)throws Exception{
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){assertEquals("manifest.json",zip.getNextEntry().getName());return json.readValue(zip.readAllBytes(),ChatBackupService.Backup.class);}
    }
    @Test void backupAndLoadPreserveMessagesImagesReplyLinksAndContextWithoutReplayingJobs()throws Exception{
        var agent=agent("writer");var source=chat.direct(agent.id());String image=uuid();images.upload(image,source.id(),ChatImageIntegrationTest.png(30,20));
        var user=chat.send(new DeviceMessageStore.Send(uuid(),uuid(),"手机","android","请记住 **BLUE**",source.id(),List.of(),List.of(image)));
        store.complete(Objects.requireNonNull(store.claim()),agent,"已记住 BLUE");
        jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,5,0,0)",source.id());
        jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,'偏好 BLUE',100)",source.id(),agent.id(),user.id());
        byte[] archive=backups.export(source.id());String contents=json.writeValueAsString(manifest(archive));
        assertFalse(contents.contains("test-only-key"));assertFalse(contents.contains("private-system-prompt"));
        String request=uuid();var restored=backups.restore(request,null,archive);
        assertNotEquals(source.id(),restored.id());assertEquals(restored,backups.restore(request,null,archive));
        var original=store.page(source.id(),null,null,100);var page=store.page(restored.id(),null,null,100);
        assertEquals(2,original.messages().size());assertEquals(2,page.messages().size());assertTrue(page.jobs().isEmpty());assertNull(store.claim());
        for(int i=0;i<2;i++){
            var a=original.messages().get(i);var b=page.messages().get(i);
            assertEquals(a.text(),b.text());assertEquals(a.sender(),b.sender());assertEquals(a.createdAt(),b.createdAt());assertEquals(a.role(),b.role());assertEquals(a.deviceId(),b.deviceId());assertNotEquals(a.id(),b.id());
        }
        assertEquals(page.messages().getFirst().id(),page.messages().getLast().replyTo());
        String restoredImage=page.messages().getFirst().images().getFirst().id();byte[] expectedImage=images.bytes(image);assertNotEquals(image,restoredImage);assertArrayEquals(expectedImage,images.bytes(restoredImage));
        var memory=contexts.info(restored.id());assertEquals(5,memory.recentRounds());assertEquals("偏好 BLUE",memory.agents().getFirst().summary());
        var preview=contexts.clearPreview(source.id());contexts.clear(source.id(),uuid(),preview.latestMessageId(),preview.historyRevision(),true);
        assertArrayEquals(expectedImage,images.bytes(restoredImage));assertEquals(2,store.page(restored.id(),null,null,100).messages().size());
        var reopened=new ChatBackupService(store,images,agents,jdbc,json);reopened.initialize();assertEquals(restored,reopened.restore(request,null,archive));
    }
    @Test void loadingCanMapADirectBackupToAnotherAgentAndSharedGroupsKeepMembers()throws Exception{
        var first=agent("first");var second=agent("second");var room=chat.direct(first.id());
        chat.publishAgentMessage(first.id(),room.id(),"第一位助手的历史",uuid());
        var loaded=backups.restore(uuid(),second.id(),backups.export(room.id()));
        assertEquals(second.id(),loaded.agentId());assertEquals(second.id(),store.page(loaded.id(),null,null,100).messages().getFirst().agentId());
        chat.publishAgentMessage(first.id(),"shared","群聊历史",uuid());
        var group=backups.restore(uuid(),null,backups.export("shared"));assertEquals("group",group.kind());assertTrue(group.agentIds().contains(first.id()));
    }
    @Test void invalidArchivesAndConflictingRetriesLeaveExistingHistoryUntouched()throws Exception{
        var a=agent("writer");var room=chat.direct(a.id());chat.publishAgentMessage(a.id(),room.id(),"原消息",uuid());
        byte[] archive=backups.export(room.id());String request=uuid();backups.restore(request,null,archive);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation",Integer.class);
        assertThrows(Exception.class,()->backups.restore(uuid(),null,"invalid".getBytes()));
        assertThrows(Exception.class,()->backups.restore(uuid(),null,new byte[ChatBackupService.MAX_BYTES+1]));
        assertThrows(Exception.class,()->backups.restore(uuid(),null,zip(Map.of("../manifest.json","{}".getBytes()))));
        var valid=manifest(archive);
        var bad=new ChatBackupService.Backup("leon-chat",999,valid.conversation(),valid.messages(),10,0,List.of());
        assertThrows(Exception.class,()->backups.restore(uuid(),null,zip(Map.of("manifest.json",json.writeValueAsBytes(bad)))));
        assertThrows(Exception.class,()->backups.restore(uuid(),null,zip(Map.of("manifest.json","{\"format\":\"leon-chat\",\"version\":1}".getBytes()))));
        chat.publishAgentMessage(a.id(),room.id(),"另一条消息",uuid());
        assertThrows(Exception.class,()->backups.restore(request,null,backups.export(room.id())));
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation",Integer.class));assertEquals(2,store.page(room.id(),null,null,100).messages().size());
    }
    @Test void deviceAndCookieAuthenticationProtectBackupAndThreadCreation()throws Exception{
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();var a=agent("writer");var room=chat.direct(a.id());
        mvc.perform(get("/api/conversations/backups").param("conversationId",room.id())).andExpect(status().isUnauthorized());
        byte[] bytes=mvc.perform(get("/api/conversations/backups").header("Authorization",AUTH).param("conversationId",room.id())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        mvc.perform(post("/api/conversations/backups").param("requestId",uuid()).contentType("application/zip").content(bytes)).andExpect(status().isUnauthorized());
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc.perform(post("/api/conversations/backups").cookie(cookie).param("requestId",uuid()).contentType("application/zip").content(bytes)).andExpect(status().isForbidden());
        mvc.perform(post("/api/conversations/backups").header("Authorization",AUTH).param("requestId",uuid()).contentType("application/zip").content(bytes)).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("direct"));
        String request=uuid(),url="/api/conversations/direct/"+a.id()+"/threads",body=json.writeValueAsString(new AgentController.ThreadRequest(request,"周末安排"));
        mvc.perform(post(url).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        for(int i=0;i<2;i++)mvc.perform(post(url).header("Authorization",AUTH).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("周末安排"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation WHERE id=?",Integer.class,"thread:"+request));
        assertTrue(chat.conversations().stream().anyMatch(c->c.id().equals("thread:"+request)&&c.name().equals("周末安排")));
        assertTrue(store.page("thread:"+request,null,null,100).messages().isEmpty());
    }
}
