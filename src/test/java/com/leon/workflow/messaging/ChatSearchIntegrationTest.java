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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:chat-search;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop","leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false",
    "leon.chat.auto-title-enabled=false","leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class ChatSearchIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    record Account(String token,String home) {}
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).build();}
    Account account()throws Exception{
        var result=mvc.perform(post("/api/conversations/account/login").header("Authorization","Bearer integration-test-token")
            .contentType("application/json").content(json.writeValueAsString(Map.of("username","Search"+UUID.randomUUID().toString().substring(0,8)))))
            .andExpect(status().isOk()).andReturn().getResponse();
        var value=json.readTree(result.getContentAsByteArray());
        return new Account("Bearer "+value.path("sessionToken").asText(),"home:"+value.path("user").path("id").asText());
    }
    long send(Account user,String room,String text)throws Exception{
        var body=new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Search","ios",text,room,List.of());
        return json.readTree(mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json")
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
    }
    @Test void globalSearchIsAuthenticatedAndNeverLeaksOtherAccounts()throws Exception{
        var alice=account();var bob=account();long id=send(alice,"shared","中文 搜索 Needle");send(bob,"shared","中文 搜索 Secret");
        mvc.perform(get("/api/device-messages/search").param("q","搜索")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/device-messages/search").header("Authorization",alice.token()).param("q","搜索"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.results[0].message.id").value(id));
        mvc.perform(get("/api/device-messages/search").header("Authorization",bob.token()).param("q","搜索").param("conversationId",alice.home())).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/context").header("Authorization",bob.token()).param("conversationId",alice.home()).param("messageId",""+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/search").header("Authorization",alice.token()).param("q","needle").param("conversationId","shared"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1));
    }
    @Test void literalWildcardsAndInvalidSearchInputs()throws Exception{
        var user=account();send(user,"shared","100%_完成 ' OR 1=1 --");send(user,"shared","普通内容");
        for(String q:List.of("%_","' OR 1=1 --"))mvc.perform(get("/api/device-messages/search").header("Authorization",user.token()).param("q",q))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1));
        for(String q:List.of("","   ","x".repeat(101)))mvc.perform(get("/api/device-messages/search").header("Authorization",user.token()).param("q",q)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/device-messages/search").header("Authorization",user.token()).param("q","普通").param("limit","51")).andExpect(status().isBadRequest());
    }
    @Test void searchesBeyondRecentHundredWithStableDescendingPagination()throws Exception{
        var user=account();var ids=new ArrayList<Long>();for(int i=0;i<105;i++)ids.add(send(user,"shared","分页 hit "+i));
        Long before=null;var found=new ArrayList<Long>();
        do{
            var request=get("/api/device-messages/search").header("Authorization",user.token()).param("q","hit").param("limit","25");
            if(before!=null)request.param("before",""+before);
            var value=json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            for(var row:value.path("results"))found.add(row.path("message").path("id").asLong());
            before=value.path("hasMore").asBoolean()?value.path("nextBefore").asLong():null;
        }while(before!=null);
        Collections.reverse(ids);assertEquals(ids,found);
    }
    @Test void contextAnchorsAndPagesBothDirectionsAndRejectsDeletedOrWrongRoom()throws Exception{
        var user=account();var ids=new ArrayList<Long>();for(int i=0;i<9;i++)ids.add(send(user,"shared","上下文 "+i));long anchor=ids.get(4);
        mvc.perform(get("/api/device-messages/context").header("Authorization",user.token()).param("conversationId","shared").param("messageId",""+anchor).param("limit","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(5)).andExpect(jsonPath("$.messages[2].id").value(anchor))
            .andExpect(jsonPath("$.hasOlder").value(true)).andExpect(jsonPath("$.hasNewer").value(true));
        for(String direction:List.of("before","after")){
            var value=json.readTree(mvc.perform(get("/api/device-messages/context").header("Authorization",user.token()).param("conversationId","shared")
                .param("messageId",""+anchor).param("direction",direction).param("limit","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals(2,value.path("messages").size());for(var row:value.path("messages"))assertTrue(direction.equals("before")?row.path("id").asLong()<anchor:row.path("id").asLong()>anchor);
        }
        mvc.perform(get("/api/device-messages/context").header("Authorization",user.token()).param("conversationId","shared").param("messageId","9223372036854775807")).andExpect(status().isNotFound());
    }
    @Test void sharedGroupIsSearchableByMembersOnlyAndRoomFilterExcludesPrivateMatches()throws Exception{
        var owner=account();var member=account();var outsider=account();
        // Membership uses UserName, so resolve the member from its authenticated session.
        var me=json.readTree(mvc.perform(get("/api/conversations/account/me").header("Authorization",member.token())).andReturn().getResponse().getContentAsByteArray());
        var result=mvc.perform(post("/api/conversations/groups").header("Authorization",owner.token()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("name","搜索群","usernames",List.of(me.path("username").asText()))))).andExpect(status().isOk()).andReturn().getResponse();
        String room=json.readTree(result.getContentAsByteArray()).path("id").asText();
        send(owner,"shared","共同关键词 私人");long shared=send(member,room,"共同关键词 群聊");
        mvc.perform(get("/api/device-messages/search").header("Authorization",member.token()).param("q","共同关键词"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.results[0].message.id").value(shared));
        mvc.perform(get("/api/device-messages/search").header("Authorization",owner.token()).param("q","共同关键词").param("conversationId",room))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1));
        mvc.perform(get("/api/device-messages/search").header("Authorization",outsider.token()).param("q","共同关键词"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(0));
    }
}
