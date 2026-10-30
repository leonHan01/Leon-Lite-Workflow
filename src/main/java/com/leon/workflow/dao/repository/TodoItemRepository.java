package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {
    List<TodoItem> findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc();
}
