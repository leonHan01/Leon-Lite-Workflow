package com.leon.workflow.service;

import com.leon.workflow.dao.entity.TodoItem;
import com.leon.workflow.dao.repository.TodoItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leon.workflow.service.Payloads.booleanValue;
import static com.leon.workflow.service.Payloads.requiredString;

@Service
public class TodoItemService {
    private final TodoItemRepository todoItemRepository;

    public TodoItemService(TodoItemRepository todoItemRepository) {
        this.todoItemRepository = todoItemRepository;
    }

    public List<Map<String, Object>> listTodos() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TodoItem item : todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()) {
            result.add(toResponse(item));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createTodo(Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        TodoItem item = new TodoItem();
        item.setContent(requiredString(request.get("content"), "待办内容不能为空"));
        item.setDone(false);
        item.setSortOrder(nextSortOrder());
        item.setDeleted(false);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        return toResponse(todoItemRepository.save(item));
    }

    @Transactional
    public Map<String, Object> updateTodo(Long id, Map<String, Object> request) {
        TodoItem item = requireTodo(id);
        if (request.containsKey("content")) {
            item.setContent(requiredString(request.get("content"), "待办内容不能为空"));
        }
        if (request.containsKey("done")) {
            item.setDone(booleanValue(request.get("done")));
        }
        item.setUpdateTime(LocalDateTime.now());
        return toResponse(todoItemRepository.save(item));
    }

    @Transactional
    public void deleteTodo(Long id) {
        TodoItem item = requireTodo(id);
        item.setDeleted(true);
        item.setUpdateTime(LocalDateTime.now());
        todoItemRepository.save(item);
    }

    private Integer nextSortOrder() {
        return todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()
                .stream()
                .map(TodoItem::getSortOrder)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
    }

    private TodoItem requireTodo(Long id) {
        return todoItemRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("待办不存在: " + id));
    }

    private Map<String, Object> toResponse(TodoItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("content", item.getContent());
        result.put("done", item.getDone());
        result.put("sortOrder", item.getSortOrder());
        result.put("createTime", item.getCreateTime());
        result.put("updateTime", item.getUpdateTime());
        return result;
    }
}
