package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/conversations/backups")
public class ChatBackupController {
    private final ChatBackupService backups;
    public ChatBackupController(ChatBackupService backups){this.backups=backups;}
    @GetMapping public ResponseEntity<byte[]> export(@RequestParam String conversationId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip"))
            .header("Content-Disposition","attachment; filename=\"leon-chat.zip\"").body(backups.export(conversationId));
    }
    @PostMapping(consumes="application/zip") public ResponseEntity<?> restore(@RequestParam String requestId,@RequestParam(required=false) String agentId,HttpServletRequest request)throws IOException{
        if(request.getContentLengthLong()>ChatBackupService.MAX_BYTES)throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"备份不能超过 32 MB");
        byte[] data=request.getInputStream().readNBytes(ChatBackupService.MAX_BYTES+1);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(backups.restore(requestId,agentId,data));
    }
}
