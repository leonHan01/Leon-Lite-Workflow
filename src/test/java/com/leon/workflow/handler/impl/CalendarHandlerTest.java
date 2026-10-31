package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarHandlerTest {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private CalendarHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CalendarHandler();
    }

    @Test
    void showsOneWeekPerLineAndEndsWithToday() {
        handler.setClock(clock(2026, 10, 31));
        AlfredParam param = param("cal");

        assertTrue(handler.matchEvent(param, false));
        List<AlfredResult> results = handler.doHandleAlfredEvent(param);

        assertEquals(7, results.size());
        assertEquals("2026年10月", results.get(0).getTitle());
        assertEquals(" 一   二   三   四   五   六   日  ", results.get(0).getSubtitle());
        assertEquals("              1   2   3   4 ", results.get(1).getTitle());
        assertEquals(" 26  27  28  29  30 [31]    ", results.get(5).getTitle());
        assertEquals("今天：2026-10-31", results.get(6).getTitle());
        assertEquals("星期六", results.get(6).getSubtitle());
        assertEquals("2026-10-31", results.get(6).getMsgNeedToCopy());
        assertTrue(results.get(0).getMsgNeedToCopy().endsWith("今天：2026-10-31"));
    }

    @Test
    void marksTheFirstDayWhenTodayIsTheFirst() {
        handler.setClock(clock(2026, 10, 1));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param("日历"));

        assertTrue(results.stream().anyMatch(result -> result.getTitle().contains("[ 1]")));
        assertEquals("今天：2026-10-01", results.get(results.size() - 1).getTitle());
        assertEquals("星期四", results.get(results.size() - 1).getSubtitle());
    }

    @Test
    void ignoresWordsThatOnlyStartWithTheCommand() {
        assertFalse(handler.matchEvent(param("call"), false));
        assertFalse(handler.matchEvent(param("cal 2026-11"), false));
    }

    private static Clock clock(int year, int month, int day) {
        return Clock.fixed(ZonedDateTime.of(year, month, day, 12, 0, 0, 0, BEIJING_ZONE).toInstant(), BEIJING_ZONE);
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
