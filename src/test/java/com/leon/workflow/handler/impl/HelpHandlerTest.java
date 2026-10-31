package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpHandlerTest {

    private final HelpHandler handler = new HelpHandler();

    @Test
    void listsEveryCapability() {
        assertTrue(handler.matchEvent(param("help"), false));
        List<AlfredResult> results = handler.doHandleAlfredEvent(param(" 帮助 "));

        assertEquals(18, results.size());
        assertTrue(results.size() <= 20);
        assertEquals("当前时间", results.get(0).getTitle());
        assertEquals("now", results.get(0).getMsgNeedToCopy());
        assertEquals("now", results.get(0).getAutoComplete());
        assertEquals("cal", copyOf(results, "日历"));
        assertEquals("md", copyOf(results, "Markdown"));
        assertEquals("1+2", copyOf(results, "计算"));
        assertEquals("fy hello", copyOf(results, "有道翻译"));
        assertEquals("restart", copyOf(results, "重启"));
        assertEquals("201_指南.png", results.get(0).getIcon());
    }

    @Test
    void filtersByChineseOrEnglishKeywords() {
        List<AlfredResult> calendar = handler.doHandleAlfredEvent(param("help 日历"));
        List<AlfredResult> english = handler.doHandleAlfredEvent(param("HELP calendar"));
        List<AlfredResult> both = handler.doHandleAlfredEvent(param("帮助 git mvn"));

        assertEquals(1, calendar.size());
        assertEquals("cal", calendar.get(0).getMsgNeedToCopy());
        assertEquals("日历", english.get(0).getTitle());
        assertEquals("全部片段", both.get(0).getTitle());
    }

    @Test
    void rejectsWordsThatOnlyStartWithTheCommand() {
        assertFalse(handler.matchEvent(param("helpful"), false));
        assertFalse(handler.matchEvent(param("help5"), false));
        assertFalse(handler.matchEvent(param(""), false));
    }

    @Test
    void tellsUserWhenNothingMatches() {
        List<AlfredResult> results = handler.doHandleAlfredEvent(param("help 没有这种功能"));

        assertEquals(1, results.size());
        assertEquals("没有匹配的功能", results.get(0).getTitle());
        assertEquals("help ", results.get(0).getAutoComplete());
    }

    private static String copyOf(List<AlfredResult> results, String title) {
        return results.stream()
                .filter(result -> title.equals(result.getTitle()))
                .map(AlfredResult::getMsgNeedToCopy)
                .findFirst()
                .orElseThrow();
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
