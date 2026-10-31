package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownHandlerTest {

    private final MarkdownHandler handler = new MarkdownHandler();

    @Test
    void listsSyntaxAndExistingCalloutsForMd() {
        AlfredParam param = param("md");

        assertTrue(handler.matchEvent(param, false));
        List<AlfredResult> results = handler.doHandleAlfredEvent(param);

        assertEquals(20, results.size());
        assertEquals("标题", results.get(0).getTitle());
        assertEquals("# 标题", results.get(0).getMsgNeedToCopy());
        assertTrue(results.stream().anyMatch(result -> "note 笔记".equals(result.getTitle())));
        assertTrue(results.stream().anyMatch(result -> ">[!note] 笔记\n> test".equals(result.getMsgNeedToCopy())));
    }

    @Test
    void filtersByChineseOrEnglishKeywords() {
        List<AlfredResult> table = handler.doHandleAlfredEvent(param("md 表格"));
        List<AlfredResult> english = handler.doHandleAlfredEvent(param("MD table"));
        List<AlfredResult> code = handler.doHandleAlfredEvent(param("md 代码 块"));

        assertEquals(1, table.size());
        assertEquals("| 列1 | 列2 |\n| --- | --- |\n|  |  |", table.get(0).getMsgNeedToCopy());
        assertEquals(table.get(0).getMsgNeedToCopy(), english.get(0).getMsgNeedToCopy());
        assertEquals("代码块", code.get(0).getTitle());
    }

    @Test
    void rejectsWordsThatOnlyStartWithMd() {
        AlfredParam param = param("md5");

        assertFalse(handler.matchEvent(param, false));
    }

    @Test
    void tellsUserWhenNothingMatches() {
        List<AlfredResult> results = handler.doHandleAlfredEvent(param("md 不存在的语法"));

        assertEquals(1, results.size());
        assertEquals("没有匹配的 Markdown 语法", results.get(0).getTitle());
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
