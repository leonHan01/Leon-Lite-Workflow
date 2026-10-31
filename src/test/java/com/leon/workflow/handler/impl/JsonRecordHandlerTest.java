package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.JsonRecord;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.domain.dto.ParsedJsonRecord;
import com.leon.workflow.service.JsonRecordService;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonRecordHandlerTest {

    private final JsonRecordService service = mock(JsonRecordService.class);
    private final JsonRecordHandler handler = new JsonRecordHandler(service);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "baseUrl", "http://127.0.0.1:8080/");
    }

    @Test
    void savesValidJsonAndOpensTheRecordPage() {
        ParsedJsonRecord parsed = new ParsedJsonRecord();
        parsed.setBodyJson("{\"a\":1}");
        when(service.parse("{\"a\":1}")).thenReturn(parsed);
        JsonRecord saved = new JsonRecord();
        saved.setId(42L);
        when(service.save("{\"a\":1}")).thenReturn(saved);

        assertTrue(handler.matchEvent(param("{\"a\":1}"), false));
        List<AlfredResult> results = handler.doHandleAlfredEvent(param("{\"a\":1}"));

        assertEquals("http://127.0.0.1:8080/json-record.html?id=42", results.getFirst().getUri());
        assertEquals("openurl", results.getFirst().getType());
        assertEquals("JSON 已保存，打开格式化页面", results.getFirst().getSubtitle());
        assertTrue(handler.breakNextHandlerIfHaveResult());
    }

    @Test
    void invalidOrBlankInputDoesNotSave() {
        when(service.parse("hello")).thenReturn(new ParsedJsonRecord());

        assertFalse(handler.matchEvent(param("hello"), false));
        assertTrue(handler.doHandleAlfredEvent(param("  ")).isEmpty());
        verify(service, never()).save(org.mockito.ArgumentMatchers.anyString());
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
