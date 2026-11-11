package com.leon.workflow.service;

import com.leon.workflow.dao.entity.BrowserSession;
import com.leon.workflow.dao.repository.BrowserSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.ResponseCookie;

@Service
public class BrowserSessionService {
    public static final String COOKIE_NAME = "__Host-leon_session";
    private static final Instant PERMANENT = Instant.parse("9999-12-31T23:59:59Z");
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(400);
    private final BrowserSessionRepository repository;
    private final SecureRandom random = new SecureRandom();

    public BrowserSessionService(BrowserSessionRepository repository) { this.repository = repository; }

    @Transactional
    public String create(boolean remember, String fingerprint) {
        Instant now = Instant.now();
        repository.deleteByExpiresAtBefore(now);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        BrowserSession session = new BrowserSession();
        session.setId(hash(value));
        session.setTokenFingerprint(fingerprint);
        session.setExpiresAt(remember ? PERMANENT : now.plus(Duration.ofHours(12)));
        repository.save(session);
        return value;
    }

    @Transactional(readOnly = true)
    public boolean valid(HttpServletRequest request, String fingerprint) {
        String cookie = cookie(request);
        if (cookie == null) return false;
        return repository.findById(hash(cookie))
                .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
                .filter(session -> MessageDigest.isEqual(session.getTokenFingerprint().getBytes(StandardCharsets.US_ASCII),
                        fingerprint.getBytes(StandardCharsets.US_ASCII))).isPresent();
    }

    public static String persistentCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value).secure(true).httpOnly(true)
                .sameSite("Lax").path("/").maxAge(COOKIE_LIFETIME).build().toString();
    }

    @Transactional(readOnly = true)
    public String refreshedCookie(HttpServletRequest request, String fingerprint) {
        String value = cookie(request);
        if (value == null) return null;
        return repository.findById(hash(value))
                .filter(session -> PERMANENT.equals(session.getExpiresAt()))
                .filter(session -> MessageDigest.isEqual(session.getTokenFingerprint().getBytes(StandardCharsets.US_ASCII),
                        fingerprint.getBytes(StandardCharsets.US_ASCII)))
                .map(session -> persistentCookie(value)).orElse(null);
    }

    @Transactional
    public void revoke(HttpServletRequest request) {
        String value = cookie(request);
        revokeValue(value);
    }

    @Transactional
    public void revokeValue(String value) {
        if (value != null) repository.deleteById(hash(value));
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}")) return cookie.getValue();
            }
        }
        return null;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
