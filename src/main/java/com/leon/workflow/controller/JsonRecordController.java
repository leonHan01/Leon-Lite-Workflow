package com.leon.workflow.controller;

import com.leon.workflow.dao.entity.JsonRecord;
import com.leon.workflow.domain.dto.JsonRecordSearchResult;
import com.leon.workflow.service.JsonRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class JsonRecordController {

    private final JsonRecordService jsonRecordService;

    public JsonRecordController(JsonRecordService jsonRecordService) {
        this.jsonRecordService = jsonRecordService;
    }

    @GetMapping("/api/json-record/{id}")
    public ResponseEntity<JsonRecord> get(@PathVariable Long id) {
        return jsonRecordService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/json-record/search")
    public List<JsonRecordSearchResult> search(@RequestParam(value = "keyword", required = false) String keyword,
                                               @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        return jsonRecordService.search(keyword, limit);
    }
}
