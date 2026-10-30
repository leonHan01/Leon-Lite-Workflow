package com.leon.workflow.controller;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.service.AlfredIconService;
import com.leon.workflow.service.SimpleKVCacheService;
import com.leon.workflow.service.SimpleKVService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
public class SimpleKVController {
    private final SimpleKVService service;
    private final SimpleKVCacheService cache;
    private final AlfredIconService icons;

    public SimpleKVController(SimpleKVService service, SimpleKVCacheService cache, AlfredIconService icons) {
        this.service = service;
        this.cache = cache;
        this.icons = icons;
    }

    @GetMapping("/api/simple-kv/list")
    public List<SimpleKV> list() { return cache.getAll(); }

    @PostMapping("/api/simple-kv/save")
    public ResponseEntity<Map<String, String>> save(@RequestBody SimpleKV simpleKV) {
        try {
            service.save(simpleKV);
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (Exception e) {
            log.error("save simple kv failed: {}", e.getClass().getSimpleName());
            return failure(e);
        }
    }

    @PostMapping("/api/simple-kv/delete")
    public ResponseEntity<Map<String, String>> delete(@RequestBody Map<String, Long> body) {
        try {
            service.delete(body.get("id"));
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (Exception e) {
            log.error("delete simple kv failed: {}", e.getClass().getSimpleName());
            return failure(e);
        }
    }

    private ResponseEntity<Map<String, String>> failure(Exception e) {
        return ResponseEntity.status(500).body(Map.of("status", "fail", "message", e.getMessage() == null ? "操作失败" : e.getMessage()));
    }

    @GetMapping("/api/simple-kv/query")
    public List<SimpleKV> query(@RequestParam String key) { return cache.fuzzyMatch(key); }

    @GetMapping("/api/simple-kv/icons")
    public List<String> listIcons() { return icons.list(); }

    @GetMapping("/api/simple-kv/icon-img/{name}")
    public ResponseEntity<byte[]> getIconImage(@PathVariable String name) {
        return icons.read(name).map(data -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(data))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
