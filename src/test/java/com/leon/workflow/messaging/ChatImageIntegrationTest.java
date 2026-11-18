package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.automation.WorkflowModels;
import com.leon.workflow.ai.OpenAiExecutor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:chat-images-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ChatImageIntegrationTest {
    private static final String AUTH="Bearer integration-test-token", KEY="test-only-model-key";
    @Autowired WebApplicationContext context;@Autowired ChatImageService images;@Autowired DeviceMessageStore store;
    @Autowired AgentRegistry agents;@Autowired AgentChatService chat;@Autowired AgentReplyEngine engine;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @MockitoBean AgentModelClient model;
    MockMvc mvc(){return MockMvcBuilders.webAppContextSetup(context).build();}
    @BeforeEach void clean(){for(String table:List.of("chat_context_snapshot","chat_context_summary","chat_context_policy","chat_agent_job","device_message","chat_message_image","chat_agent"))jdbc.update("DELETE FROM "+table);jdbc.update("DELETE FROM chat_conversation WHERE id<>'shared'");}
    static byte[] png(int width,int height) throws Exception {var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB),"png",output);return output.toByteArray();}
    AgentRegistry.Agent agent(String handle,String provider){return agents.save(null,new AgentRegistry.Edit(handle,handle,provider,provider.equals("openai")?"gpt-4o":"deepseek-chat","",true,KEY,null));}
    DeviceMessageStore.Send input(String room,String text,List<String> ids){return new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos",text,room,List.of(),ids);}

    @Test void uploadsAreAuthenticatedBoundedNormalizedAndIdempotent() throws Exception {
        String id=UUID.randomUUID().toString();byte[] input=png(2000,1000);
        mvc().perform(post("/api/device-messages/images").param("id",id).param("conversationId","shared").contentType("image/png").content(input)).andExpect(status().isUnauthorized());
        mvc().perform(post("/api/device-messages/images").header("Authorization",AUTH).param("id",id).param("conversationId","shared").contentType("image/png").content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.width").value(1600)).andExpect(jsonPath("$.height").value(800)).andExpect(jsonPath("$.mimeType").value("image/jpeg"));
        mvc().perform(get("/api/device-messages/images/"+id)).andExpect(status().isUnauthorized());
        byte[] bytes=mvc().perform(get("/api/device-messages/images/"+id).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        assertEquals(1600,ImageIO.read(new ByteArrayInputStream(bytes)).getWidth());assertTrue(bytes.length<=ChatImageService.MAX_STORED);
        assertEquals(images.metadata(id),images.upload(id,"shared",input));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image",Integer.class));
        assertThrows(Exception.class,()->images.upload(id,"shared",png(3,2)));
        mvc().perform(post("/api/device-messages/images").header("Authorization",AUTH).param("id",UUID.randomUUID().toString()).param("conversationId","shared").content(new byte[ChatImageService.MAX_UPLOAD+1])).andExpect(status().isPayloadTooLarge());
        assertThrows(Exception.class,()->images.upload(UUID.randomUUID().toString(),"shared","not an image".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void imageOnlyMessagesKeepHistoryAndRetrySnapshotsWithoutCrossConversationReuse() throws Exception {
        var agent=agent("vision","openai");String room=chat.direct(agent.id()).id();String id=UUID.randomUUID().toString();
        images.upload(id,room,png(20,10));var input=input(room,"",List.of(id));var sent=chat.send(input);
        assertEquals(sent.id(),chat.send(input).id());assertEquals(id,store.page(room,null,null,100).messages().getFirst().images().getFirst().id());
        when(model.complete(any(),anyString(),anyList())).thenThrow(new AgentModelClient.Failure("temporary"));
        assertTrue(engine.runNext());
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(model).complete(any(),eq(KEY),captor.capture());
        assertTrue(captor.getValue().toString().contains("imageIds="+id));
        store.retryJob(sent.id(),agent.id());doReturn("图片包含一个矩形").when(model).complete(any(),anyString(),anyList());assertTrue(engine.runNext());
        verify(model,times(2)).complete(any(),eq(KEY),eq(captor.getValue()));
        assertEquals(2,store.page(room,null,null,100).messages().size());
        assertThrows(Exception.class,()->chat.send(input("shared","错误会话",List.of(id))));
        assertThrows(Exception.class,()->chat.send(input(room,"缺少附件",List.of(UUID.randomUUID().toString()))));
        assertThrows(Exception.class,()->chat.send(input(room,"",List.of(id,id))));
        var changed=new DeviceMessageStore.Send(input.clientMessageId(),input.deviceId(),input.sender(),input.platform(),"changed",room,List.of(),List.of(id));
        assertThrows(Exception.class,()->chat.send(changed));
        mvc().perform(get("/api/device-messages").param("conversationId",room).header("Authorization",AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].images[0].id").value(id));
    }
    @Test void unsupportedProvidersAreRejectedAndOfficialRequestContainsActualImageBytes() throws Exception {
        var deepseek=agent("textonly","deepseek");String room=chat.direct(deepseek.id()).id();String id=UUID.randomUUID().toString();images.upload(id,room,png(20,10));
        assertThrows(Exception.class,()->chat.send(input(room,"看图",List.of(id))));
        var messages=List.of(ChatImageService.message("user","看图",List.of(images.metadata(id))));
        assertThrows(AgentModelClient.Failure.class,()->images.modelMessages(messages,false));
        HttpClient http=mock(HttpClient.class);HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"图片回答\"}}]}".getBytes(StandardCharsets.UTF_8));
        var client=new OfficialAgentModelClient(json,http,mock(WorkflowModels.class),mock(OpenAiExecutor.class),images);
        assertEquals("图片回答",client.complete(agent("vision","openai"),KEY,messages));
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        var payload=json.readTree(body(request.getValue()));var content=payload.path("messages").path(0).path("content");
        assertEquals("看图",content.path(0).path("text").asText());assertEquals("image_url",content.path(1).path("type").asText());
        String url=content.path(1).path("image_url").path("url").asText();assertTrue(url.startsWith("data:image/jpeg;base64,"));
        assertArrayEquals(images.bytes(id),Base64.getDecoder().decode(url.substring(url.indexOf(',')+1)));assertFalse(body(request.getValue()).contains("imageIds"));
        when(response.statusCode()).thenReturn(400);
        var unsupported=assertThrows(AgentModelClient.Failure.class,()->client.complete(agent("vision2","openai"),KEY,messages));
        assertTrue(unsupported.getMessage().contains("支持图片输入"));
        var history=new ArrayList<Map<String,String>>();for(int i=0;i<10;i++)history.add(messages.getFirst());
        var bounded=json.readTree(json.writeValueAsString(images.modelMessages(history,true)));int count=0;
        for(var message:bounded)for(var part:message.path("content"))if(part.path("type").asText().equals("image_url"))count++;
        assertEquals(8,count);
    }
    private static String body(HttpRequest request){
        var result=new CompletableFuture<String>();var bytes=new ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}public void onNext(ByteBuffer buffer){byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}public void onError(Throwable error){result.completeExceptionally(error);}public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return result.join();
    }
}
