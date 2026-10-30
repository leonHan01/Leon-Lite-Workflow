package com.leon.workflow.handler;

import com.leon.workflow.constant.HandlerPriority;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class AbstractEventHandler implements AlfredEventHandler {
    @Override
    public List<AlfredResult> handleAlfredEvent(AlfredParam alfredParam) {
        try {
            return doHandleAlfredEvent(alfredParam);
        } catch (Exception e) {
            log.error("Alfred handler {} failed: {}", getClass().getSimpleName(), e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    public abstract List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam);

    @Override
    public Integer getPriority() {
        return HandlerPriority.NORMAL;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return false;
    }

}
