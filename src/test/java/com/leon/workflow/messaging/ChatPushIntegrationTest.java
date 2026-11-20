package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:chat-push;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test","leon.push.enabled=true","leon.push.initial-delay-ms=3600000",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ChatPushIntegrationTest {
    @Autowired WebApplicationContext context;@Autowired ObjectMapper json;@Autowired DeviceMessageStore store;@Autowired JdbcTemplate jdbc;@Autowired ChatPushService push;
    @MockitoBean ChatPushGateway gateway;
    MockMvc mvc;
    record User(String id,String token){String room(){return "home:"+id;}ChatUserRegistry.User identity(){return new ChatUserRegistry.User(id,"test");}}
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).build();jdbc.update("DELETE FROM chat_push_device");when(gateway.available(anyString())).thenReturn(true);when(gateway.send(anyString(),anyString(),anyString(),anyString(),any(),anyString())).thenReturn(ChatPushGateway.Result.SENT);}
    User user()throws Exception{
        var data=mvc.perform(post("/api/conversations/account/login").header("Authorization","Bearer integration-test-token").contentType("application/json").content(json.writeValueAsString(Map.of("username","Push"+UUID.randomUUID().toString().substring(0,8))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var node=json.readTree(data);return new User(node.path("user").path("id").asText(),"Bearer "+node.path("sessionToken").asText());
    }
    DeviceMessageStore.Message reply(User user,String text){return store.publishAgent(new AgentRegistry.Agent(UUID.randomUUID().toString(),"Assistant","assistant","openai","test-model","",true,false,1),user.room(),text,UUID.randomUUID().toString());}
    void register(User user,String device,String token,String active)throws Exception{
        var registration=new ChatPushService.Registration(device,"android","vivo",token,"production",true,false,active);
        mvc.perform(post("/api/device-messages/notifications/devices").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(registration))).andExpect(status().isOk());
    }
    @Test void inboxStartsAtBaselineAndFiltersOtherAccountsAndOwnOutgoingMessages()throws Exception{
        var user=user();var other=user();String device=UUID.randomUUID().toString();reply(user,"historical");var baseline=push.inbox(user.identity(),device,null,false);assertTrue(baseline.events().isEmpty());
        reply(other,"secret");var own=reply(user,"new response");var result=push.inbox(user.identity(),device,baseline.nextAfter(),false);
        assertEquals(1,result.events().size());assertEquals(own.id(),result.events().getFirst().id());assertEquals("收到一条新消息",result.events().getFirst().text());assertEquals("new response",push.inbox(user.identity(),device,baseline.nextAfter(),true).events().getFirst().text());
        assertTrue(push.inbox(user.identity(),device,result.nextAfter(),false).events().isEmpty());
        mvc.perform(get("/api/device-messages/notifications").param("deviceId",device)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/device-messages/notifications/status").header("Authorization",user.token())).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(user.id()));
    }
    @Test void deviceDeliveryIsDurableDeduplicatedAndPrivateByDefault()throws Exception{
        var user=user();String device=UUID.randomUUID().toString();register(user,device,"vivo_test_token_123",null);var message=reply(user,"private body");
        push.dispatch();push.dispatch();verify(gateway,times(1)).send(eq("vivo"),eq("vivo_test_token_123"),eq("production"),eq(device),argThat(event->event.id()==message.id()&&event.text().equals("收到一条新消息")),eq(user.id()));
        assertEquals(message.id(),jdbc.queryForObject("SELECT last_id FROM chat_push_device WHERE user_id=?",Long.class,user.id()));
    }
    @Test void retriesKeepCursorAndBackoffAcrossHeartbeatRegistration()throws Exception{
        var user=user();String device=UUID.randomUUID().toString();register(user,device,"vivo_retry_token_123",null);reply(user,"retry response");
        when(gateway.send(anyString(),anyString(),anyString(),anyString(),any(),anyString())).thenReturn(ChatPushGateway.Result.RETRY,ChatPushGateway.Result.SENT);
        push.dispatch();long retry=jdbc.queryForObject("SELECT retry_at FROM chat_push_device WHERE user_id=?",Long.class,user.id());assertTrue(retry>System.currentTimeMillis());
        register(user,device,"vivo_retry_token_123",null);assertEquals(retry,jdbc.queryForObject("SELECT retry_at FROM chat_push_device WHERE user_id=?",Long.class,user.id()));
        push.dispatch();verify(gateway,times(1)).send(anyString(),anyString(),anyString(),anyString(),any(),anyString());
        jdbc.update("UPDATE chat_push_device SET retry_at=0 WHERE user_id=?",user.id());push.dispatch();push.dispatch();verify(gateway,times(2)).send(anyString(),anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void activePresenceDoesNotLoseBackgroundRepliesAndInvalidTokenIsDisabled()throws Exception{
        var user=user();String device=UUID.randomUUID().toString();register(user,device,"vivo_visible_token_123",user.room());reply(user,"currently visible");push.dispatch();verify(gateway,times(1)).send(anyString(),anyString(),anyString(),anyString(),any(),anyString());
        register(user,device,"vivo_visible_token_123",null);reply(user,"background");when(gateway.send(anyString(),anyString(),anyString(),anyString(),any(),anyString())).thenReturn(ChatPushGateway.Result.INVALID);push.dispatch();
        assertEquals(false,jdbc.queryForObject("SELECT enabled FROM chat_push_device WHERE user_id=?",Boolean.class,user.id()));
    }
    @Test void logoutAndAccountChangesRevokeOldDeliveryAndTokenOwnership()throws Exception{
        var first=user();var second=user();String one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString();register(first,one,"vivo_rebind_token_123",null);register(second,two,"vivo_rebind_token_123",null);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_push_device WHERE user_id=?",Integer.class,first.id()));
        mvc.perform(post("/api/conversations/account/logout").header("Authorization",second.token())).andExpect(status().isOk());reply(second,"after logout");push.dispatch();verify(gateway,never()).send(anyString(),anyString(),anyString(),anyString(),any(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_push_device WHERE user_id=?",Integer.class,second.id()));
    }
    @Test void revocationDuringDeliveryStopsTheRemainingBatch()throws Exception{
        var user=user();String device=UUID.randomUUID().toString();register(user,device,"vivo_revoke_batch_token",null);reply(user,"first");reply(user,"second");
        when(gateway.send(anyString(),anyString(),anyString(),anyString(),any(),anyString())).thenAnswer(invocation->{jdbc.update("DELETE FROM chat_user_session WHERE user_id=?",user.id());return ChatPushGateway.Result.SENT;});
        push.dispatch();verify(gateway,times(1)).send(anyString(),anyString(),anyString(),anyString(),any(),anyString());
    }
    @Test void clientsCannotRemoveOthersDevicesOrRegisterUnreadableRooms()throws Exception{
        var first=user();var second=user();String device=UUID.randomUUID().toString();register(first,device,"vivo_private_token_123",null);
        mvc.perform(post("/api/device-messages/notifications/devices/"+device+"/remove").header("Authorization",second.token())).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_push_device WHERE user_id=?",Integer.class,first.id()));
        var registration=new ChatPushService.Registration(device,"android","vivo","vivo_private_token_123","production",true,false,first.room());
        mvc.perform(post("/api/device-messages/notifications/devices").header("Authorization",second.token()).contentType("application/json").content(json.writeValueAsString(registration))).andExpect(status().isNotFound());
        assertTrue(ChatPushGateway.intent(new ChatPushService.Event(7,"group:a;b","Leon","new"),"user").contains("group%3Aa%3Bb"));
    }
}
