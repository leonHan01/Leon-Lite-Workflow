package com.leon.workflow.controller;

import com.leon.workflow.monitor.MonitoringService;
import com.leon.workflow.monitor.DeploymentControlService;
import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    private final DeploymentControlService deploymentControl;
    private final ApiTokenInterceptor auth;
    public MonitoringController(MonitoringService monitoring, DeploymentControlService deploymentControl, ApiTokenInterceptor auth) {
        this.monitoring = monitoring;
        this.deploymentControl = deploymentControl;
        this.auth = auth;
    }

    @PostMapping("/deploy")
    public ResponseEntity<DeploymentControlService.Job> deploy(HttpServletRequest request) throws IOException {
        if (!auth.validBrowserSession(request) || !auth.validAdminWrite(request))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请通过管理页登录后部署");
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(deploymentControl.request());
    }

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
