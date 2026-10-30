package com.leon.workflow.domain.dto;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Builder
@EqualsAndHashCode
public class AlfredResult {
    private String uri;

    private String title;

    private String subtitle;

    private String autoComplete;

    private String icon;

    private String type;

    private String msgNeedToCopy;

    private Double matchRatio;

    private int hitTime;

    /**
     * 其他详细信息，会传递到统计信息里
     */
    private String features;

    private String arg;
}
