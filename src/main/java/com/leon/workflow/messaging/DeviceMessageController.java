package com.leon.workflow.messaging;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Existing /api/** interceptor authenticates both reads and writes. */
@RestController
@RequestMapping("/api/device-messages")
public class DeviceMessageController {
    private final DeviceMessageStore store;
    private final AgentChatService chat;
    public DeviceMessageController(DeviceMessageStore store, AgentChatService chat) { this.store = store; this.chat=chat; }

    @GetMapping
    public ResponseEntity<DeviceMessageStore.Page> list(@RequestParam(defaultValue="shared") String conversationId, @RequestParam(required = false) Long after,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.page(conversationId,after, before, limit));
    }

    @PostMapping
    public ResponseEntity<DeviceMessageStore.Message> send(@RequestBody DeviceMessageStore.Send input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.send(input));
    }
    @PostMapping("/{messageId}/agents/{agentId}/retry") public ResponseEntity<?> retry(@PathVariable long messageId,@PathVariable String agentId) {
        store.retryJob(messageId,agentId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("queued",true));
    }
}
