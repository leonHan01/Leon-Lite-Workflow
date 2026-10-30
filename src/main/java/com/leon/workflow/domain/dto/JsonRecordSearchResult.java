package com.leon.workflow.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JsonRecordSearchResult {
    private Long id;
    private String sourceType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String preview;
    private String matchField;
}
