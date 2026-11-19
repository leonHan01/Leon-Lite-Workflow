package com.leon.workflow.messaging;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Uses the same authenticated device API / browser-origin checks as conversations. */
@RestController
public class AgentContextController {
    private final AgentContextManager contexts;
    public AgentContextController(AgentContextManager contexts) { this.contexts=contexts; }
    public record Settings(String conversationId,int recentRounds) {}
    public record Compact(String conversationId,String agentId,String requestId) {}
    public record Clear(String conversationId,String requestId,long expectedLatestMessageId,long expectedHistoryRevision,boolean confirm) {}
    @GetMapping("/api/conversations/context/clear-preview")
    public ResponseEntity<?> clearPreview(@RequestParam String conversationId) { return response(contexts.clearPreview(conversationId)); }
    @PostMapping("/api/conversations/context/clear")
    public ResponseEntity<?> clear(@RequestBody Clear input) { return response(contexts.clear(input.conversationId(),input.requestId(),input.expectedLatestMessageId(),input.expectedHistoryRevision(),input.confirm())); }
    @PostMapping("/api/conversations/context/archive")
    public ResponseEntity<?> archive(@RequestBody Compact input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(contexts.enqueueArchive(input.conversationId(),input.agentId(),input.requestId()));
    }
    @GetMapping("/api/conversations/context/archive/{id}")
    public ResponseEntity<?> archive(@PathVariable String id) { return response(contexts.archive(id)); }
    @PostMapping("/api/conversations/context/archive/{id}/finish")
    public ResponseEntity<?> finish(@PathVariable String id) { return response(contexts.finishArchive(id)); }
    @PostMapping("/api/conversations/context/compact")
    public ResponseEntity<?> compact(@RequestBody Compact input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(contexts.enqueueCompression(input.conversationId(),input.agentId(),input.requestId()));
    }
    @GetMapping("/api/conversations/context")
    public ResponseEntity<?> info(@RequestParam String conversationId) { return response(contexts.info(conversationId)); }
    @PostMapping("/api/conversations/context/settings")
    public ResponseEntity<?> settings(@RequestBody Settings input) { return response(contexts.configure(input.conversationId(),input.recentRounds(),false)); }
    @PostMapping("/api/conversations/context/reset")
    public ResponseEntity<?> reset(@RequestBody Settings input) { return response(contexts.configure(input.conversationId(),input.recentRounds(),true)); }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
