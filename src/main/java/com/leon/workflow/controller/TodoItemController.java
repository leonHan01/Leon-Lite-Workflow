package com.leon.workflow.controller;

import com.leon.workflow.service.TodoItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/todos")
public class TodoItemController {
    private final TodoItemService todoItemService;

    public TodoItemController(TodoItemService todoItemService) {
        this.todoItemService = todoItemService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTodos() {
        return ok(todoItemService.listTodos());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTodo(@RequestBody Map<String, Object> request) {
        return ok(todoItemService.createTodo(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTodo(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request) {
        return ok(todoItemService.updateTodo(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTodo(@PathVariable Long id) {
        todoItemService.deleteTodo(id);
        return ok("deleted");
    }
}
