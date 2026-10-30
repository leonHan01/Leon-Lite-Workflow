package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.SimpleKV;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SimpleKVRepository extends JpaRepository<SimpleKV, Long> {

    @Query(value = "SELECT * FROM simple_kv WHERE kv_key = :kvKey", nativeQuery = true)
    SimpleKV findByKvKey(@Param("kvKey") String kvKey);

    @Query(value = "SELECT * FROM simple_kv WHERE kv_key LIKE :kvKey", nativeQuery = true)
    List<SimpleKV> findByKvKeyFuzzy(@Param("kvKey") String kvKey);
}
