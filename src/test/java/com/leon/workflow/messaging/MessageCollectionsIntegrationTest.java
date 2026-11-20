package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:message-collections;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MessageCollectionsIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired ChatBackupService backups;
    @Autowired DeviceMessageStore store;
    MockMvc mvc;
    record User(String name,String id,String token){}
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).build();}
    User user()throws Exception{return user("Collect"+UUID.randomUUID().toString().substring(0,8));}
    User user(String name)throws Exception{
        var response=mvc.perform(post("/api/conversations/account/login").header("Authorization","Bearer integration-test-token").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username",name)))).andExpect(status().isOk()).andReturn().getResponse();
        var node=json.readTree(response.getContentAsByteArray());return new User(name,node.path("user").path("id").asText(),"Bearer "+node.path("sessionToken").asText());
    }
    Map<String,Object> sendBody(String room,String text,Long quote,List<String> images){
        var body=new HashMap<String,Object>();body.put("clientMessageId",UUID.randomUUID().toString());body.put("deviceId",UUID.randomUUID().toString());
        body.put("sender","spoofed");body.put("platform","ios");body.put("text",text);body.put("conversationId",room);body.put("imageIds",images);if(quote!=null)body.put("quoteMessageId",quote);return body;
    }
    long send(User user,Map<String,Object> body)throws Exception{
        var response=mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("id").asLong();
    }
    long favorite(User user,long message)throws Exception{
        var response=mvc.perform(post("/api/device-messages/"+message+"/favorite").header("Authorization",user.token())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("id").asLong();
    }
    @Test void quoteSnapshotUsesServerContentAndRetryRemainsIdempotent()throws Exception{
        var user=user();long original=send(user,sendBody("shared","服务器原文",null,List.of()));
        var body=sendBody("shared","请解释这一句",original,List.of());body.put("quote",Map.of("text","伪造原文","sender","伪造姓名"));
        long reply=send(user,body);assertEquals(reply,send(user,body));
        mvc.perform(get("/api/device-messages").header("Authorization",user.token())).andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[1].quote.id").value(original)).andExpect(jsonPath("$.messages[1].quote.text").value("服务器原文"))
            .andExpect(jsonPath("$.messages[1].quote.sender").value(user.name())).andExpect(jsonPath("$.messages[1].replyTo").isEmpty());
        assertTrue(store.message(reply).modelText().contains("服务器原文"));
        body.put("quoteMessageId",reply);mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
    }
    @Test void crossConversationReferencesAndPrivateFavoritesAreRejected()throws Exception{
        var alice=user();var bob=user();long message=send(alice,sendBody("shared","私有消息",null,List.of()));long favorite=favorite(alice,message);
        mvc.perform(post("/api/device-messages").header("Authorization",bob.token()).contentType("application/json").content(json.writeValueAsString(sendBody("shared","越权引用",message,List.of())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/device-messages/"+message+"/favorite").header("Authorization",bob.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",bob.token())).andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(0));
        mvc.perform(post("/api/device-messages/favorites/"+favorite+"/remove").header("Authorization",bob.token())).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",alice.token())).andExpect(jsonPath("$.results.length()").value(1));
        mvc.perform(get("/api/device-messages/favorites")).andExpect(status().isUnauthorized());
    }
    @Test void favoritesDeduplicatePersistAcrossSessionsAndSearchWithStablePagination()throws Exception{
        var user=user();long first=send(user,sendBody("shared","关键词 %_",null,List.of()));long id=favorite(user,first);assertEquals(id,favorite(user,first));
        for(int i=0;i<4;i++)favorite(user,send(user,sendBody("shared","关键词 "+i,null,List.of())));
        var second=user(user.name());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",second.token()).param("q","%_"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.results[0].id").value(id));
        var found=new ArrayList<Long>();Long before=null;
        do{var request=get("/api/device-messages/favorites").header("Authorization",user.token()).param("q","关键词").param("limit","2");if(before!=null)request.param("before",""+before);
            var result=json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            for(var row:result.path("results"))found.add(row.path("id").asLong());before=result.path("hasMore").asBoolean()?result.path("nextBefore").asLong():null;
        }while(before!=null);
        assertEquals(5,found.size());assertEquals(5,new HashSet<>(found).size());assertEquals(id,found.getLast());
        for(int i=0;i<2;i++)mvc.perform(post("/api/device-messages/favorites/"+id+"/remove").header("Authorization",user.token())).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",user.token()).param("q","%_")).andExpect(jsonPath("$.results.length()").value(0));
    }
    @Test void favoriteImageSurvivesSourceClearingAndHasIndependentOwnerAuthorization()throws Exception{
        var user=user();var outsider=user();String image=UUID.randomUUID().toString();var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"jpeg",output);
        mvc.perform(post("/api/device-messages/images").header("Authorization",user.token()).param("id",image).param("conversationId","shared").contentType("image/jpeg").content(output.toByteArray())).andExpect(status().isOk());
        long original=send(user,sendBody("shared","已收藏图片",null,List.of(image))),id=favorite(user,original);
        String imageUrl="/api/device-messages/favorites/"+id+"/images/"+image;
        byte[] saved=mvc.perform(get(imageUrl).header("Authorization",user.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get(imageUrl).header("Authorization",outsider.token())).andExpect(status().isNotFound());
        var preview=json.readTree(mvc.perform(get("/api/conversations/context/clear-preview").header("Authorization",user.token()).param("conversationId","shared")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(post("/api/conversations/context/clear").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(Map.of("conversationId","shared","requestId",UUID.randomUUID().toString(),"expectedLatestMessageId",preview.path("latestMessageId").asLong(),"expectedHistoryRevision",preview.path("historyRevision").asLong(),"confirm",true)))).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages/images/"+image).header("Authorization",user.token())).andExpect(status().isNotFound());
        assertArrayEquals(saved,mvc.perform(get(imageUrl).header("Authorization",user.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",user.token())).andExpect(jsonPath("$.results[0].message.text").value("已收藏图片"));
        mvc.perform(post("/api/device-messages/favorites/"+id+"/remove").header("Authorization",user.token())).andExpect(status().isOk());
        mvc.perform(get(imageUrl).header("Authorization",user.token())).andExpect(status().isNotFound());
    }
    @Test void backupRestoreRemapsQuoteIdsAndPreservesSnapshot()throws Exception{
        var user=user();long source=send(user,sendBody("shared","要保留的原文",null,List.of()));send(user,sendBody("shared","带引用的回复",source,List.of()));
        byte[] backup=backups.export("home:"+user.id());
        var restored=backups.restore(UUID.randomUUID().toString(),null,backup,user.id(),false);var page=store.page(restored.id(),null,null,100);
        assertEquals(2,page.messages().size());assertEquals(page.messages().getFirst().id(),page.messages().getLast().quote().id());assertNotEquals(source,page.messages().getLast().quote().id());
        assertEquals("要保留的原文",page.messages().getLast().quote().text());
    }
}
