package com.leon.workflow;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.dao.repository.LlmMessageRepository;
import com.leon.workflow.dao.repository.WebCollectionItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:plugintest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class PluginIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired WebCollectionItemRepository items;
    @Autowired LlmMessageRepository messages;
    @Autowired com.leon.workflow.service.WebCollectionService collectionService;

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }
    private JSONObject data(MvcResult result) throws Exception {
        JSONObject envelope = JSON.parseObject(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(envelope.getBooleanValue("success"));
        return envelope.getJSONObject("data");
    }
    private MvcResult postJson(String path, String body) throws Exception {
        return mvc().perform(post(path).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
    }
    private MvcResult putJson(String path, String body) throws Exception {
        return mvc().perform(put(path).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
    }
    private MvcResult getData(String path) throws Exception {
        return mvc().perform(get(path)).andExpect(status().isOk()).andReturn();
    }

    @Test void pluginWorkflowAndPersistence() throws Exception {
        JSONObject collection = data(postJson("/api/web-collections", "{\"name\":\"测试集合\"}"));
        long collectionId = collection.getLongValue("id");
        long defaultGroup = collection.getJSONArray("groups").getJSONObject(0).getLongValue("id");
        long group = data(postJson("/api/web-collections/" + collectionId + "/groups", "{\"name\":\"其他\"}")).getLongValue("id");
        long item = data(postJson("/api/web-collections/" + collectionId + "/items", "{\"groupId\":" + defaultGroup + ",\"name\":\"网站\",\"url\":\"example.com\"}")).getLongValue("id");
        putJson("/api/web-collections/" + collectionId + "/items/" + item, "{\"groupId\":" + group + ",\"name\":\"网站\",\"url\":\"example.com\"}");
        putJson("/api/web-collections/" + collectionId + "/groups/reorder", "{\"groupIds\":[" + group + "," + defaultGroup + "]}");
        putJson("/api/web-collections/" + collectionId + "/groups/" + group + "/items/reorder", "{\"itemIds\":[" + item + "]}");
        putJson("/api/web-collections/reorder", "{\"collectionIds\":[" + collectionId + "]}");
        JSONArray collections = JSON.parseObject(getData("/api/web-collections/full").getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
        assertEquals(item, collections.getJSONObject(0).getJSONArray("groups").getJSONObject(0).getJSONArray("items").getJSONObject(0).getLongValue("id"));
        mvc().perform(delete("/api/web-collections/" + collectionId)).andExpect(status().isOk());
        assertTrue(items.findById(item).orElseThrow().getDeleted());

        long note = data(postJson("/api/notes", "{\"content\":\"便签\"}")).getLongValue("id");
        mvc().perform(delete("/api/notes/" + note)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        putJson("/api/notes/" + note, "{\"archived\":true}");
        mvc().perform(delete("/api/notes/" + note)).andExpect(status().isOk());
        long todo = data(postJson("/api/todos", "{\"content\":\"待办\"}")).getLongValue("id");
        assertTrue(data(putJson("/api/todos/" + todo, "{\"done\":true}")).getBooleanValue("done"));
        mvc().perform(delete("/api/todos/" + todo)).andExpect(status().isOk());
        long tool = data(postJson("/api/toolbox", "{\"name\":\"工具\",\"url\":\"example.com\"}")).getLongValue("id");
        assertEquals(tool, data(postJson("/api/toolbox", "{\"name\":\"重复\",\"url\":\"https://example.com\"}")).getLongValue("id"));
        long session = data(postJson("/api/llm-sessions", "{}")).getLongValue("id");
        long message = data(postJson("/api/llm-sessions/" + session + "/messages", "{\"role\":\"user\",\"content\":\"你好\"}")).getLongValue("id");
        JSONObject history = data(getData("/api/llm-sessions/" + session));
        assertEquals("你好", history.getString("title"));
        assertEquals(1, history.getJSONArray("messages").size());
        JSONArray summaries = JSON.parseObject(getData("/api/llm-sessions").getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
        assertEquals(1, summaries.getJSONObject(0).getIntValue("messageCount"));
        JSONArray fullSessions = JSON.parseObject(getData("/api/llm-sessions/full").getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("data");
        assertEquals(message, fullSessions.getJSONObject(0).getJSONArray("messages").getJSONObject(0).getLongValue("id"));
        mvc().perform(post("/api/llm-sessions/" + session + "/messages").contentType("application/json").content("{\"role\":\"system\",\"content\":\"x\"}")).andExpect(status().isBadRequest());
        mvc().perform(delete("/api/llm-sessions/" + session)).andExpect(status().isOk());
        assertTrue(messages.findById(message).orElseThrow().getDeleted());
        putJson("/api/app-settings/DEEPSEEK_API_KEY", "{\"value\":\"test-value\"}");
        assertEquals("test-value", data(getData("/api/app-settings/DEEPSEEK_API_KEY")).getString("value"));
    }

    @Test void invalidReorderRollsBackEarlierItemChanges() {
        var collection = collectionService.createCollection(java.util.Map.of("name", "排序事务"));
        Long collectionId = (Long) collection.get("id");
        @SuppressWarnings("unchecked")
        var groups = (java.util.List<java.util.Map<String, Object>>) collection.get("groups");
        Long groupId = (Long) groups.getFirst().get("id");
        Long otherGroupId = (Long) collectionService.createGroup(collectionId, java.util.Map.of("name", "其他")).get("id");
        collectionService.createItem(collectionId, java.util.Map.of("groupId", groupId, "name", "first", "url", "first.example"));
        Long second = (Long) collectionService.createItem(collectionId,
                java.util.Map.of("groupId", groupId, "name", "second", "url", "second.example")).get("id");
        Long other = (Long) collectionService.createItem(collectionId,
                java.util.Map.of("groupId", otherGroupId, "name", "other", "url", "other.example")).get("id");

        assertEquals(1, items.findById(second).orElseThrow().getSortOrder());
        assertThrows(IllegalArgumentException.class, () -> collectionService.reorderItems(collectionId, groupId,
                java.util.Map.of("itemIds", java.util.List.of(second, other))));
        assertEquals(1, items.findById(second).orElseThrow().getSortOrder());
        collectionService.deleteCollection(collectionId);
    }

    @Test void corsAndSchedulingDefaults() throws Exception {
        mvc().perform(options("/api/web-collections").header("Origin", "chrome-extension://abcdefghijklmnop").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "chrome-extension://abcdefghijklmnop"));
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
    }
}
