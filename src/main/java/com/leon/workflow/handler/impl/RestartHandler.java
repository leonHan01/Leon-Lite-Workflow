package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RestartHandler extends AbstractEventHandler {
    @Override public List<AlfredResult> doHandleAlfredEvent(AlfredParam param) {
        return List.of(AlfredResult.builder().title("请通过服务管理工具重启服务").build());
    }
    @Override public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) { return "restart".equals(param.getInstruction()); }
    @Override public Boolean breakNextHandlerIfHaveResult() { return true; }
}
