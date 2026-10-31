package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeetCodeNameHandlerTest {

    private final LeetCodeNameHandler handler = new LeetCodeNameHandler();

    @Test
    void convertsHyphenatedNamesToCamelCase() {
        List<AlfredResult> results = handler.doHandleAlfredEvent(param("Two-Sum"));

        assertEquals("TwoSum", results.getFirst().getTitle());
        assertEquals("TwoSum", results.getFirst().getMsgNeedToCopy());
        assertEquals("leetcode", results.getFirst().getSubtitle());
        assertEquals("clipboardAndPaste", results.getFirst().getType());
    }

    @Test
    void convertsALeetCodeProblemUrl() {
        List<AlfredResult> results = handler.doHandleAlfredEvent(
                param("https://leetcode.cn/problems/sort-list/description/"));

        assertEquals("SortList", results.getFirst().getMsgNeedToCopy());
        assertTrue(handler.matchEvent(param("https://leetcode.cn/problems/sort-list/description/"), false));
    }

    @Test
    void ignoresTextWithoutAHyphenOrLeetCode() {
        assertFalse(handler.matchEvent(param("sortlist"), false));
        assertTrue(handler.doHandleAlfredEvent(param("sortlist")).isEmpty());
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
