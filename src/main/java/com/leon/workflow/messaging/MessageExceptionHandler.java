package com.leon.workflow.messaging;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes={AgentController.class,DeviceMessageController.class,AgentAvatarController.class})
public class MessageExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",error.getReason()==null?"请求未完成":error.getReason()));
    }
}
