package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ai.OpenAiExecutor;
import com.leon.workflow.automation.WorkflowModels;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:chat-accounts;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop","leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ChatAccountIntegrationTest {
    static final String SERVICE="Bearer integration-test-token";
    static final String LOGIN="/api/conversations/account/login";
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentRegistry agents;
    @Autowired AgentReplyEngine engine;
    @Autowired WorkflowModels models;
    @Autowired OpenAiExecutor executor;
    @MockitoBean AgentModelClient model;
    MockMvc mvc;

    @BeforeEach void setUp(){mvc=MockMvcBuilders.webAppContextSetup(context).build();}
    private record Account(String id,String authorization){}
    private Account login(String name)throws Exception{
        var response=mvc.perform(post(LOGIN).header("Authorization",SERVICE).contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",name)))).andExpect(status().isOk()).andReturn().getResponse();
        var value=json.readTree(response.getContentAsByteArray());
        return new Account(value.path("user").path("id").asText(),"Bearer "+value.path("sessionToken").asText());
    }
    private String room(Account user,String path,Object data)throws Exception{
        var response=mvc.perform(post(path).header("Authorization",user.authorization()).contentType("application/json")
            .content(json.writeValueAsString(data))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("id").asText();
    }
    private DeviceMessageStore.Send message(String room,String text,List<String> targets){
        return new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"伪造名称",
            "android",text,room,targets);
    }
    private long send(Account user,DeviceMessageStore.Send input,String name)throws Exception{
        var result=mvc.perform(post("/api/device-messages").header("Authorization",user.authorization()).contentType("application/json")
            .content(json.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.sender").value(name))
            .andExpect(jsonPath("$.userId").value(user.id())).andReturn().getResponse();
        return json.readTree(result.getContentAsByteArray()).path("id").asLong();
    }
    @Test void scopesLoginAndEveryConversationEntryPointToItsMembers()throws Exception{
        var alice=login("Alice");var bob=login("Bob");var eve=login("Eve");
        assertEquals(alice.id(),login(" alice ").id());
        mvc.perform(post(LOGIN).contentType("application/json").content("{\"username\":\"Leon\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(LOGIN).header("Authorization",alice.authorization()).contentType("application/json").content("{\"username\":\"Leon\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent-admin").header("Authorization",alice.authorization())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations/account/me").header("Authorization",alice.authorization())).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("Alice"));
        send(alice,message("shared","私人首页",List.of()),"Alice");
        mvc.perform(get("/api/device-messages").header("Authorization",bob.authorization())).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(0));
        var agent=agents.save(null,new AgentRegistry.Edit("Writer","writerac","openai","test-model","",true,"test-key",null));
        String path="/api/conversations/direct/"+agent.id();
        String aliceDirect=room(alice,path,Map.of());
        String bobDirect=room(bob,path,Map.of());
        assertNotEquals(aliceDirect,bobDirect);
        mvc.perform(get("/api/device-messages").param("conversationId",aliceDirect).header("Authorization",bob.authorization())).andExpect(status().isNotFound());
        String group=room(alice,"/api/conversations/groups",Map.of("name","双人测试群","usernames",List.of("Bob")));
        send(bob,message(group,"群内可见",List.of()),"Bob");
        for(var member:List.of(alice,bob)){
            mvc.perform(get("/api/device-messages").param("conversationId",group).header("Authorization",member.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].text").value("群内可见"));
            mvc.perform(get("/api/conversations/"+group+"/members").header("Authorization",member.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        }
        mvc.perform(get("/api/conversations").header("Authorization",eve.authorization())).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '"+group+"')]").isEmpty());
        for(String url:List.of("/api/device-messages","/api/conversations/context","/api/conversations/backups")){
            mvc.perform(get(url).param("conversationId",group).header("Authorization",eve.authorization())).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/conversations/context/clear-preview").param("conversationId",group).header("Authorization",bob.authorization())).andExpect(status().isNotFound());
        String image=UUID.randomUUID().toString();
        mvc.perform(post("/api/device-messages/images").param("id",image).param("conversationId",group).header("Authorization",bob.authorization())
            .contentType("image/png").content(ChatImageIntegrationTest.png(15,15))).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages/images/"+image).header("Authorization",eve.authorization())).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/images/"+image).header("Authorization",alice.authorization())).andExpect(status().isOk());
        String members="/api/conversations/"+group+"/members";
        mvc.perform(post(members).header("Authorization",bob.authorization()).contentType("application/json").content("{\"usernames\":[\"Eve\"]}")).andExpect(status().isNotFound());
        mvc.perform(post(members).header("Authorization",alice.authorization()).contentType("application/json").content("{\"usernames\":[\"Eve\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages").param("conversationId",group).header("Authorization",eve.authorization())).andExpect(status().isOk());
        mvc.perform(post("/api/conversations/account/logout").header("Authorization",eve.authorization())).andExpect(status().isOk());
        mvc.perform(get("/api/conversations").header("Authorization",eve.authorization())).andExpect(status().isUnauthorized());
    }

    @Test void onlyOwnersCanRenameConversationsAndMembersSeeTheNewName() throws Exception {
        var owner = login("RenameOwner");
        var member = login("RenameMember");
        var outsider = login("RenameVisitor");
        var agent = groupAgent("rename");
        String group = room(owner, "/api/conversations/groups", Map.of("name","原群名称","usernames",List.of("RenameMember")));
        String direct = room(owner, "/api/conversations/direct/"+agent.id(), Map.of());
        long message = send(member, message(group,"保留的群聊记录",List.of()), "RenameMember");
        mvc.perform(rename(group, "未认证")).andExpect(status().isUnauthorized());
        for (var denied : List.of(member, outsider))
            mvc.perform(rename(group, "不能重命名").header("Authorization",denied.authorization())).andExpect(status().isNotFound());
        for (var invalid : List.of("  ", "两行\n名称", "字".repeat(61)))
            mvc.perform(rename(group, invalid).header("Authorization",owner.authorization())).andExpect(status().isBadRequest());
        mvc.perform(rename(group, "  新群名称  ").header("Authorization",owner.authorization()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.id").value(group)).andExpect(jsonPath("$.name").value("新群名称"));
        mvc.perform(rename(direct, "私聊新名称").header("Authorization",owner.authorization())).andExpect(status().isOk());
        mvc.perform(rename("shared", "我的设备").header("Authorization",owner.authorization()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("home:"+owner.id()));
        assertEquals("私聊新名称", conversationNames(owner).get(direct));
        var memberRooms = conversationNames(member);
        assertEquals("新群名称", memberRooms.get(group));
        assertEquals("设备群聊", memberRooms.get("home:"+member.id()));
        mvc.perform(get("/api/device-messages").param("conversationId",group).header("Authorization",member.authorization()))
            .andExpect(jsonPath("$.messages[0].id").value(message)).andExpect(jsonPath("$.historyRevision").value(0));
    }

    private MockHttpServletRequestBuilder rename(String id, String name) {
        return post("/api/conversations/rename").contentType("application/json")
            .content(json.writeValueAsString(Map.of("conversationId",id,"name",name)));
    }

    private Map<String,String> conversationNames(Account account) throws Exception {
        var result = mvc.perform(get("/api/conversations").header("Authorization",account.authorization()))
            .andExpect(status().isOk()).andReturn().getResponse();
        var names = new HashMap<String,String>();
        for (var conversation : json.readTree(result.getContentAsByteArray()))
            names.put(conversation.path("id").asText(), conversation.path("name").asText());
        return names;
    }

    private AgentRegistry.Agent groupAgent(String label){
        String handle=label+UUID.randomUUID().toString().substring(0,8);
        return agents.save(null,new AgentRegistry.Edit(label,handle,"openai","test-model","",true,"test-key",null));
    }
    @Test void groupOwnerCanAddAgentsWithoutReplacingMembersHistoryOrOwner()throws Exception{
        var owner=login("GroupOwner");var member=login("GroupMember");var outsider=login("GroupVisitor");
        var first=groupAgent("first");var second=groupAgent("second");
        String group=room(owner,"/api/conversations/groups",Map.of("name","可扩展群聊","usernames",List.of("GroupMember"),"agentIds",List.of(first.id())));
        send(member,message(group,"保留已有群聊记录",List.of()),"GroupMember");
        String url="/api/conversations/"+group+"/agents",body=json.writeValueAsString(Map.of("agentIds",List.of(second.id(),second.id())));
        mvc.perform(post(url).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        for(var denied:List.of(member,outsider))mvc.perform(post(url).header("Authorization",denied.authorization()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        for(int i=0;i<2;i++)mvc.perform(post(url).header("Authorization",owner.authorization()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.ownerId").value(owner.id())).andExpect(jsonPath("$.agentIds.length()").value(2))
            .andExpect(jsonPath("$.agentIds[0]").value(first.id())).andExpect(jsonPath("$.agentIds[1]").value(second.id()));
        mvc.perform(get("/api/conversations/"+group+"/members").header("Authorization",member.authorization())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        long fixture=send(member,message(group,"让新 Agent 回复",List.of(second.id())),"GroupMember");
        assertEquals(second.id(),jdbc.queryForObject("SELECT agent_id FROM chat_agent_job WHERE message_id=?",String.class,fixture));
        // This context also tests the reply worker; do not leave a queued fixture for it.
        jdbc.update("DELETE FROM chat_agent_job WHERE message_id=?",fixture);
        mvc.perform(get("/api/device-messages").param("conversationId",group).header("Authorization",member.authorization()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2)).andExpect(jsonPath("$.messages[0].text").value("保留已有群聊记录"));
        mvc.perform(get("/api/conversations").header("Authorization",member.authorization())).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '"+group+"')].agentIds[1]").value(org.hamcrest.Matchers.contains(second.id())));
    }
    @Test void invalidAgentBatchesLeaveGroupUntouchedAndPersonalChatsCannotBeExpanded()throws Exception{
        var owner=login("AtomicGroupOwner");var first=groupAgent("atomicfirst");var second=groupAgent("atomicsecond");
        String group=room(owner,"/api/conversations/groups",Map.of("name","原子验证群","agentIds",List.of(first.id())));
        for(var ids:List.of(List.<String>of(),List.of(second.id(),"invalid"),List.of(second.id(),UUID.randomUUID().toString()))){
            mvc.perform(post("/api/conversations/"+group+"/agents").header("Authorization",owner.authorization()).contentType("application/json").content(json.writeValueAsString(Map.of("agentIds",ids)))).andExpect(status().is4xxClientError());
            assertEquals(first.id(),jdbc.queryForObject("SELECT agent_ids FROM chat_conversation WHERE id=?",String.class,group));
        }
        jdbc.update("UPDATE chat_agent SET enabled=FALSE WHERE id=?",second.id());
        mvc.perform(post("/api/conversations/"+group+"/agents").header("Authorization",owner.authorization()).contentType("application/json").content(json.writeValueAsString(Map.of("agentIds",List.of(second.id()))))).andExpect(status().isBadRequest());
        String direct=room(owner,"/api/conversations/direct/"+first.id(),Map.of());
        for(String target:List.of("shared",direct))mvc.perform(post("/api/conversations/"+target+"/agents").header("Authorization",owner.authorization()).contentType("application/json").content(json.writeValueAsString(Map.of("agentIds",List.of(first.id()))))).andExpect(status().isBadRequest());
        assertEquals(first.id(),jdbc.queryForObject("SELECT agent_ids FROM chat_conversation WHERE id=?",String.class,group));
    }
    @Test void groupAgentLimitCountsExistingMembersAndConcurrentAdditionsAreMerged()throws Exception{
        var owner=login("LimitGroupOwner");var first=groupAgent("limitfirst");var second=groupAgent("limitsecond");var third=groupAgent("limitthird");
        String group=room(owner,"/api/conversations/groups",Map.of("name","并发群","agentIds",List.of(first.id())));
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->room(owner,"/api/conversations/"+group+"/agents",Map.of("agentIds",List.of(second.id()))));
            var two=pool.submit(()->room(owner,"/api/conversations/"+group+"/agents",Map.of("agentIds",List.of(third.id()))));
            assertEquals(group,one.get(5,TimeUnit.SECONDS));assertEquals(group,two.get(5,TimeUnit.SECONDS));
        }
        var batch=new ArrayList<String>();for(int i=0;i<29;i++)batch.add(groupAgent("batch").id());
        mvc.perform(post("/api/conversations/"+group+"/agents").header("Authorization",owner.authorization()).contentType("application/json").content(json.writeValueAsString(Map.of("agentIds",batch)))).andExpect(status().isOk()).andExpect(jsonPath("$.agentIds.length()").value(32));
        var extra=groupAgent("extra");
        mvc.perform(post("/api/conversations/"+group+"/agents").header("Authorization",owner.authorization()).contentType("application/json").content(json.writeValueAsString(Map.of("agentIds",List.of(extra.id()))))).andExpect(status().isBadRequest());
        var stored=Arrays.asList(jdbc.queryForObject("SELECT agent_ids FROM chat_conversation WHERE id=?",String.class,group).split(","));
        assertEquals(32,stored.size());assertTrue(stored.containsAll(List.of(first.id(),second.id(),third.id())));assertFalse(stored.contains(extra.id()));
    }

    @Test void countsDirectAndMacResponsesForGroupInitiatorExactlyOnce()throws Exception{
        var owner=login("Owner");var member=login("Member");
        var agent=agents.save(null,new AgentRegistry.Edit("Helper","helperac","openai","test-model","",true,"test-key",null));
        String group=room(owner,"/api/conversations/groups",Map.of("name","用量群","usernames",List.of("Member"),"agentIds",List.of(agent.id())));
        var usage=Map.of("prompt_tokens",20,"completion_tokens",5,"total_tokens",25);
        var response=Map.of("usage",usage,"choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","Agent 会话回复"))));
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<byte[]> responseMock=mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(responseMock);
        when(responseMock.statusCode()).thenReturn(200);
        when(responseMock.body()).thenReturn(json.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
        var direct=new OfficialAgentModelClient(json,http,models,executor);
        when(model.complete(any(),anyString(),anyList())).thenAnswer(invocation->
            direct.complete(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2)));
        send(member,message(group,"直接回复",List.of(agent.id())),"Member");
        assertTrue(engine.runNext());
        var workerId=(String)executor.register("integration").get("id");
        executor.heartbeat(workerId,true);
        executor.mode(true);
        try(var pool=Executors.newSingleThreadExecutor()){
            send(member,message(group,"Mac 回复",List.of(agent.id())),"Member");
            var running=pool.submit(engine::runNext);
            Map<String,Object> job=Map.of();
            for(int i=0;i<100&&job.isEmpty();i++){job=executor.claim(workerId);if(job.isEmpty())Thread.sleep(20);}
            assertFalse(job.isEmpty());
            String id=(String)job.get("id"),lease=(String)job.get("lease");
            assertEquals(member.id(),jdbc.queryForObject("SELECT chat_user_id FROM ai_executor_job WHERE id=?",String.class,id));
            executor.start(workerId,id,lease);
            var result=Map.<String,Object>of("status",200,"body",response);
            executor.complete(workerId,id,lease,result);
            executor.complete(workerId,id,lease,result);
            assertTrue(running.get(3,TimeUnit.SECONDS));
        }finally{
            executor.mode(false);
            executor.revoke(workerId);
        }
        mvc.perform(get("/api/agents/usage").header("Authorization",owner.authorization())).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/agents/usage").header("Authorization",member.authorization())).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].totalTokens").value(50)).andExpect(jsonPath("$[0].reportedCalls").value(2));
    }
}
