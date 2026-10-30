package com.leon.workflow.controller;

import com.leon.workflow.service.TodoDigestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/todo-digest")
public class TodoDigestController {
    private final TodoDigestService todoDigestService;

    public TodoDigestController(TodoDigestService todoDigestService) {
        this.todoDigestService = todoDigestService;
    }

    @PostMapping("/stats")
    public ResponseEntity<Map<String, Object>> sendStats() {
        boolean sent = todoDigestService.sendStats("手动");
        return ok(sent ? "sent" : "skipped");
    }

    @PostMapping("/summary")
    public ResponseEntity<Map<String, Object>> sendSummary() {
        boolean sent = todoDigestService.sendMidnightSummary();
        return ok(sent ? "sent" : "skipped");
    }
}
