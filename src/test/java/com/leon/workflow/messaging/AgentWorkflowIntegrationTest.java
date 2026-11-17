package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.automation.*;
import com.leon.workflow.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import java.util.concurrent.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:agent-workflow-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class AgentWorkflowIntegrationTest {
    @Autowired AgentRegistry agents; @Autowired AgentChatService chat; @Autowired DeviceMessageStore messages;
    @Autowired WorkflowAppMessage app; @Autowired WorkflowEngine engine; @Autowired WorkflowModels models;
    @Autowired WorkflowCredentials credentials; @Autowired WorkflowStore workflows; @Autowired JdbcTemplate jdbc;
    @Autowired AppSettingService settings; @Autowired BrowserSessionService sessions; @Autowired ApiTokenInterceptor auth;
    @Autowired WebApplicationContext context;
    @MockitoBean AgentModelClient client;
    static final String KEY="saved-profile-test-key", AUTH="Bearer integration-test-token";
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM chat_agent_job");jdbc.update("DELETE FROM device_message");jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");jdbc.update("DELETE FROM chat_agent");
        settings.saveSetting(AppSettingService.DEEPSEEK_API_KEY,Map.of("value",KEY));
    }
    AgentRegistry.Agent agent(String handle) { return agents.save(null,new AgentRegistry.Edit(handle,handle,"deepseek","deepseek-flash","角色",true,KEY,null)); }
    AgentRegistry.Edit profileEdit(String handle,String id,Long version) { return new AgentRegistry.Edit(handle,handle,null,null,"提示词",true,null,version,"alfred",id); }
    Map<String,Object> profile(String provider,String model,String credential) {
        var body=new LinkedHashMap<String,Object>(Map.of("name","已保存模型","provider",provider,"model",model));
        if(credential!=null)body.put("credentialId",credential); return models.save(null,body);
    }
    Map<String,Object> input(String text) { return Map.of("items",List.of(Map.of("json",Map.of("summary",text)))); }
    Map<String,Object> config(AgentRegistry.Agent agent) { return new LinkedHashMap<>(Map.of("agentId",agent.id(),"targetType","direct","text","{{ input.items[0].json.summary }}")); }
    Map<String,Object> ctx() { return Map.of("runId",UUID.randomUUID().toString(),"nodeId","notify"); }
    WorkflowGraph.Node node(Map<String,Object> config) { return new WorkflowGraph.Node("notify","app-message","通知",config,Map.of("x",300,"y",100)); }

    @Test void savedOpenaiModelUsesCurrentSharedProfileAndCredentialsWithoutCopyingKeys() {
        String credential=text(credentials.create(Map.of("kind","openai","name","测试凭据","value",Map.of("apiKey",KEY))).get("id"));
        String id=text(profile("openai","model-v1",credential).get("id")); var a=agents.save(null,profileEdit("writer",id,null));
        assertEquals(id,a.modelId()); assertEquals("",jdbc.queryForObject("SELECT encrypted_key FROM chat_agent WHERE id=?",String.class,a.id()));
        var runtime=agents.runtime(a.id());assertEquals("openai",runtime.agent.provider());assertEquals("model-v1",runtime.agent.model());assertEquals(KEY,runtime.key);
        credentials.update(credential,Map.of("name","轮换","kind","openai","value",Map.of("apiKey","replacement-key")));
        models.save(id,Map.of("name","改名","provider","openai","model","model-v2","credentialId",credential));
        runtime=agents.runtime(a.id());assertEquals("model-v2",runtime.agent.model());assertEquals("replacement-key",runtime.key);
        models.save(id,Map.of("name","停用","provider","openai","model","model-v2","credentialId",credential,"enabled",false));
        assertThrows(IllegalArgumentException.class,()->agents.runtime(a.id()));
        assertThrows(IllegalArgumentException.class,()->agents.save(null,profileEdit("invalid",id,null)));
        assertThrows(IllegalArgumentException.class,()->agents.save(null,profileEdit("missing",UUID.randomUUID().toString(),null)));
        verifyNoInteractions(client);
    }
    @Test void legacyAgentSurvivesAndCanSwitchToSavedDeepseekModel() {
        var old=agent("legacy");assertEquals(KEY,agents.apiKey(old.id()));
        var preserved=agents.save(old.id(),new AgentRegistry.Edit(old.name(),old.handle(),null,null,old.systemPrompt(),true,null,old.version(),old.avatarId(),null));
        assertNull(preserved.modelId()); assertEquals(KEY,agents.apiKey(old.id()));
        String id=text(profile("deepseek","deepseek-chat",null).get("id"));
        var shared=agents.save(old.id(),profileEdit("legacy",id,preserved.version()));
        assertEquals("deepseek-chat",shared.model());assertEquals(KEY,agents.runtime(old.id()).key);
        settings.saveSetting(AppSettingService.DEEPSEEK_API_KEY,Map.of("value","updated-deepseek-key"));assertEquals("updated-deepseek-key",agents.runtime(old.id()).key);
        assertEquals("",jdbc.queryForObject("SELECT encrypted_key FROM chat_agent WHERE id=?",String.class,old.id()));
    }
    @Test void adminDirectoryRequiresCookieAndExposesNeitherKeysNorPromptsToTargets() throws Exception {
        agent("writer"); MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).build();
        Cookie cookie=new Cookie(BrowserSessionService.COOKIE_NAME,sessions.create(false,auth.fingerprint()));
        mvc.perform(get("/api/workflows/app-message-targets").header("Authorization",AUTH)).andExpect(status().isForbidden());
        String response=mvc.perform(get("/api/workflows/app-message-targets").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.agents[0].systemPrompt").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(KEY));
        response=mvc.perform(get("/api/agent-admin").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.models").isArray()).andReturn().getResponse().getContentAsString();assertFalse(response.contains(KEY));
        mvc.perform(post("/api/workflows/test-node").cookie(cookie).header("Origin","https://evil.test").contentType("application/json").content(write(Map.of("node",node(config(agents.list().getFirst())),"input",input("不能发送"))))).andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message",Integer.class));
    }
    @Test void debugNotificationAppearsInAgentPrivateHistoryWithoutModelJobsAndPassesInputThrough() throws Exception {
        var a=agent("notifier"); var input=input("汇总 😀\n第二行"); var result=engine.test(node(config(a)),input);
        assertEquals(input,result.get("output"));var page=messages.page("direct:"+a.id(),null,null,100);assertEquals(1,page.messages().size());
        var message=page.messages().getFirst();assertEquals("assistant",message.role());assertEquals(a.id(),message.agentId());assertEquals(a.name(),message.sender());assertEquals("汇总 😀\n第二行",message.text());assertNull(message.replyTo());assertTrue(page.jobs().isEmpty());
        assertTrue(messages.page(null,null,100).messages().isEmpty());assertFalse(text(result.get("logs")).contains("第二行"));verifyNoInteractions(client);
        MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/device-messages").param("conversationId","direct:"+a.id()).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].role").value("assistant"));
    }
    @Test void groupMembershipDisabledAgentsAndInvalidContentAreRejectedBeforeSaving() throws Exception {
        var a=agent("member");var b=agent("outsider");var group=chat.group("通知群",List.of(a.id()));var c=config(a);c.put("targetType","group");c.put("conversationId",group.id());
        app.execute(c,input("群通知"),ctx(),new WorkflowPrivacy(),s->{});assertEquals(1,messages.page(group.id(),null,null,100).messages().size());
        c.put("agentId",b.id());assertThrows(Exception.class,()->app.execute(c,input("禁止"),ctx(),new WorkflowPrivacy(),s->{}));
        assertThrows(Exception.class,()->chat.publishAgentMessage(b.id(),chat.direct(a.id()).id(),"禁止",UUID.randomUUID().toString()));
        var direct=config(a);
        for(String invalid:List.of(""," ","x".repeat(8001),"a\0b"))assertThrows(Exception.class,()->app.execute(direct,input(invalid),ctx(),new WorkflowPrivacy(),s->{}));
        direct.put("text","{{ input.items[0].json.missing }}");assertThrows(Exception.class,()->engine.test(node(direct),input("x")));
        agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),a.provider(),a.model(),a.systemPrompt(),false,null,a.version()));
        assertThrows(Exception.class,()->app.execute(config(a),input("禁止"),ctx(),new WorkflowPrivacy(),s->{}));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM device_message",Integer.class));verifyNoInteractions(client);
    }
    @Test void emptyInputsSkipAndSameRunNodeIsIdempotentEvenWithConcurrentDelivery() throws Exception {
        var a=agent("dedup");var c=config(a);var input=input("只发一次 {{ untouched }}");var ctx=ctx();
        engine.test(node(c),Map.of("items",List.of()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_message",Integer.class));
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->{try{app.execute(c,input,ctx,new WorkflowPrivacy(),s->{});}catch(Exception e){throw new RuntimeException(e);}}));for(var job:jobs)job.get(5,TimeUnit.SECONDS);
        }
        var page=messages.page("direct:"+a.id(),null,null,100);assertEquals(1,page.messages().size());assertEquals("只发一次 {{ untouched }}",page.messages().getFirst().text());
        assertThrows(Exception.class,()->app.execute(c,input("不同内容"),ctx,new WorkflowPrivacy(),s->{}));
        app.execute(c,input,ctx(),new WorkflowPrivacy(),s->{});assertEquals(2,messages.page("direct:"+a.id(),null,null,100).messages().size());
        Thread.currentThread().interrupt();try{assertThrows(InterruptedException.class,()->app.execute(c,input,ctx(),new WorkflowPrivacy(),s->{}));}finally{Thread.interrupted();}
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM device_message",Integer.class));verifyNoInteractions(client);
    }
    @Test void publishedWorkflowRunsAgentNotificationAndPreservesUpstreamOutput() throws Exception {
        var a=agent("workflow");var graph=new WorkflowGraph(List.of(new WorkflowGraph.Node("start","start","开始",Map.of(),Map.of()),node(config(a)),new WorkflowGraph.Node("end","end","结束",Map.of(),Map.of())),List.of(new WorkflowGraph.Edge("a","start","notify",""),new WorkflowGraph.Edge("b","notify","end","")));
        var wf=workflows.create("通知流程");String id=text(wf.get("id"));wf=workflows.save(id,"通知流程",graph,((Number)wf.get("revision")).longValue());workflows.publish(id,((Number)wf.get("revision")).longValue(),write(engine.runtimes()));
        String runId=text(engine.start(id,input("正式执行" )).get("id"));Map<String,Object> run=null;
        for(int i=0;i<150;i++){run=workflows.run(runId);if(!Set.of("QUEUED","RUNNING").contains(run.get("status")))break;Thread.sleep(20);}
        assertEquals("SUCCEEDED",run.get("status"),write(run));assertEquals("正式执行",messages.page("direct:"+a.id(),null,null,100).messages().getFirst().text());verifyNoInteractions(client);
    }
}
