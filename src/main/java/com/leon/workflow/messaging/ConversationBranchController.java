package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations/{id}/branches")
public class ConversationBranchController {
    public record Create(long messageId, String requestId, String name) {}
    private final ConversationBranchService branches;
    private final ChatAccess access;
    private final DeviceMessageStore store;

    public ConversationBranchController(ConversationBranchService branches, ChatAccess access, DeviceMessageStore store) {
        this.branches = branches;
        this.access = access;
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<DeviceMessageStore.Conversation> create(@PathVariable String id,
            @RequestBody Create input, HttpServletRequest request) {
        synchronized (store) {
            String room = access.room(request, id, false);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(branches.create(room, input.messageId(), input.requestId(), input.name(), access.user(request).id()));
        }
    }
}
