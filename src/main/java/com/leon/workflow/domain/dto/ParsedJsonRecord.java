package com.leon.workflow.domain.dto;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;

@Data
public class ParsedJsonRecord {
    private String rawText;
    private String sourceType;
    private String bodyJson;
    private String reqJson;
    private String respJson;

    public boolean isValid() {
        return StringUtils.isNotBlank(bodyJson) || StringUtils.isNotBlank(reqJson) || StringUtils.isNotBlank(respJson);
    }
}
