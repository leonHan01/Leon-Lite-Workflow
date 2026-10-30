package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.WebCollectionGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebCollectionGroupRepository extends JpaRepository<WebCollectionGroup, Long> {
    List<WebCollectionGroup> findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List<Long> collectionIds);

    List<WebCollectionGroup> findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long collectionId);
}
