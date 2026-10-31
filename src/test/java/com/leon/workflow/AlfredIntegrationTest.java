package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.controller.AlfredWorkflowController;
import com.leon.workflow.controller.SimpleKVController;
import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.dao.repository.LeonDiamondRepository;
import com.leon.workflow.domain.dto.AlfredQueryRequest;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.service.JsonRecordService;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HelloServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:alfredtest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "leon.alfred.api-key=test-secret"})
class AlfredIntegrationTest {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired AlfredWorkflowController controller;
    @Autowired SimpleKVController kvController;
    @Autowired LeonDiamondRepository diamonds;
    @Autowired AlfredDiamondService diamondService;
    @Autowired JsonRecordService records;

    private List<AlfredResult> query(String input) throws Exception {
        AlfredQueryRequest request = new AlfredQueryRequest();
        request.setParam(input);
        request.setKey(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((input + "test-secret").getBytes(StandardCharsets.UTF_8))));
        return controller.query(request, new MockHttpServletRequest());
    }

    @Test void queryWorksOverHttp() throws Exception {
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest("1+2test-secret".getBytes(StandardCharsets.UTF_8)));
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/alfred/query"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"param\":\"1+2\",\"key\":\"" + signature + "\"}"))
                .build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("3", com.alibaba.fastjson.JSON.parseArray(response.body()).getJSONObject(0).getString("msgNeedToCopy"));
    }

    @Test void checksSignatureAndNormalizesFallback() throws Exception {
        assertThrows(ResponseStatusException.class, () -> controller.query(new AlfredQueryRequest(), new MockHttpServletRequest()));
        assertEquals("icons/default.png", query("").get(0).getIcon());
    }

    @Test void calculatorAndTimeCommandsWork() throws Exception {
        assertEquals("3", query("1+2").get(0).getMsgNeedToCopy());
        assertTrue(query("now").stream().anyMatch(r -> r.getTitle().startsWith("北京时间")));
        assertTrue(query("time").stream().anyMatch(r -> r.getTitle().startsWith("北京时间")));
        assertTrue(query("时间").stream().anyMatch(r -> r.getTitle().startsWith("北京时间")));
        assertTrue(query("shijian+1m").stream().anyMatch(r -> r.getTitle().startsWith("北京时间")));
        assertEquals("2025-01-01 08:00:00", query("1735689600").get(0).getMsgNeedToCopy());
        assertEquals("2025-01-01 08:00:00", query("1735689600000").get(0).getMsgNeedToCopy());
        assertEquals("1793419200", query("2026-10-31 12:00:00").get(1).getMsgNeedToCopy());
        assertEquals("| 列1 | 列2 |\n| --- | --- |\n|  |  |", query("md 表格").get(0).getMsgNeedToCopy());
        assertEquals(18, query("help").size());
        assertEquals("cal", query("help 日历").get(0).getMsgNeedToCopy());
        assertEquals(1, query("md 表格").size());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<AlfredResult> calendar = query("cal");
        assertEquals("今天：" + today, calendar.get(calendar.size() - 1).getTitle());
        int day = today.getDayOfMonth();
        String tens = day < 10 ? "\u3000" : String.valueOf((char) ('\uFF10' + day / 10));
        String ones = String.valueOf((char) ('\uFF10' + day % 10));
        assertTrue(calendar.stream().anyMatch(result -> result.getTitle().contains("\uFF3B" + tens + ones + "\uFF3D")));
        int width = calendar.get(1).getTitle().length();
        assertTrue(calendar.subList(1, calendar.size() - 1).stream().allMatch(result -> result.getTitle().length() == width));
        assertEquals("3", query("1+2").get(0).getMsgNeedToCopy());
    }

    @Test void savedKvIsSearchableAndVisibleOnHome() throws Exception {
        SimpleKV item = new SimpleKV();
        item.setKvKey("独特测试项目"); item.setKvValue("sample-value"); item.setKvAction("首页");
        assertTrue(kvController.save(item).getStatusCode().is2xxSuccessful());
        assertTrue(query("独特测试项目").stream().anyMatch(r -> "sample-value".equals(r.getMsgNeedToCopy())));
        assertTrue(query("").stream().anyMatch(r -> "独特测试项目".equals(r.getTitle())));
    }

    @Test void diamondContentAndBookmarksAreSearchable() throws Exception {
        LeonDiamond kv = new LeonDiamond(); kv.setDiamondKey("kv-demo"); kv.setDiamondValue("迁移测试:hello[tag-test]"); diamonds.save(kv);
        LeonDiamond urls = new LeonDiamond(); urls.setDiamondKey("WebUrlDiamond"); urls.setDiamondValue("[{\"name\":\"网站 testtag\",\"url\":\"https://example.com\"}]"); diamonds.save(urls);
        diamondService.init();
        assertTrue(query("迁移测试").stream().anyMatch(r -> "hello".equals(r.getMsgNeedToCopy())));
        assertTrue(query("testtag").stream().anyMatch(r -> "https://example.com".equals(r.getUri())));
    }

    @Test void jsonIsStoredAndSearchable() throws Exception {
        List<AlfredResult> result = query("{\"uniqueQueryField\":12345}");
        assertTrue(result.get(0).getUri().contains("json-record.html?id="));
        assertFalse(records.search("uniqueQueryField", 10).isEmpty());
    }
}
