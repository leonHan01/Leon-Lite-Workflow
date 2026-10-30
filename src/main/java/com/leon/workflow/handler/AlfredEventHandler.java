package com.leon.workflow.handler;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;

import java.util.List;

public interface AlfredEventHandler {
    List<AlfredResult> handleAlfredEvent(AlfredParam alfredParam);

    Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult);

    Integer getPriority();

    Boolean breakNextHandlerIfHaveResult();
}
