package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/device-messages/images")
public class ChatImageController {
    private final ChatImageService images;
    private final ChatAccess access;
    public ChatImageController(ChatImageService images,ChatAccess access){this.images=images;this.access=access;}
    @PostMapping public ResponseEntity<DeviceMessageStore.Image> upload(@RequestParam String id,@RequestParam String conversationId,HttpServletRequest request) throws IOException {
        String room=access.room(request,conversationId,false);
        if(request.getContentLengthLong()>ChatImageService.MAX_UPLOAD)throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"图片不能超过 5 MB");
        byte[] bytes=request.getInputStream().readNBytes(ChatImageService.MAX_UPLOAD+1);
        if(bytes.length>ChatImageService.MAX_UPLOAD)throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"图片不能超过 5 MB");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.upload(id,room,bytes));
    }
    @GetMapping("/{id}") public ResponseEntity<byte[]> image(@PathVariable String id,HttpServletRequest request){
        access.requireImage(request,id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff")
                .cacheControl(CacheControl.noStore()).body(images.bytes(id));
    }
}
