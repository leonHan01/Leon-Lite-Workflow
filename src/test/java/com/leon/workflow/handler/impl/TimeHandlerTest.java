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

class TimeHandlerTest {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private TimeHandler timeHandler;
    private ZonedDateTime fixedNow;

    @BeforeEach
    void setUp() {
        timeHandler = new TimeHandler();
        fixedNow = ZonedDateTime.of(2026, 5, 30, 18, 0, 0, 0, BEIJING_ZONE);
        timeHandler.setClock(Clock.fixed(fixedNow.toInstant(), BEIJING_ZONE));
    }

    @Test
    void timestampMatchingRequiresTenOrThirteenDigits() {
        for (String input : List.of("1735689600", "1735689600000")) {
            AlfredParam param = new AlfredParam();
            param.setOriginParams(input);
            assertTrue(timeHandler.matchEvent(param, false));
            var results = timeHandler.doHandleAlfredEvent(param);
            assertEquals("2025-01-01 08:00:00", results.get(0).getMsgNeedToCopy());
            assertEquals("2025-01-01 00:00:00", results.get(1).getMsgNeedToCopy());
        }
        for (String input : List.of("123456789", "12345678901", "17356 89600", "1735689600+1")) {
            AlfredParam param = new AlfredParam();
            param.setOriginParams(input);
            assertFalse(timeHandler.matchEvent(param, false));
        }
    }

    @Test
    void shouldReturnBeijingTimeAndTimestampsForNow() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("now");

        List<AlfredResult> results = timeHandler.doHandleAlfredEvent(alfredParam);

        assertTimeResults(results, fixedNow, "2026-05-30 18:00:00");
    }

    @Test
    void shouldSupportMinuteOffset() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("now+1m");

        List<AlfredResult> results = timeHandler.doHandleAlfredEvent(alfredParam);

        assertTimeResults(results, fixedNow.plusMinutes(1), "2026-05-30 18:01:00");
    }

    @Test
    void shouldSupportChainedOffsets() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("now+1d+1h+1m");

        List<AlfredResult> results = timeHandler.doHandleAlfredEvent(alfredParam);

        assertTimeResults(results, fixedNow.plusDays(1).plusHours(1).plusMinutes(1), "2026-05-31 19:01:00");
    }

    @Test
    void shouldOnlyMatchNowExpressions() {
        AlfredParam validParam = new AlfredParam();
        validParam.setOriginParams("now+1d+1h+1m");
        AlfredParam invalidParam = new AlfredParam();
        invalidParam.setOriginParams("now+abc");

        assertTrue(timeHandler.matchEvent(validParam, false));
        assertFalse(timeHandler.matchEvent(invalidParam, false));
    }

    @Test
    void shouldConvertBeijingDateTimeToTimestamps() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams(" 2026-10-31 12:00:00 ");

        assertTrue(timeHandler.matchEvent(alfredParam, false));
        List<AlfredResult> results = timeHandler.doHandleAlfredEvent(alfredParam);

        assertEquals(3, results.size());
        assertEquals("北京时间：2026-10-31 12:00:00", results.get(0).getTitle());
        assertEquals("2026-10-31 12:00:00", results.get(0).getMsgNeedToCopy());
        assertEquals("秒级时间戳：1793419200", results.get(1).getTitle());
        assertEquals("1793419200", results.get(1).getMsgNeedToCopy());
        assertEquals("毫秒时间戳：1793419200000", results.get(2).getTitle());
        assertEquals("1793419200000", results.get(2).getMsgNeedToCopy());
    }

    @Test
    void shouldRejectIncompleteOrImpossibleDateTimes() {
        for (String input : List.of("2026-10-31", "2026-10-31 12:00", "2026-02-31 12:00:00", "2026-10-31 24:00:00")) {
            AlfredParam alfredParam = new AlfredParam();
            alfredParam.setOriginParams(input);
            assertFalse(timeHandler.matchEvent(alfredParam, false), input);
        }
    }

    private void assertTimeResults(List<AlfredResult> results, ZonedDateTime expectedDateTime, String expectedBeijingTime) {
        long expectedMillis = expectedDateTime.toInstant().toEpochMilli();
        long expectedSeconds = expectedMillis / 1000;

        assertEquals(3, results.size());
        assertEquals("北京时间：" + expectedBeijingTime, results.get(0).getTitle());
        assertEquals(expectedBeijingTime, results.get(0).getMsgNeedToCopy());
        assertEquals("秒级时间戳：" + expectedSeconds, results.get(1).getTitle());
        assertEquals(String.valueOf(expectedSeconds), results.get(1).getMsgNeedToCopy());
        assertEquals("毫秒时间戳：" + expectedMillis, results.get(2).getTitle());
        assertEquals(String.valueOf(expectedMillis), results.get(2).getMsgNeedToCopy());
    }
}
