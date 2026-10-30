package com.leon.workflow.controller;

import com.leon.workflow.service.TodoItemService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoItemControllerTest {
    @Test
    void crudDelegatesToService() {
        TodoItemService service = mock(TodoItemService.class);
        TodoItemController controller = new TodoItemController(service);
        when(service.listTodos()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.createTodo(Map.of("content", "写测试"))).thenReturn(Map.of("id", 2L));
        when(service.updateTodo(2L, Map.of("done", true))).thenReturn(Map.of("done", true));

        assertEquals(1, ((List<?>) controller.listTodos().getBody().get("data")).size());
        assertEquals(2L, ((Map<?, ?>) controller.createTodo(Map.of("content", "写测试")).getBody().get("data")).get("id"));
        assertEquals(true, ((Map<?, ?>) controller.updateTodo(2L, Map.of("done", true)).getBody().get("data")).get("done"));
        assertEquals("deleted", controller.deleteTodo(2L).getBody().get("data"));
        verify(service).deleteTodo(2L);
    }
}
