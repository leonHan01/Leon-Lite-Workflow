package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.BrowserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;

public interface BrowserSessionRepository extends JpaRepository<BrowserSession, String> {
    void deleteByExpiresAtBefore(Instant now);
}
