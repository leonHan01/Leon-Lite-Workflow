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
    private final ChatFavorites favorites;private final ChatDocumentService documents;private final ChatPushService push;
    public DeviceMessageController(DeviceMessageStore store,AgentChatService chat,ChatAccess access,ChatFavorites favorites,ChatDocumentService documents,ChatPushService push){
        this.store=store;this.chat=chat;this.access=access;this.favorites=favorites;this.documents=documents;this.push=push;
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

    @GetMapping("/notifications/status") public ResponseEntity<?> notificationStatus(HttpServletRequest request){var result=new java.util.HashMap<String,Object>(push.status());result.put("userId",access.user(request).id());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);}
    @GetMapping("/notifications") public ResponseEntity<ChatPushService.Page> notifications(@RequestParam String deviceId,@RequestParam(required=false) Long after,@RequestParam(defaultValue="false") boolean preview,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(push.inbox(access.user(request),deviceId,after,preview));
    }
    @PostMapping("/notifications/devices") public ResponseEntity<?> registerNotifications(@RequestBody ChatPushService.Registration registration,HttpServletRequest request){
        push.register(access.user(request),registration,request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("registered",true));
    }
    @PostMapping("/notifications/devices/{deviceId}/remove") public ResponseEntity<?> removeNotifications(@PathVariable String deviceId,HttpServletRequest request){
        push.remove(access.user(request),deviceId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("removed",true));
    }
    @PostMapping("/documents") public ResponseEntity<DeviceMessageStore.Document> uploadDocument(@RequestParam String id,@RequestParam String conversationId,@RequestParam String name,HttpServletRequest request)throws java.io.IOException {
        var user=access.user(request);String room=access.room(request,conversationId,false);
        if(request.getContentLengthLong()>ChatDocumentService.MAX_BYTES)throw DeviceMessageStore.failure(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"文件不能超过 10 MB");
        byte[] bytes=request.getInputStream().readNBytes(ChatDocumentService.MAX_BYTES+1);
        if(bytes.length>ChatDocumentService.MAX_BYTES)throw DeviceMessageStore.failure(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"文件不能超过 10 MB");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.upload(id,room,user.id(),name,bytes));
    }
    @GetMapping("/documents/{id}") public ResponseEntity<byte[]> document(@PathVariable String id,HttpServletRequest request){
        synchronized(store){access.room(request,documents.room(id),false);var doc=store.documents(id).getFirst();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options","nosniff").header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(doc.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(documents.bytes(id));}
    }
    @GetMapping("/features") public ResponseEntity<?> features(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("quotes",true,"favorites",true,"documents",true));
    }
    @GetMapping("/favorites") public ResponseEntity<ChatFavorites.Page> favorites(@RequestParam(defaultValue="") String q,@RequestParam(required=false) Long before,@RequestParam(defaultValue="30") int limit,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(favorites.page(access.user(request).id(),q,before,limit));
    }
    @PostMapping("/{messageId}/favorite") public ResponseEntity<ChatFavorites.Item> favorite(@PathVariable long messageId,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(favorites.add(access.user(request),messageId));
    }
    @PostMapping("/favorites/{id}/remove") public ResponseEntity<?> removeFavorite(@PathVariable long id,HttpServletRequest request){
        favorites.remove(access.user(request).id(),id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("removed",true));
    }
    @GetMapping("/favorites/{id}/documents/{documentId}") public ResponseEntity<byte[]> favoriteDocument(@PathVariable long id,@PathVariable String documentId,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
            .header("X-Content-Type-Options","nosniff").body(favorites.document(access.user(request).id(),id,documentId));
    }
    @GetMapping("/favorites/{id}/images/{imageId}") public ResponseEntity<byte[]> favoriteImage(@PathVariable long id,@PathVariable String imageId,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(org.springframework.http.MediaType.IMAGE_JPEG)
            .header("X-Content-Type-Options","nosniff").body(favorites.image(access.user(request).id(),id,imageId));
    }

    @PostMapping
    public ResponseEntity<DeviceMessageStore.Message> send(@RequestBody DeviceMessageStore.Send input,HttpServletRequest request) {
        var user=access.user(request);
        String room=access.room(request,DeviceMessageStore.conversationId(input),false);
        String sender=ChatUserRegistry.requestUser(request)==null?input.sender():user.username();
        var scoped=new DeviceMessageStore.Send(input.clientMessageId(),input.deviceId(),sender,input.platform(),
            input.text(),room,input.mentionAgentIds(),input.imageIds(),input.quoteMessageId(),input.documentIds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.send(scoped,user.id()));
    }
    @PostMapping("/{messageId}/agents/{agentId}/retry") public ResponseEntity<?> retry(@PathVariable long messageId,@PathVariable String agentId,HttpServletRequest request) {
        access.requireRetry(request,messageId,agentId);
        store.retryJob(messageId,agentId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("queued",true));
    }
}
