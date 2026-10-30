package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.StickyNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StickyNoteRepository extends JpaRepository<StickyNote, Long> {
    List<StickyNote> findByDeletedFalseOrderByCreateTimeAscIdAsc();
}
