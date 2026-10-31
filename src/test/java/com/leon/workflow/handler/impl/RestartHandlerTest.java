package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartHandlerTest {

    private final RestartHandler handler = new RestartHandler();

    @Test
    void restartOnlyTellsTheUserToUseServiceManagement() {
        AlfredParam param = new AlfredParam("restart now", List.of("restart", "now"));

        assertTrue(handler.matchEvent(param, false));
        assertEquals("请通过服务管理工具重启服务", handler.doHandleAlfredEvent(param).getFirst().getTitle());
        assertTrue(handler.breakNextHandlerIfHaveResult());
    }

    @Test
    void rejectsOtherCommandsAndDifferentCase() {
        assertFalse(handler.matchEvent(new AlfredParam("Restart", List.of("Restart")), false));
        assertFalse(handler.matchEvent(new AlfredParam("re", List.of("re")), false));
    }
}
