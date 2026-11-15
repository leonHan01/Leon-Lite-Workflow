package com.leon.workflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        WebCollectionController.class,
        StickyNoteController.class,
        TodoItemController.class,
        ToolboxItemController.class,
        LlmMessageController.class,
        AppSettingController.class, WorkspaceChatController.class
})
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
