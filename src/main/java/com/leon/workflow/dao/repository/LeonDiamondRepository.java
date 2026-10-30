package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.LeonDiamond;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeonDiamondRepository extends JpaRepository<LeonDiamond, Long> {
    // 根据 name 查询
    @Query(value = "SELECT * FROM leon_diamond WHERE diamond_key =:diamondKey", nativeQuery = true)
    LeonDiamond findByDiamondKey(@Param("diamondKey") String diamondKey);

    // 根据 name 查询
    @Query(value = "SELECT * FROM leon_diamond WHERE diamond_key LIKE :diamondKey", nativeQuery = true)
    List<LeonDiamond> findByDiamondKeyFuzzy(@Param("diamondKey") String diamondKey);
}
