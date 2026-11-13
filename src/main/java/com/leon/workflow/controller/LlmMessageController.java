package com.leon.workflow.controller;

import com.leon.workflow.service.LlmMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/llm-sessions")
public class LlmMessageController {
    private final LlmMessageService llmMessageService;

    public LlmMessageController(LlmMessageService llmMessageService) {
        this.llmMessageService = llmMessageService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listSessions() {
        return ok(llmMessageService.listSessions());
    }

    @GetMapping("/full")
    public ResponseEntity<Map<String, Object>> listSessionsFull() {
        return ok(llmMessageService.listSessionsFull());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> request) {
        return ok(llmMessageService.createSession(request == null ? Map.of() : request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable Long id) {
        return ok(llmMessageService.getSession(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateSession(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, Object> request) {
        return ok(llmMessageService.updateSession(id, request == null ? Map.of() : request));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<Map<String, Object>> addMessage(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request) {
        return ok(llmMessageService.addMessage(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable Long id) {
        llmMessageService.deleteSession(id);
        return ok("deleted");
    }
}
