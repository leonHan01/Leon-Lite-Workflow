package com.leon.workflow.controller;

import com.leon.workflow.monitor.MonitoringService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitor")
public class MonitoringController {
    private final MonitoringService monitoring;
    public MonitoringController(MonitoringService monitoring) { this.monitoring = monitoring; }

    @GetMapping
    public ResponseEntity<MonitoringService.Dashboard> dashboard() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(monitoring.dashboard());
    }

    @PostMapping("/page-view")
    public ResponseEntity<Void> pageView() {
        monitoring.recordView();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
