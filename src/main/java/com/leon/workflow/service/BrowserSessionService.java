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

@Service
public class BrowserSessionService {
    public static final String COOKIE_NAME = "__Host-leon_session";
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
        session.setExpiresAt(now.plus(remember ? Duration.ofDays(30) : Duration.ofHours(12)));
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

    @Transactional
    public void revoke(HttpServletRequest request) {
        String value = cookie(request);
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
