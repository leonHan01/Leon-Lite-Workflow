package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/device-messages/images")
public class ChatImageController {
    private final ChatImageService images;
    public ChatImageController(ChatImageService images){this.images=images;}
    @PostMapping public ResponseEntity<DeviceMessageStore.Image> upload(@RequestParam String id,@RequestParam String conversationId,HttpServletRequest request) throws IOException {
        if(request.getContentLengthLong()>ChatImageService.MAX_UPLOAD)throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"图片不能超过 5 MB");
        byte[] bytes=request.getInputStream().readNBytes(ChatImageService.MAX_UPLOAD+1);
        if(bytes.length>ChatImageService.MAX_UPLOAD)throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"图片不能超过 5 MB");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.upload(id,conversationId,bytes));
    }
    @GetMapping("/{id}") public ResponseEntity<byte[]> image(@PathVariable String id){
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff")
                .cacheControl(CacheControl.noStore()).body(images.bytes(id));
    }
}
