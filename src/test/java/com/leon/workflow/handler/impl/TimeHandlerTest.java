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
