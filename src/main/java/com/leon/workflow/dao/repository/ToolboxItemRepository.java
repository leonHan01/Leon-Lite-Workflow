package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.ToolboxItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolboxItemRepository extends JpaRepository<ToolboxItem, Long> {
    List<ToolboxItem> findByDeletedFalseOrderBySortOrderAscIdAsc();
}
