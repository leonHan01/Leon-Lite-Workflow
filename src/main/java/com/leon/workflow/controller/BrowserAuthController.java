package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class BrowserAuthController {
    private final ApiTokenInterceptor auth;
    private final BrowserSessionService sessions;

    public BrowserAuthController(ApiTokenInterceptor auth, BrowserSessionService sessions) {
        this.auth = auth; this.sessions = sessions;
    }

    public record LoginRequest(String token, boolean remember) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        if (!auth.enabled() || !auth.validAdminWrite(request)) return ResponseEntity.status(403).build();
        if (!auth.validToken(body.token())) return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                .body(Map.of("success", false, "message", "访问令牌不正确"));
        String value = sessions.create(body.remember(), auth.fingerprint());
        sessions.revoke(request);
        var cookie = ResponseCookie.from(BrowserSessionService.COOKIE_NAME, value)
                .secure(true).httpOnly(true).sameSite("Lax").path("/");
        String cookieHeader = body.remember() ? BrowserSessionService.persistentCookie(value) : cookie.build().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookieHeader)
                .body(Map.of("success", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        if (!auth.validAdminWrite(request)) return ResponseEntity.status(403).build();
        sessions.revoke(request);
        String cookie = ResponseCookie.from(BrowserSessionService.COOKIE_NAME, "")
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie).build();
    }

    @GetMapping("/check")
    public ResponseEntity<Void> check(HttpServletRequest request) {
        return ResponseEntity.status(auth.validBrowserSession(request) ? 204 : 401).cacheControl(CacheControl.noStore()).build();
    }
}
