package com.leon.workflow.ai;

import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

@RestController
public class OpenAiExecutorController {
    private final OpenAiExecutor executor; private final ApiTokenInterceptor auth;
    public OpenAiExecutorController(OpenAiExecutor executor,ApiTokenInterceptor auth) { this.executor=executor;this.auth=auth; }
    private void admin(HttpServletRequest request) {
        if(auth.enabled() && (!auth.validBrowserSession(request) || (!"GET".equals(request.getMethod()) && !auth.validAdminWrite(request))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请通过管理页面配置 AI 执行器");
    }
    private String worker(HttpServletRequest request) {
        checkSize(request);
        return executor.authenticate(request.getHeader("Authorization"));
    }
    @ModelAttribute void authorize(HttpServletRequest request) {
        checkSize(request);
        if(request.getRequestURI().startsWith("/api/ai-executor/")) worker(request); else admin(request);
    }
    private void checkSize(HttpServletRequest request){
        int limit=request.getRequestURI().matches("/api/ai-executor/jobs/[A-Za-z0-9-]+/complete")?OpenAiExecutor.IMAGE_RESULT_LIMIT+4096:1_100_000;
        if(request.getContentLengthLong()>limit)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"执行器请求过大");
    }
    @GetMapping("/api/ai-executor-admin") public ResponseEntity<?> status(HttpServletRequest request) { admin(request);return result(executor.status()); }
    @PostMapping("/api/ai-executor-admin/workers") public ResponseEntity<?> register(@RequestBody Map<String,Object> body,HttpServletRequest request) { admin(request);return result(executor.register(text(body.get("name")))); }
    @DeleteMapping("/api/ai-executor-admin/workers/{id}") public ResponseEntity<?> revoke(@PathVariable String id,HttpServletRequest request) { admin(request);executor.revoke(id);return result(Map.of("revoked",true)); }
    @PutMapping("/api/ai-executor-admin/mode") public ResponseEntity<?> mode(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        admin(request); if(!(body.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("执行模式无效");
        executor.mode(enabled);return result(executor.status());
    }
    @PostMapping("/api/ai-executor/heartbeat") public ResponseEntity<?> heartbeat(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        executor.heartbeat(worker(request),Boolean.TRUE.equals(body.get("ready")));return result(Map.of("accepted",true));
    }
    @PostMapping("/api/ai-executor/claim") public ResponseEntity<?> claim(HttpServletRequest request) { return result(executor.claim(worker(request))); }
    @PostMapping("/api/ai-executor/jobs/{id}/start") public ResponseEntity<?> start(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        executor.start(worker(request),id,required(body.get("lease"),"任务租约缺失",100));return result(Map.of("started",true));
    }
    @PostMapping("/api/ai-executor/jobs/{id}/complete") public ResponseEntity<?> complete(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        executor.complete(worker(request),id,required(body.get("lease"),"任务租约缺失",100),object(body.get("result")));return result(Map.of("accepted",true));
    }
    private ResponseEntity<?> result(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> rejected(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",Objects.requireNonNullElse(error.getReason(),"执行器请求未完成"))); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message",text(error.getMessage()))); }
}
