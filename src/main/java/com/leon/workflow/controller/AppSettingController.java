package com.leon.workflow.controller;

import com.leon.workflow.service.AppSettingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/app-settings")
public class AppSettingController {
    private final AppSettingService appSettingService;

    public AppSettingController(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getSetting(@PathVariable String key) {
        return ok(appSettingService.getSetting(key));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> saveSetting(@PathVariable String key,
                                                           @RequestBody Map<String, Object> request) {
        return ok(appSettingService.saveSetting(key, request));
    }
}
