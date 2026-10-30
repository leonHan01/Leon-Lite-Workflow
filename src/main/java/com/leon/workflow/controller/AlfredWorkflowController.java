package com.leon.workflow.controller;

import com.leon.workflow.domain.dto.AlfredQueryRequest;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.service.AlfredQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class AlfredWorkflowController {
    private final AlfredQueryService queries;
    private final AlfredRequestGuard guard;

    public AlfredWorkflowController(AlfredQueryService queries, AlfredRequestGuard guard) {
        this.queries = queries;
        this.guard = guard;
    }

    @PostMapping("/alfred/query")
    public List<AlfredResult> query(@RequestBody AlfredQueryRequest request, HttpServletRequest httpRequest) {
        String raw = request.getParam() == null ? "" : request.getParam();
        guard.check(raw, request.getKey());
        return queries.query(raw, httpRequest.getHeader("employee_id"));
    }

    @GetMapping("/alfred/statistics")
    public void statistics(@RequestParam String param, @RequestHeader("employee_id") String employeeId) {
        // Retained for compatibility; the original endpoint only logged the request.
    }
}
