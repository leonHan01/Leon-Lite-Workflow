package com.leon.workflow.domain.dto;

import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

@Data
public class AlfredParam {
    private List<String> params;

    private String instruction;

    private String paramsWithoutInstruction;

    private String originParams;

    private String employeeId;

    public AlfredParam() {
    }

    public AlfredParam(String originParams, List<String> params) {
        this.originParams = originParams;
        this.params = params;
        if (CollectionUtils.isNotEmpty(params)) {
            this.instruction = params.get(0);
            if (params.size() > 1) {
                StringBuilder stringBuilder = new StringBuilder();
                for (int i = 1; i < params.size(); i++) {
                    stringBuilder.append(params.get(i)).append(" ");
                }
                this.paramsWithoutInstruction = stringBuilder.toString().trim();
            }
        }

    }
}
