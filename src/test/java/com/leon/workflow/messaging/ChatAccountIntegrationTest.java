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
