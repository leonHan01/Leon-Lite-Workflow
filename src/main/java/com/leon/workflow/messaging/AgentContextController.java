package com.leon.workflow.messaging;

import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Uses the same authenticated device API / browser-origin checks as conversations. */
@RestController
public class AgentContextController {
    private final AgentContextManager contexts;
    private final ChatAccess access;
    public AgentContextController(AgentContextManager contexts,ChatAccess access) { this.contexts=contexts;this.access=access; }
    public record Settings(String conversationId,int recentRounds) {}
    public record Compact(String conversationId,String agentId,String requestId) {}
    public record Clear(String conversationId,String requestId,long expectedLatestMessageId,long expectedHistoryRevision,boolean confirm) {}
    @GetMapping("/api/conversations/context/clear-preview")
    public ResponseEntity<?> clearPreview(@RequestParam String conversationId,HttpServletRequest request) { return response(contexts.clearPreview(access.room(request,conversationId,true))); }
    @PostMapping("/api/conversations/context/clear")
    public ResponseEntity<?> clear(@RequestBody Clear input,HttpServletRequest request) { return response(contexts.clear(access.room(request,input.conversationId(),true),input.requestId(),input.expectedLatestMessageId(),input.expectedHistoryRevision(),input.confirm())); }
    @PostMapping("/api/conversations/context/archive")
    public ResponseEntity<?> archive(@RequestBody Compact input,HttpServletRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(contexts.enqueueArchive(access.room(request,input.conversationId(),true),input.agentId(),input.requestId(),access.user(request).id()));
    }
    @GetMapping("/api/conversations/context/archive/{id}")
    public ResponseEntity<?> archive(@PathVariable String id,HttpServletRequest request) { access.requireArchive(request,id,false);return response(contexts.archive(id)); }
    @PostMapping("/api/conversations/context/archive/{id}/finish")
    public ResponseEntity<?> finish(@PathVariable String id,HttpServletRequest request) { access.requireArchive(request,id,true);return response(contexts.finishArchive(id)); }
    @PostMapping("/api/conversations/context/compact")
    public ResponseEntity<?> compact(@RequestBody Compact input,HttpServletRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(contexts.enqueueCompression(access.room(request,input.conversationId(),false),input.agentId(),input.requestId(),access.user(request).id()));
    }
    @GetMapping("/api/conversations/context")
    public ResponseEntity<?> info(@RequestParam String conversationId,HttpServletRequest request) { return response(contexts.info(access.room(request,conversationId,false))); }
    @PostMapping("/api/conversations/context/settings")
    public ResponseEntity<?> settings(@RequestBody Settings input,HttpServletRequest request) { return response(contexts.configure(access.room(request,input.conversationId(),true),input.recentRounds(),false)); }
    @PostMapping("/api/conversations/context/reset")
    public ResponseEntity<?> reset(@RequestBody Settings input,HttpServletRequest request) { return response(contexts.configure(access.room(request,input.conversationId(),true),input.recentRounds(),true)); }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
