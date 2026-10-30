package com.leon.workflow.controller;

import com.alibaba.fastjson.JSON;
import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.service.DiamondService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class DiamondController {
    private final DiamondService service;

    public DiamondController(DiamondService service) { this.service = service; }

    @PostMapping("/api/diamond")
    public ResponseEntity<?> createDiamond(@RequestBody LeonDiamond diamond) {
        try {
            return ResponseEntity.ok(service.create(diamond));
        } catch (DiamondService.DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/api/options")
    public String getDiamondDataId() { return JSON.toJSONString(service.options()); }

    @GetMapping("/api/text")
    public String getText(@RequestParam String selected) { return service.text(Long.parseLong(selected)); }

    @PostMapping("/api/save")
    public ResponseEntity<Map<String, String>> saveText(@RequestBody Map<String, String> body) {
        try {
            service.save(Long.parseLong(body.get("selectedId")), body.get("text"));
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "fail", "message", e.getMessage() == null ? "保存失败" : e.getMessage()));
        }
    }
}
