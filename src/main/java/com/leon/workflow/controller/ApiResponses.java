package com.leon.workflow.controller;

import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

final class ApiResponses {
    private ApiResponses() {
    }

    static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
