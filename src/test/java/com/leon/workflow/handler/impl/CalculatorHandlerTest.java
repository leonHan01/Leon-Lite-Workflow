package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorHandlerTest {

    private final CalculatorHandler calculatorHandler = new CalculatorHandler();

    @Test
    void matchesOnlyExpressionsThatStartWithANumberParenthesisOrMinus() {
        assertTrue(calculatorHandler.matchEvent(param("1+2"), false));
        assertTrue(calculatorHandler.matchEvent(param("(1+2)*3"), false));
        assertFalse(calculatorHandler.matchEvent(param("abc"), false));
        assertFalse(calculatorHandler.matchEvent(param("  "), false));
        assertTrue(calculatorHandler.breakNextHandlerIfHaveResult());
    }

    @Test
    void shouldReturnRawResultWhenResultIsNotGreaterThanOneThousand() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("500+500");

        List<AlfredResult> results = calculatorHandler.doHandleAlfredEvent(alfredParam);

        assertEquals(1, results.size());
        assertEquals("500+500 = 1000", results.get(0).getTitle());
        assertEquals("1000", results.get(0).getMsgNeedToCopy());
    }

    @Test
    void shouldReturnFormattedAndRawResultsWhenResultIsGreaterThanOneThousand() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("1000+1");

        List<AlfredResult> results = calculatorHandler.doHandleAlfredEvent(alfredParam);

        assertEquals(2, results.size());
        assertEquals("1000+1 = 1,001", results.get(0).getTitle());
        assertEquals("1,001", results.get(0).getMsgNeedToCopy());
        assertEquals("1000+1 = 1001", results.get(1).getTitle());
        assertEquals("1001", results.get(1).getMsgNeedToCopy());
    }

    @Test
    void shouldReturnPercentAndRawResultsWhenResultIsBetweenZeroAndOne() {
        AlfredParam alfredParam = new AlfredParam();
        alfredParam.setOriginParams("0.9554");

        List<AlfredResult> results = calculatorHandler.doHandleAlfredEvent(alfredParam);

        assertEquals(2, results.size());
        assertEquals("0.9554 = 95.54%", results.get(0).getTitle());
        assertEquals("95.54%", results.get(0).getMsgNeedToCopy());
        assertEquals("0.9554 = 0.9554", results.get(1).getTitle());
        assertEquals("0.9554", results.get(1).getMsgNeedToCopy());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2+3*4,14", "(2+3)*4,20", "-(-2+3),-1", "6/-2,-3"})
    void keepsOperatorPrecedenceAndUnaryMinus(String expression, String expected) {
        AlfredParam input = new AlfredParam(); input.setOriginParams(expression);
        assertEquals(expected, calculatorHandler.doHandleAlfredEvent(input).getLast().getMsgNeedToCopy());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"(1+2", "1+", "1..2", "1/0", "0/0"})
    void invalidOrNonFiniteResultsUseTheExistingErrorHint(String expression) {
        AlfredParam input = new AlfredParam(); input.setOriginParams(expression);
        assertEquals("计算出错，请检查表达式", calculatorHandler.doHandleAlfredEvent(input).getFirst().getTitle());
    }

    @Test
    void sharedHandlerCalculatesIndependentExpressionsConcurrently() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.rangeClosed(1, 100).<java.util.concurrent.Callable<String>>mapToObj(i -> () -> {
                AlfredParam input = new AlfredParam(); input.setOriginParams(i + "*(2+3)-1");
                return calculatorHandler.doHandleAlfredEvent(input).getLast().getMsgNeedToCopy();
            }).toList();
            var results = pool.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) assertEquals(Integer.toString((i + 1) * 5 - 1), results.get(i).get());
        }
    }

    private static AlfredParam param(String input) {
        AlfredParam param = new AlfredParam();
        param.setOriginParams(input);
        return param;
    }
}
