package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.LlmSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmSessionRepository extends JpaRepository<LlmSession, Long> {
    List<LlmSession> findByDeletedFalseOrderByUpdateTimeDescIdDesc();
}
