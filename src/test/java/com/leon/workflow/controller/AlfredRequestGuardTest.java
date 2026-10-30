package com.leon.workflow.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AlfredRequestGuardTest {
    @Test
    void verifiesRawInputBeforeConsumingRateLimit() throws Exception {
        AlfredRequestGuard guard = new AlfredRequestGuard("test-secret", () -> 0L);
        String raw = " 1+2 ";
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest((raw + "test-secret").getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 25; i++) {
            assertEquals(401, assertThrows(ResponseStatusException.class, () -> guard.check(raw, "invalid")).getStatusCode().value());
        }
        for (int i = 0; i < 20; i++) guard.check(raw, signature);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> guard.check(raw, signature)).getStatusCode().value());
    }

    @Test
    void refillsAtTwentyRequestsPerSecondWithoutSleeping() {
        AtomicLong clock = new AtomicLong();
        AlfredRequestGuard guard = new AlfredRequestGuard("", clock::get);
        for (int i = 0; i < 20; i++) guard.check("", null);
        assertThrows(ResponseStatusException.class, () -> guard.check("", null));
        clock.set(50_000_000L);
        assertDoesNotThrow(() -> guard.check("", null));
        assertThrows(ResponseStatusException.class, () -> guard.check("", null));
    }
}
