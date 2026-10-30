package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.controller.DiamondController;
import com.leon.workflow.controller.SimpleKVController;
import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.dao.repository.LeonDiamondRepository;
import com.leon.workflow.dao.repository.SimpleKVRepository;
import com.leon.workflow.service.SimpleKVCacheService;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:config-refactor;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class ConfigurationIntegrationTest {
    @Autowired SimpleKVController kvController;
    @Autowired DiamondController diamondController;
    @Autowired SimpleKVRepository keyValues;
    @Autowired LeonDiamondRepository diamonds;
    @Autowired SimpleKVCacheService kvCache;
    @Autowired AlfredDiamondService diamondCache;
    @Autowired com.leon.workflow.dao.repository.JsonRecordRepository jsonRecords;

    @BeforeEach
    void clearTestData() {
        jsonRecords.deleteAll();
        keyValues.deleteAll();
        diamonds.deleteAll();
        kvCache.refresh();
        diamondCache.init();
    }

    @Test
    void keyValueWritesPreserveIdAndRefreshSearchAndHome() {
        SimpleKV request = new SimpleKV();
        request.setKvKey("search-original");
        request.setKvValue(" HTTPS://example.com ");
        request.setKvAction("首页");
        assertEquals(Map.of("status", "success"), kvController.save(request).getBody());
        Long id = request.getId();
        assertEquals("url", keyValues.findById(id).orElseThrow().getKvType());
        assertEquals(1, kvCache.getHomeItems().size());

        request.setKvKey("search-updated");
        request.setKvValue("new value");
        request.setKvType("text");
        request.setKvTags("tag");
        request.setKvCategory("category");
        request.setKvIcon("updated.png");
        request.setKvAction("置顶");
        assertEquals(200, kvController.save(request).getStatusCode().value());
        assertEquals(1, keyValues.count());
        assertEquals(request, keyValues.findById(id).orElseThrow());
        assertTrue(kvController.query("search-original").isEmpty());
        assertEquals(id, kvController.query("search-updated").getFirst().getId());
        assertTrue(kvCache.getHomeItems().isEmpty());

        assertEquals(200, kvController.delete(Map.of("id", id)).getStatusCode().value());
        assertTrue(kvController.list().isEmpty());
        assertTrue(keyValues.findById(id).isEmpty());
    }

    @Test
    void unknownKeyValueIdRemainsSuccessfulWithoutInserting() {
        SimpleKV request = new SimpleKV();
        request.setId(Long.MAX_VALUE);
        request.setKvKey("missing");
        assertEquals(200, kvController.save(request).getStatusCode().value());
        assertEquals(0, keyValues.count());
    }

    @Test
    void diamondCreatePreservesLegacyErrorStatusesAndValidatesBeforeWriting() {
        LeonDiamond request = diamond("kv-test", "名称:内容[tag]");
        assertEquals(200, diamondController.createDiamond(request).getStatusCode().value());
        assertEquals(409, diamondController.createDiamond(diamond("kv-test", "名称:值")).getStatusCode().value());
        assertEquals(400, diamondController.createDiamond(diamond("WebUrlDiamond", "{}" )).getStatusCode().value());
        assertEquals(400, diamondController.createDiamond(diamond("kv-invalid", "名称:值>倒序<")).getStatusCode().value());
        assertEquals(1, diamonds.count());
        assertEquals("内容", diamondCache.getAlfredKVList().getFirst().getValue());

        assertEquals(500, diamondController.saveText(Map.of("selectedId", request.getId().toString(), "text", "不合法的行")).getStatusCode().value());
        assertEquals("名称:内容[tag]", diamonds.findById(request.getId()).orElseThrow().getDiamondValue());
        assertEquals("内容", diamondCache.getAlfredKVList().getFirst().getValue());
        assertEquals(200, diamondController.saveText(Map.of("selectedId", request.getId().toString(), "text", "名称:新内容[tag]")).getStatusCode().value());
        assertEquals("新内容", diamondCache.getAlfredKVList().getFirst().getValue());
    }

    @Test
    void concurrentJsonSavesReturnTheSamePersistentRecord() throws Exception {
        var reads = new java.util.concurrent.CountDownLatch(2);
        var proxy = org.mockito.Mockito.mock(com.leon.workflow.dao.repository.JsonRecordRepository.class);
        org.mockito.Mockito.when(proxy.findByContentHash(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            var found = jsonRecords.findByContentHash(call.getArgument(0));
            if (found.isEmpty()) {
                reads.countDown();
                assertTrue(reads.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            return found;
        });
        org.mockito.Mockito.when(proxy.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                jsonRecords.saveAndFlush(call.getArgument(0)));
        var service = new com.leon.workflow.service.JsonRecordService(proxy, new com.leon.workflow.service.JsonRecordParser());
        String input = "{\"concurrentRefactorTest\":1}";
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.save(input));
            var second = pool.submit(() -> service.save(input));
            Long id = first.get(10, java.util.concurrent.TimeUnit.SECONDS).getId();
            assertEquals(id, second.get(10, java.util.concurrent.TimeUnit.SECONDS).getId());
            assertEquals(1, jsonRecords.count());
        }
    }

    private LeonDiamond diamond(String key, String value) {
        LeonDiamond diamond = new LeonDiamond();
        diamond.setDiamondKey(key);
        diamond.setDiamondValue(value);
        return diamond;
    }
}
