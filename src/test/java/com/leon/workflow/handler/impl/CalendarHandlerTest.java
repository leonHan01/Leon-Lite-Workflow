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

        assertEquals(8, results.size());
        assertEquals("2026年10月", results.get(0).getTitle());
        assertEquals("", results.get(0).getSubtitle());
        assertEquals(header(), results.get(1).getTitle());
        assertEquals(week(0, 0, 0, 1, 2, 3, 4), results.get(2).getTitle());
        assertEquals(week(26, 27, 28, 29, 30, 31, 0), results.get(6).getTitle());
        assertEquals(28, results.get(1).getTitle().length());
        for (int i = 1; i <= 6; i++) {
            assertEquals(results.get(1).getTitle().length(), results.get(i).getTitle().length());
        }
        assertEquals('四', results.get(1).getTitle().charAt(14));
        assertEquals('\uFF11', results.get(2).getTitle().charAt(14));
        assertEquals('\uFF10', results.get(6).getTitle().charAt(18));
        assertEquals("\uFF3B\uFF13\uFF11\uFF3D", results.get(6).getTitle().substring(20, 24));
        assertEquals("今天：2026-10-31", results.get(7).getTitle());
        assertEquals("星期六", results.get(7).getSubtitle());
        assertEquals("2026-10-31", results.get(7).getMsgNeedToCopy());
        assertTrue(results.get(0).getMsgNeedToCopy().endsWith("今天：2026-10-31"));
        assertTrue(results.get(1).getMsgNeedToCopy().contains(results.get(1).getTitle()));
    }

    @Test
    void marksTheFirstDayWhenTodayIsTheFirst() {
        handler.setClock(clock(2026, 10, 1));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param("日历"));

        assertTrue(results.stream().anyMatch(result -> result.getTitle().contains("\uFF3B\u3000\uFF11\uFF3D")));
        assertEquals("今天：2026-10-01", results.get(results.size() - 1).getTitle());
        assertEquals("星期四", results.get(results.size() - 1).getSubtitle());
    }

    @Test
    void alignsTodayWhenTheMonthStartsOnMonday() {
        handler.setClock(clock(2026, 6, 1));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param("CAL"));

        String header = results.get(1).getTitle();
        String firstWeek = results.get(2).getTitle();
        assertEquals(header.length(), firstWeek.length());
        assertEquals('一', header.charAt(2));
        assertEquals("\uFF3B\u3000\uFF11\uFF3D", firstWeek.substring(0, 4));
        assertEquals(firstWeek, results.get(2).getMsgNeedToCopy());
        assertEquals("星期一", results.get(results.size() - 1).getSubtitle());
        assertEquals("2026-06-01", results.get(results.size() - 1).getMsgNeedToCopy());
    }

    @Test
    void alignsASundayInTheLastColumn() {
        handler.setClock(clock(2026, 11, 1));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param(" 日历 "));

        assertEquals(9, results.size());
        String header = results.get(1).getTitle();
        String firstWeek = results.get(2).getTitle();
        assertEquals(header.length(), firstWeek.length());
        assertEquals('日', header.charAt(26));
        assertEquals("\uFF3B\u3000\uFF11\uFF3D", firstWeek.substring(24, 28));
        assertEquals("星期日", results.get(results.size() - 1).getSubtitle());
        assertTrue(results.get(0).getMsgNeedToCopy().contains(firstWeek));
    }

    @Test
    void ignoresWordsThatOnlyStartWithTheCommand() {
        assertFalse(handler.matchEvent(param("call"), false));
        assertFalse(handler.matchEvent(param("cal 2026-11"), false));
    }

    private static String header() {
        StringBuilder header = new StringBuilder();
        for (String label : new String[] {"一", "二", "三", "四", "五", "六", "日"}) {
            header.append('\u3000').append('\u3000').append(label).append('\u3000');
        }
        return header.toString();
    }

    private static String week(int... days) {
        StringBuilder week = new StringBuilder();
        for (int day : days) {
            week.append(day == 31 ? "\uFF3B\uFF13\uFF11\uFF3D" : dayCell(day));
        }
        return week.toString();
    }

    private static String dayCell(int day) {
        if (day == 0) {
            return "\u3000\u3000\u3000\u3000";
        }
        String tens = day < 10 ? "\u3000" : String.valueOf((char) ('\uFF10' + day / 10));
        String ones = String.valueOf((char) ('\uFF10' + day % 10));
        return "\u3000" + tens + ones + "\u3000";
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
