package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.WebCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebCollectionRepository extends JpaRepository<WebCollection, Long> {
    List<WebCollection> findByDeletedFalseOrderBySortOrderAscIdAsc();
}
