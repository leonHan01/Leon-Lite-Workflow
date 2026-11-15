package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.FeishuBotService;
import com.leon.workflow.service.AppSettingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace-notification")
public class WorkspaceNotificationController {
    private static final Map<String,String> COLORS = Map.of("新增便签", "blue", "新增待办", "orange", "完成待办", "green");
    private final ApiTokenInterceptor auth;
    private final FeishuBotService feishu;
    private final AppSettingService settings;
    public WorkspaceNotificationController(ApiTokenInterceptor auth, FeishuBotService feishu, AppSettingService settings) { this.auth = auth; this.feishu = feishu; this.settings = settings; }
    public record Notification(String title, String text) {}
    @PostMapping
    public ResponseEntity<?> send(@RequestBody Notification request, HttpServletRequest http) {
        if (auth.enabled() && !auth.validBrowserSession(http)) return ResponseEntity.status(403).build();
        if (request.title() == null || !COLORS.containsKey(request.title()) || request.text() == null || request.text().length() > 2000)
            return ResponseEntity.badRequest().body(Map.of("message", "通知内容无效"));
        String webhook = settings.getValue(AppSettingService.FEISHU_WEBHOOK_URL);
        if (webhook.isEmpty()) return ResponseEntity.ok(Map.of("sent", false));
        try {
            var uri = java.net.URI.create(webhook);
            if (!"https".equals(uri.getScheme()) || !"open.feishu.cn".equals(uri.getHost()) || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]{1,200}"))
                return ResponseEntity.badRequest().body(Map.of("message", "飞书机器人地址无效"));
        } catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", "飞书机器人地址无效")); }
        return ResponseEntity.ok(Map.of("sent", feishu.sendCardToWebhook(request.title(), request.text(), COLORS.get(request.title()), webhook)));
    }
}
