package com.leon.workflow.messaging;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Existing /api/** interceptor authenticates both reads and writes. */
@RestController
@RequestMapping("/api/device-messages")
public class DeviceMessageController {
    private final DeviceMessageStore store;
    private final AgentChatService chat;
    private final ChatAccess access;
    public DeviceMessageController(DeviceMessageStore store,AgentChatService chat,ChatAccess access){
        this.store=store;this.chat=chat;this.access=access;
    }

    @GetMapping
    public ResponseEntity<DeviceMessageStore.Page> list(@RequestParam(defaultValue="shared") String conversationId, @RequestParam(required = false) Long after,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "100") int limit,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.page(access.room(request,conversationId,false),after,before,limit));
    }


    @GetMapping("/search")
    public ResponseEntity<DeviceMessageStore.SearchPage> search(@RequestParam String q,
            @RequestParam(required=false) String conversationId,@RequestParam(required=false) Long before,
            @RequestParam(defaultValue="50") int limit,HttpServletRequest request) {
        var user=access.user(request);
        String room=conversationId==null?null:access.room(request,conversationId,false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(store.search(q,room,before,limit,user.id(),access.legacy(user)));
    }

    @GetMapping("/context")
    public ResponseEntity<DeviceMessageStore.SearchContext> context(@RequestParam String conversationId,
            @RequestParam long messageId,@RequestParam(defaultValue="around") String direction,
            @RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(store.searchContext(access.room(request,conversationId,false),messageId,direction,limit));
    }

    @PostMapping
    public ResponseEntity<DeviceMessageStore.Message> send(@RequestBody DeviceMessageStore.Send input,HttpServletRequest request) {
        var user=access.user(request);
        String room=access.room(request,DeviceMessageStore.conversationId(input),false);
        String sender=ChatUserRegistry.requestUser(request)==null?input.sender():user.username();
        var scoped=new DeviceMessageStore.Send(input.clientMessageId(),input.deviceId(),sender,input.platform(),
            input.text(),room,input.mentionAgentIds(),input.imageIds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.send(scoped,user.id()));
    }
    @PostMapping("/{messageId}/agents/{agentId}/retry") public ResponseEntity<?> retry(@PathVariable long messageId,@PathVariable String agentId,HttpServletRequest request) {
        access.requireRetry(request,messageId,agentId);
        store.retryJob(messageId,agentId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("queued",true));
    }
}
