package com.leon.workflow.messaging;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Uses the same authenticated device API / browser-origin checks as conversations. */
@RestController
public class AgentContextController {
    private final AgentContextManager contexts;
    public AgentContextController(AgentContextManager contexts) { this.contexts=contexts; }
    public record Settings(String conversationId,int recentRounds) {}
    @GetMapping("/api/conversations/context")
    public ResponseEntity<?> info(@RequestParam String conversationId) { return response(contexts.info(conversationId)); }
    @PostMapping("/api/conversations/context/settings")
    public ResponseEntity<?> settings(@RequestBody Settings input) { return response(contexts.configure(input.conversationId(),input.recentRounds(),false)); }
    @PostMapping("/api/conversations/context/reset")
    public ResponseEntity<?> reset(@RequestBody Settings input) { return response(contexts.configure(input.conversationId(),input.recentRounds(),true)); }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
