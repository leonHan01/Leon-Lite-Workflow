package com.leon.workflow.service;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AlfredEventHandler;
import com.leon.workflow.handler.impl.DefaultHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.RUN_SCRIPT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlfredQueryServiceTest {
    private final DefaultHandler fallback = mock(DefaultHandler.class);

    @Test
    void emptyResultDoesNotShortCircuitAndLaterHandlerSeesExistingResults() {
        AlfredEventHandler empty = handler(0, List.of(), true);
        AlfredEventHandler first = handler(1, List.of(result("first")), false);
        AlfredEventHandler stop = handler(2, List.of(result("stop")), true);
        AlfredEventHandler ignored = mock(AlfredEventHandler.class);
        when(ignored.getPriority()).thenReturn(3);
        AlfredQueryService service = new AlfredQueryService(List.of(ignored, stop, first, empty), fallback);

        assertEquals(List.of("first", "stop"), service.query("  query words  ", "employee")
                .stream().map(AlfredResult::getTitle).toList());

        var order = inOrder(empty, first, stop);
        order.verify(empty).matchEvent(any(), eq(false));
        order.verify(empty).handleAlfredEvent(any());
        order.verify(first).matchEvent(any(), eq(false));
        order.verify(first).handleAlfredEvent(any());
        order.verify(stop).matchEvent(argThat(p -> p.getParams().equals(List.of("query", "words"))
                && "employee".equals(p.getEmployeeId())), eq(true));
        verify(ignored, never()).matchEvent(any(), anyBoolean());
        verifyNoInteractions(fallback);
    }

    @Test
    void blankInputUsesOnlyFallbackAndNormalizesDefaults() {
        AlfredEventHandler handler = mock(AlfredEventHandler.class);
        when(handler.getPriority()).thenReturn(1);
        when(fallback.handleAlfredEvent(any())).thenReturn(List.of(result("home")));

        AlfredResult result = new AlfredQueryService(List.of(handler), fallback).query("  ", " ").getFirst();

        assertEquals("icons/default.png", result.getIcon());
        assertEquals("home", result.getAutoComplete());
        assertEquals("", result.getArg());
        assertEquals("", result.getSubtitle());
        assertEquals("version:20240104", result.getFeatures());
        verify(fallback).handleAlfredEvent(argThat(p -> "DEFAULT".equals(p.getEmployeeId())));
        verify(handler, never()).matchEvent(any(), anyBoolean());
    }

    @Test
    void noMatchesUsesFallback() {
        AlfredEventHandler handler = handler(1, List.of(), false);
        when(fallback.handleAlfredEvent(any())).thenReturn(List.of(result("fallback")));
        assertEquals("fallback", new AlfredQueryService(List.of(handler), fallback).query("query", null).getFirst().getTitle());
    }

    @Test
    void capsResultsAndPreservesScriptSemantics() {
        List<AlfredResult> results = IntStream.range(0, 25).mapToObj(i -> result("result" + i)).toList();
        results.getFirst().setType(RUN_SCRIPT);
        results.getFirst().setIcon("script.png");
        results.getFirst().setAutoComplete("custom");
        AlfredEventHandler handler = handler(1, results, true);

        List<AlfredResult> found = new AlfredQueryService(List.of(handler), fallback).query(" query ", null);

        assertEquals(20, found.size());
        assertNull(found.getFirst().getArg());
        assertEquals("query", found.get(1).getArg());
        assertEquals("icons/script.png", found.getFirst().getIcon());
        assertEquals("custom", found.getFirst().getAutoComplete());
    }

    private AlfredEventHandler handler(int priority, List<AlfredResult> results, boolean stop) {
        AlfredEventHandler handler = mock(AlfredEventHandler.class);
        when(handler.getPriority()).thenReturn(priority);
        when(handler.matchEvent(any(AlfredParam.class), anyBoolean())).thenReturn(true);
        when(handler.handleAlfredEvent(any())).thenReturn(results);
        lenient().when(handler.breakNextHandlerIfHaveResult()).thenReturn(stop);
        return handler;
    }

    private AlfredResult result(String title) { return AlfredResult.builder().title(title).build(); }
}
