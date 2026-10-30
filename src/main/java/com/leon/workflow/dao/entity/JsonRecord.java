package com.leon.workflow.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "json_record")
public class JsonRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String contentHash;

    @Column(nullable = false, length = 20)
    private String sourceType;

    @Column(columnDefinition = "CLOB")
    private String rawText;

    @Column(columnDefinition = "CLOB")
    private String bodyJson;

    @Column(columnDefinition = "CLOB")
    private String reqJson;

    @Column(columnDefinition = "CLOB")
    private String respJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
