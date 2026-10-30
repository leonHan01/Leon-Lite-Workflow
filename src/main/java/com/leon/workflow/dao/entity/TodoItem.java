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
@Table(name = "todo_item")
public class TodoItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Boolean done = false;

    @Column(nullable = false, columnDefinition = "int not null default 0")
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean deleted = false;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
