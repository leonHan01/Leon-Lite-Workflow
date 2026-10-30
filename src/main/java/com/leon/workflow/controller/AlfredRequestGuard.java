package com.leon.workflow.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongSupplier;

/** Owns the legacy raw-input signature and shared token bucket for Alfred requests. */
@Component
public class AlfredRequestGuard {
    private final String apiKey;
    private final LongSupplier nanoTime;
    private double tokens = 20;
    private long lastRefill;

    @Autowired
    public AlfredRequestGuard(@Value("${leon.alfred.api-key:}") String apiKey) {
        this(apiKey, System::nanoTime);
    }

    AlfredRequestGuard(String apiKey, LongSupplier nanoTime) {
        this.apiKey = apiKey;
        this.nanoTime = nanoTime;
        this.lastRefill = nanoTime.getAsLong();
    }

    public void check(String raw, String suppliedKey) {
        authenticate(raw, suppliedKey);
        checkRateLimit();
    }

    private synchronized void checkRateLimit() {
        long now = nanoTime.getAsLong();
        tokens = Math.min(20, tokens + (now - lastRefill) / 1_000_000_000.0 * 20);
        lastRefill = now;
        if (tokens < 1) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Alfred rate limit exceeded");
        tokens -= 1;
    }

    private void authenticate(String param, String supplied) {
        if (apiKey.isBlank()) return;
        try {
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest((param + apiKey).getBytes(StandardCharsets.UTF_8)));
            if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Alfred key");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
