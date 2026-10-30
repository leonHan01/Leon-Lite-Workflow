package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.JsonRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JsonRecordRepository extends JpaRepository<JsonRecord, Long> {
    Optional<JsonRecord> findByContentHash(String contentHash);

    @Query("""
            SELECT r FROM JsonRecord r
            WHERE :keyword IS NULL
               OR :keyword = ''
               OR LOWER(COALESCE(r.rawText, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(r.bodyJson, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(r.reqJson, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(r.respJson, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
            ORDER BY r.updateTime DESC, r.id DESC
            """)
    List<JsonRecord> search(@Param("keyword") String keyword, Pageable pageable);
}
