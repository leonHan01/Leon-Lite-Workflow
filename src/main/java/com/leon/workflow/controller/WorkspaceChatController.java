package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.DeepseekChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace-chat")
public class WorkspaceChatController {
    private final ApiTokenInterceptor auth;
    private final DeepseekChatService chat;
    public WorkspaceChatController(ApiTokenInterceptor auth, DeepseekChatService chat) { this.auth = auth; this.chat = chat; }
    public record ChatRequest(List<Map<String,String>> messages) {}
    @PostMapping
    public ResponseEntity<?> complete(@RequestBody ChatRequest request, HttpServletRequest http) {
        if (auth.enabled() && !auth.validBrowserSession(http)) return ResponseEntity.status(403).build();
        try { return ResponseEntity.ok(Map.of("content", chat.completeMessages(request.messages()))); }
        catch (IllegalStateException error) { return ResponseEntity.status(502).body(Map.of("message", "DeepSeek 请求未完成，请检查高级设置和网络连接。")); }
    }
}
