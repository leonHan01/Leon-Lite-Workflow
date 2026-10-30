package com.leon.workflow.dao.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "simple_kv")
@Data
public class SimpleKV {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String kvKey;

    private String kvValue;

    /**
     * 值类型: url / text
     */
    private String kvType;

    /**
     * 标签，逗号分隔
     */
    private String kvTags;

    /**
     * 类目
     */
    private String kvCategory;

    /**
     * 动作，逗号分隔
     */
    private String kvAction;

    /**
     * 图标文件名，如 red.png
     */
    private String kvIcon;
}
