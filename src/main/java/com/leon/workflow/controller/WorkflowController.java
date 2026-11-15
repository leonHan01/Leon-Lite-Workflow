package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.automation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.time.Instant;
import static com.leon.workflow.automation.WorkflowJson.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final WorkflowCredentials credentials;
    private final ApiTokenInterceptor auth;
    private final WorkflowSchedules schedules;
    private final boolean schedulingEnabled;
    public WorkflowController(WorkflowStore store, WorkflowEngine engine, WorkflowCredentials credentials, ApiTokenInterceptor auth, WorkflowSchedules schedules,
            @Value("${leon.workflows.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.store = store; this.engine = engine; this.credentials = credentials; this.auth = auth;
        this.schedules = schedules;
        this.schedulingEnabled = schedulingEnabled;
    }
    @ModelAttribute void authorize(HttpServletRequest request) {
        if (auth.enabled() && (!auth.validBrowserSession(request) || (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod()) && !auth.validAdminWrite(request))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请通过管理页登录后操作工作流");
        if (request.getContentLengthLong() > 1_200_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "请求不能超过 1 MB");
    }
    @GetMapping public ResponseEntity<?> list() { return response(store.list()); }
    @GetMapping("/capabilities") public ResponseEntity<?> capabilities() {
        var value = new LinkedHashMap<>(engine.capabilities()); value.put("credentialsEnabled", credentials.enabled()); value.put("scheduledExecution", schedulingEnabled); return response(value);
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody Map<String, Object> body) { return response(store.create(required(body.get("name"), "工作流名称需要 1–100 个字符", 100))); }
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable String id) { return response(store.get(id)); }
    @PutMapping("/{id}") public ResponseEntity<?> save(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return response(store.save(id, required(body.get("name"), "工作流名称需要 1–100 个字符", 100), body.get("definition"), revision(body)));
    }
    @PostMapping("/{id}/publish") public ResponseEntity<?> publish(@PathVariable String id, @RequestBody Map<String, Object> body) { return response(store.publish(id, revision(body), write(engine.runtimes()))); }
    @GetMapping("/{id}/versions") public ResponseEntity<?> versions(@PathVariable String id) { return response(store.versions(id)); }
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id) { store.delete(id); return response(Map.of("deleted", true)); }
    @PostMapping("/{id}/runs") public ResponseEntity<?> start(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(engine.start(id, object(body.get("input"))));
    }
    @GetMapping("/{id}/runs") public ResponseEntity<?> runs(@PathVariable String id) { return response(store.runs(id)); }
    @GetMapping("/runs") public ResponseEntity<?> instances(@RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String trigger,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) { return response(store.allRuns(workflowId, status, trigger, q, page, pageSize)); }
    @GetMapping("/run-options") public ResponseEntity<?> runOptions() { return response(store.runOptions()); }
    @GetMapping("/{id}/schedule") public ResponseEntity<?> schedule(@PathVariable String id) { return response(schedules.get(id)); }
    @PutMapping("/{id}/schedule") public ResponseEntity<?> saveSchedule(@PathVariable String id, @RequestBody Map<String, Object> body) { return response(schedules.save(id, body, Instant.now())); }
    @PostMapping("/schedule-preview") public ResponseEntity<?> previewSchedule(@RequestBody Map<String, Object> body) {
        var rule = WorkflowScheduleRule.parse(body.get("cron"), body.get("timeZone")); return response(Map.of("nextRuns", rule.preview(Instant.now()), "timeZone", rule.zone().getId()));
    }
    @GetMapping("/runs/{id}") public ResponseEntity<?> run(@PathVariable String id) { return response(store.run(id)); }
    @PostMapping("/runs/{id}/cancel") public ResponseEntity<?> cancel(@PathVariable String id) { return response(engine.cancel(id)); }
    @PostMapping("/test-node") public ResponseEntity<?> test(@RequestBody Map<String, Object> body) { return response(engine.test(JSON.convertValue(body.get("node"), WorkflowGraph.Node.class), object(body.get("input")))); }
    @GetMapping("/credentials") public ResponseEntity<?> credentials() { return response(credentials.list()); }
    @PostMapping("/credentials") public ResponseEntity<?> credential(@RequestBody Map<String, Object> body) { return response(credentials.create(body)); }
    @GetMapping("/credentials/{id}") public ResponseEntity<?> credential(@PathVariable String id) { return response(credentials.get(id)); }
    @PutMapping("/credentials/{id}") public ResponseEntity<?> updateCredential(@PathVariable String id, @RequestBody Map<String, Object> body) { return response(credentials.update(id, body)); }
    @DeleteMapping("/credentials/{id}") public ResponseEntity<?> deleteCredential(@PathVariable String id) { credentials.delete(id); return response(Map.of("deleted", true)); }
    private long revision(Map<String, Object> body) {
        try { return Long.parseLong(text(body.get("revision"))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("缺少草稿版本，请刷新后重试"); }
    }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @ExceptionHandler(WorkflowEngine.DebugFailure.class) ResponseEntity<?> debugFailed(WorkflowEngine.DebugFailure error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", text(error.getMessage()), "logs", text(error.logs())));
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", text(error.getMessage()))); }
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> rejected(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "请求未完成"))); }
}
