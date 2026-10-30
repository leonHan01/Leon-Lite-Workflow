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
@Table(name = "web_collection")
public class WebCollection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String icon;

    @Column(nullable = false)
    private Boolean deleted = false;

    @Column(nullable = false, columnDefinition = "int not null default 0")
    private Integer sortOrder = 0;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
