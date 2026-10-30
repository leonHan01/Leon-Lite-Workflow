package com.leon.workflow;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:pluginlinks;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class PluginLinksAlfredIntegrationTest {
    @Autowired WebApplicationContext context;

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }

    private JSONObject create(String path, String body) throws Exception {
        var response = mvc().perform(post(path).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        return JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");
    }

    private JSONArray query(String words) throws Exception {
        String body = JSON.toJSONString(java.util.Map.of("param", words));
        var response = mvc().perform(post("/alfred/query").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        return JSON.parseArray(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JSONObject findByUrl(JSONArray results, String url) {
        return results.stream().map(value -> (JSONObject) value)
                .filter(value -> url.equals(value.getString("uri"))).findFirst().orElse(null);
    }

    @Test void searchesLinksOpensOriginalUrlAndReflectsChanges() throws Exception {
        JSONObject collection = create("/api/web-collections", "{\"name\":\"项目蓝图\",\"description\":\"架构资料\"}");
        long collectionId = collection.getLongValue("id");
        long groupId = collection.getJSONArray("groups").getJSONObject(0).getLongValue("id");
        String itemPath = "/api/web-collections/" + collectionId + "/items";
        JSONObject item = create(itemPath, "{\"groupId\":" + groupId + ",\"name\":\"工程文档\",\"url\":\"https://docs.example.org/guide\",\"note\":\"部署手册\"}");
        JSONObject tool = create("/api/toolbox", "{\"name\":\"蓝图工具\",\"url\":\"https://tools.example.org\"}");
        create("/api/toolbox", "{\"name\":\"蓝图不安全\",\"url\":\"javascript://example.org\"}");

        JSONObject link = findByUrl(query("项目蓝图 部署"), "https://docs.example.org/guide");
        assertNotNull(link);
        assertEquals("openurl", link.getString("type"));
        assertEquals("工程文档", link.getString("title"));
        assertTrue(link.getString("subtitle").contains("项目蓝图"));
        assertEquals("https://docs.example.org/guide", link.getString("msgNeedToCopy"));
        assertNotNull(findByUrl(query("DOCS.EXAMPLE"), "https://docs.example.org/guide"));
        assertNotNull(findByUrl(query("蓝图工具"), "https://tools.example.org"));
        assertNull(findByUrl(query("蓝图不安全"), "javascript://example.org"));

        mvc().perform(put(itemPath + "/" + item.getLongValue("id")).contentType("application/json")
                .content("{\"groupId\":" + groupId + ",\"name\":\"更新后文档\",\"url\":\"https://new.example.org\"}"))
                .andExpect(status().isOk());
        assertNotNull(findByUrl(query("更新后文档"), "https://new.example.org"));
        assertNull(findByUrl(query("工程文档"), "https://docs.example.org/guide"));
        mvc().perform(delete("/api/toolbox/" + tool.getLongValue("id"))).andExpect(status().isOk());
        assertNull(findByUrl(query("蓝图工具"), "https://tools.example.org"));
        mvc().perform(delete("/api/web-collections/" + collectionId)).andExpect(status().isOk());
        assertNull(findByUrl(query("更新后文档"), "https://new.example.org"));
    }
}
