package com.leon.workflow.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PayloadsTest {
    @Test void idsMustBeExactLongIntegers() {
        assertEquals(12L, Payloads.longValue(12));
        assertEquals(12L, Payloads.longValue("12"));
        assertEquals(12L, Payloads.longValue(new BigDecimal("12.0")));
        assertEquals(Long.MAX_VALUE, Payloads.longValue(Long.MAX_VALUE));
        for (Object invalid : List.of(1.9, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1.01"),
                new BigInteger("9223372036854775808"), "1.9")) {
            assertThrows(IllegalArgumentException.class, () -> Payloads.longValue(invalid));
        }
    }

    @Test void reorderCannotTargetATruncatedOrEmptyId() {
        assertEquals(List.of(1L, 2L), Payloads.longList(List.of(1, "2")));
        assertThrows(IllegalArgumentException.class, () -> Payloads.longList(List.of(1, 2.1)));
        assertThrows(IllegalArgumentException.class, () -> Payloads.longList(Arrays.asList(1, null)));
        assertThrows(IllegalArgumentException.class, () -> Payloads.longList(List.of("")));
    }
}
