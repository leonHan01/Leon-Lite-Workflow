package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.WebCollectionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebCollectionItemRepository extends JpaRepository<WebCollectionItem, Long> {
    List<WebCollectionItem> findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(List<Long> collectionIds);

    List<WebCollectionItem> findByCollectionIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long collectionId);

    List<WebCollectionItem> findByGroupIdAndDeletedFalseOrderBySortOrderAscIdAsc(Long groupId);
}
