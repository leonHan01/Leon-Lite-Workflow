package com.leon.workflow.messaging;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.automation.WorkflowModels;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AgentController {
    private final WorkflowModels models;
    private final AgentRegistry agents; private final AgentChatService chat; private final ApiTokenInterceptor auth; private final AgentAvatarService avatars;
    public AgentController(AgentRegistry agents,AgentChatService chat,ApiTokenInterceptor auth,AgentAvatarService avatars,WorkflowModels models) { this.models=models;this.agents=agents;this.chat=chat;this.auth=auth;this.avatars=avatars; }
    @GetMapping("/api/agents") public ResponseEntity<?> agents() { return response(agents.summaries()); }
    @GetMapping("/api/conversations") public ResponseEntity<?> conversations() { return response(chat.conversations()); }
    public record Group(String name,List<String> agentIds) {}
    @PostMapping("/api/conversations/groups") public ResponseEntity<?> group(@RequestBody Group input) { return response(chat.group(input.name(),input.agentIds())); }
    @PostMapping("/api/conversations/direct/{agentId}") public ResponseEntity<?> direct(@PathVariable String agentId) { return response(chat.direct(agentId)); }
    @GetMapping("/api/agent-admin") public ResponseEntity<?> admin(HttpServletRequest request) { adminOnly(request);return response(Map.of("agents",agents.list(),"keyStorageEnabled",agents.keyStorageEnabled(),"avatars",avatars.catalog(),"models",models.list())); }
    @PostMapping("/api/agent-admin") public ResponseEntity<?> create(@RequestBody AgentRegistry.Edit input,HttpServletRequest request) { adminOnly(request);return response(agents.save(null,input)); }
    @PutMapping("/api/agent-admin/{id}") public ResponseEntity<?> update(@PathVariable String id,@RequestBody AgentRegistry.Edit input,HttpServletRequest request) { adminOnly(request);return response(agents.save(id,input)); }
    private void adminOnly(HttpServletRequest request) { if(auth.enabled()&&!auth.validBrowserSession(request)) throw AgentRegistry.failure(HttpStatus.FORBIDDEN,"请使用管理页面登录后配置 Agent"); }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
