package com.leon.workflow.service;

import com.leon.workflow.dao.entity.TodoItem;
import com.leon.workflow.dao.repository.TodoItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoItemServiceTest {
    @Mock
    private TodoItemRepository todoItemRepository;

    @InjectMocks
    private TodoItemService todoItemService;

    @Test
    void createTodoRejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> todoItemService.createTodo(Map.of("content", "  ")));
    }

    @Test
    void createTodoAssignsSortOrderAndPersists() {
        TodoItem existing = new TodoItem();
        existing.setSortOrder(2);
        when(todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()).thenReturn(List.of(existing));
        when(todoItemRepository.save(any())).thenAnswer(invocation -> {
            TodoItem item = invocation.getArgument(0);
            item.setId(9L);
            return item;
        });

        Map<String, Object> created = todoItemService.createTodo(Map.of("content", " 写测试 "));

        assertEquals(9L, created.get("id"));
        assertEquals("写测试", created.get("content"));
        assertEquals(false, created.get("done"));
        assertEquals(3, created.get("sortOrder"));
    }

    @Test
    void updateTodoMarksDoneFromString() {
        TodoItem item = new TodoItem();
        item.setId(1L);
        item.setContent("任务");
        item.setDone(false);
        item.setDeleted(false);
        item.setSortOrder(0);
        when(todoItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(todoItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = todoItemService.updateTodo(1L, Map.of("done", "true"));

        assertTrue((Boolean) updated.get("done"));
    }

    @Test
    void deleteTodoSoftDeletes() {
        TodoItem item = new TodoItem();
        item.setId(2L);
        item.setDeleted(false);
        when(todoItemRepository.findById(2L)).thenReturn(Optional.of(item));

        todoItemService.deleteTodo(2L);

        assertTrue(item.getDeleted());
        verify(todoItemRepository).save(item);
    }

    @Test
    void updateTodoChangesContent() {
        TodoItem item = new TodoItem();
        item.setId(1L);
        item.setContent("旧");
        item.setDone(false);
        item.setDeleted(false);
        item.setSortOrder(0);
        when(todoItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(todoItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = todoItemService.updateTodo(1L, Map.of("content", " 新内容 "));
        assertEquals("新内容", updated.get("content"));
        assertEquals(false, updated.get("done"));
    }

    @Test
    void missingTodoThrows() {
        when(todoItemRepository.findById(8L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> todoItemService.deleteTodo(8L));
    }

    @Test
    void listTodosMapsRepositoryRows() {
        TodoItem item = new TodoItem();
        item.setId(3L);
        item.setContent("列出");
        item.setDone(true);
        item.setSortOrder(1);
        when(todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()).thenReturn(List.of(item));

        List<Map<String, Object>> list = todoItemService.listTodos();

        assertEquals(1, list.size());
        assertEquals("列出", list.get(0).get("content"));
        assertFalse(todoItemService.listTodos().isEmpty());
    }
}
