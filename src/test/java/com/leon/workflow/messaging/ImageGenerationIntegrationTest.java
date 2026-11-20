package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.ai.OpenAiExecutor;
import com.leon.workflow.automation.WorkflowModels;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:image-generation-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ImageGenerationIntegrationTest {
    static final String KEY="image-test-secret",AUTH="Bearer integration-test-token";
    @Autowired WebApplicationContext context;@Autowired AgentRegistry agents;@Autowired AgentChatService chat;
    @Autowired DeviceMessageStore store;@Autowired AgentReplyEngine engine;@Autowired ChatImageService images;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired WorkflowModels usage;
    @MockitoBean AgentModelClient model;
    @BeforeEach void clean(){
        for(String table:List.of("chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_agent","chat_user_model_usage","automation_model_usage"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");
    }
    AgentRegistry.Agent agent(){return agents.save(null,new AgentRegistry.Edit("Sol","sol","openai","gpt-6.1-sol","私有提示词",true,KEY,null,null,null,"聊天和绘图",true,AgentRegistry.DEFAULT_IMAGE_MODEL));}
    DeviceMessageStore.Message send(String room,String text){return chat.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos",text,room,List.of()));}
    Map<String,Object> imageResponse() throws Exception {
        return Map.of("status","completed","usage",Map.of("input_tokens",20,"output_tokens",5,"total_tokens",25),"output",List.of(Map.of("type","image_generation_call","status","completed","result",Base64.getEncoder().encodeToString(ChatImageIntegrationTest.png(2000,1000)))));
    }
    @Test void sameAgentChatsGeneratesAndEditsWithPersistedAuthenticatedAttachments() throws Exception {
        var a=agent();String room=chat.direct(a.id()).id();
        var http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(json.writeValueAsBytes(imageResponse()));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client=new OfficialAgentModelClient(json,http,usage,mock(OpenAiExecutor.class),images);
        when(model.reply(any(),anyString(),anyList())).thenAnswer(call->client.reply(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        var question=send(room,"画一张月夜竹林");assertTrue(engine.runNext());
        var saved=store.page(room,null,null,100).messages().getLast();
        assertEquals("assistant",saved.role());assertEquals(a.id(),saved.agentId());assertEquals(question.id(),saved.replyTo());
        assertEquals("已生成图片。",saved.text());assertEquals(1,saved.images().size());
        String id=saved.images().getFirst().id();assertEquals(1600,images.metadata(id).width());assertEquals("image/jpeg",images.metadata(id).mimeType());
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/device-messages/images/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/device-messages/images/"+id).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/api/device-messages").header("Authorization",AUTH).param("conversationId",room)).andExpect(status().isOk()).andExpect(jsonPath("$.messages[1].images[0].id").value(id));
        // Continue with text using the very same Agent and its saved generated image in context.
        when(response.body()).thenReturn(json.writeValueAsBytes(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","可以把月亮改大。")))))));
        send(room,"你觉得月亮应该多大？");assertTrue(engine.runNext());
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(2)).send(request.capture(),any(HttpResponse.BodyHandler.class));
        for(var value:request.getAllValues())assertEquals("https://api.openai.com/v1/responses",value.uri().toString());
        var second=json.readTree(OfficialAgentModelClientTest.requestBody(request.getAllValues().getLast()));
        assertEquals("auto",second.path("tool_choice").asText());assertFalse(second.path("store").asBoolean(true));assertEquals(1,second.path("max_tool_calls").asInt());
        assertEquals(AgentRegistry.DEFAULT_IMAGE_MODEL,second.path("tools").path(0).path("model").asText());
        boolean reference=false;
        for(var item:second.path("input"))for(var part:item.path("content"))if(part.path("type").asText().equals("input_image")){
            assertEquals("user",item.path("role").asText());
            assertTrue(part.path("image_url").asText().startsWith("data:image/jpeg;base64,"));reference=true;
        }
        assertTrue(reference);assertTrue(store.page(room,null,null,100).messages().getLast().images().isEmpty());
        when(response.body()).thenReturn(json.writeValueAsBytes(imageResponse()));send(room,"把刚才那张图的月亮改大");assertTrue(engine.runNext());
        assertEquals(1,store.page(room,null,null,100).messages().getLast().images().size());
        verify(model,never()).complete(any(),anyString(),anyList());
        assertTrue(jdbc.queryForObject("SELECT total_tokens FROM automation_model_usage WHERE model='gpt-6.1-sol'",Long.class)>=50);
        assertEquals(2,jdbc.queryForObject("SELECT unreported_calls FROM automation_model_usage WHERE model=?",Integer.class,AgentRegistry.DEFAULT_IMAGE_MODEL));
    }
    @Test void attachmentFailureRollsBackWholeReplyAndRetryAttachesExactlyOnce() throws Exception {
        var a=agent();String room=chat.direct(a.id()).id();var question=send(room,"画两张图");
        when(model.reply(any(),anyString(),anyList())).thenReturn(new AgentModelClient.Reply("图片",List.of(ChatImageIntegrationTest.png(20,10),new byte[]{1,2,3})));
        assertTrue(engine.runNext());assertEquals(1,store.page(room,null,null,100).messages().size());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image",Integer.class));
        assertEquals("FAILED",store.page(room,null,null,100).jobs().getFirst().status());
        store.retryJob(question.id(),a.id());when(model.reply(any(),anyString(),anyList())).thenReturn(new AgentModelClient.Reply("完成",List.of(ChatImageIntegrationTest.png(20,10))));
        assertTrue(engine.runNext());assertEquals(2,store.page(room,null,null,100).messages().size());
        images.completeGenerated(new DeviceMessageStore.Work(question.id(),a.id(),room),a,"重复完成",List.of(ChatImageIntegrationTest.png(20,10)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image",Integer.class));assertEquals(2,store.page(room,null,null,100).messages().size());
    }
    @Test void configIsOptInPreservedByOlderClientsAndRejectsUnsupportedProvider() {
        var a=agent();assertTrue(agents.runtime(a.id()).agent.imageGenerationEnabled());
        var saved=agents.save(a.id(),new AgentRegistry.Edit(a.name(),a.handle(),a.provider(),a.model(),a.systemPrompt(),true,null,a.version()));
        assertTrue(saved.imageGenerationEnabled());assertEquals(AgentRegistry.DEFAULT_IMAGE_MODEL,saved.imageModel());
        assertThrows(Exception.class,()->agents.save(null,new AgentRegistry.Edit("Deep","deep","deepseek","deepseek-chat","",true,KEY,null,null,null,"",true,AgentRegistry.DEFAULT_IMAGE_MODEL)));
        assertThrows(Exception.class,()->agents.save(null,new AgentRegistry.Edit("Bad","bad","openai","gpt-6.1-sol","",true,KEY,null,null,null,"",true,"https://invalid.test/model")));
        var plain=agents.save(null,new AgentRegistry.Edit("Text","text","openai","gpt-6.1-sol","",true,KEY,null));assertFalse(plain.imageGenerationEnabled());
    }
}
