package com.leon.workflow;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsUtils;
import com.leon.workflow.service.BrowserSessionService;
import com.leon.workflow.messaging.AgentAvatarController;
import com.leon.workflow.messaging.AgentContextController;
import com.leon.workflow.messaging.AgentController;
import com.leon.workflow.messaging.ChatBackupController;
import com.leon.workflow.messaging.ChatImageController;
import com.leon.workflow.messaging.ChatUserController;
import com.leon.workflow.messaging.ChatUserRegistry;
import com.leon.workflow.messaging.DeviceMessageController;
import com.leon.workflow.messaging.DevicePresenceController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private final byte[] expectedHash;
    private final String adminOrigin;
    private final BrowserSessionService sessions;

    public ApiTokenInterceptor(@Value("${leon.auth.token-sha256:}") String configuredHash,
                               @Value("${leon.auth.required:false}") boolean required,
                               @Value("${leon.auth.admin-origin:}") String adminOrigin,
                               BrowserSessionService sessions) {
        this.adminOrigin = adminOrigin.trim();
        this.sessions = sessions;
        String value = configuredHash.trim();
        if (value.isEmpty() && !required) {
            expectedHash = null;
            return;
        }
        if (!value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("API authentication requires a valid SHA-256 token hash");
        }
        expectedHash = HexFormat.of().parseHex(value);
    }

    private ChatUserRegistry chatUsers;

    @Autowired void setChatUsers(ChatUserRegistry chatUsers){this.chatUsers=chatUsers;}

    private static final Set<Class<?>> CHAT_CONTROLLERS=Set.of(
        AgentContextController.class,ChatBackupController.class,
        ChatImageController.class,DeviceMessageController.class);

    private static boolean chatSessionEndpoint(Object handler){
        if(!(handler instanceof HandlerMethod method))return false;
        var type=method.getBeanType();
        if(CHAT_CONTROLLERS.contains(type))return true;
        String name=method.getMethod().getName();
        if(type==ChatUserController.class)return name.equals("me")||name.equals("logout");
        if(type==AgentAvatarController.class)return name.equals("catalog")||name.equals("image");
        if(type==DevicePresenceController.class)return name.equals("heartbeat");
        if(type==AgentController.class)return Set.of("agents","usage","conversations","group","members","addMembers","addAgents","direct","thread").contains(name);
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if(CorsUtils.isPreFlightRequest(request))return true;
        String header = request.getHeader("Authorization");
        if(header!=null&&header.startsWith("Bearer chat_")&&chatSessionEndpoint(handler)&&chatUsers!=null){
            try{
                ChatUserRegistry.setRequestUser(request,chatUsers.authenticate(header.substring(7)));
                return true;
            }catch(ResponseStatusException error){
                response.setStatus(401);
                response.setHeader("Cache-Control","no-store");
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"登录已过期，请重新登录\"}");
                return false;
            }
        }
        if(expectedHash==null){
            // A chat session must never be accepted as the service credential, even in development.
            if(header!=null&&header.startsWith("Bearer chat_")){response.setStatus(403);return false;}
            return true;
        }
        if (header != null && header.startsWith("Bearer ") && header.length() <= 1024) {
            byte[] actual = sha256(header.substring(7));
            if (MessageDigest.isEqual(expectedHash, actual)) return true;
        }
        if (header != null && header.startsWith("Basic ") && header.length() <= 1024) {
            try {
                String credentials = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                int colon = credentials.indexOf(':');
                if (colon > 0 && "leon".equals(credentials.substring(0, colon)) &&
                        MessageDigest.isEqual(expectedHash, sha256(credentials.substring(colon + 1)))) {
                    if (isSafeMethod(request.getMethod()) || validAdminWrite(request)) return true;
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return false;
                }
            } catch (IllegalArgumentException ignored) {
                // Treat malformed Basic credentials as an authentication failure.
            }
        }
        if (validBrowserSession(request)) {
            if (isSafeMethod(request.getMethod()) || validAdminWrite(request)) {
                String cookie = sessions.refreshedCookie(request, fingerprint());
                if (cookie != null) response.addHeader("Set-Cookie", cookie);
                return true;
            }
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"message\":\"Authentication required\"}");
        return false;
    }

    public boolean validAdminWrite(HttpServletRequest request) {
        return !adminOrigin.isEmpty() && adminOrigin.equals(request.getHeader("Origin"))
                && "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    public boolean enabled() { return expectedHash != null; }

    public String fingerprint() { return expectedHash == null ? "" : HexFormat.of().formatHex(expectedHash); }

    public boolean validToken(String token) {
        return expectedHash != null && token != null && token.length() <= 1024
                && MessageDigest.isEqual(expectedHash, sha256(token));
    }

    public boolean validBrowserSession(HttpServletRequest request) {
        return expectedHash != null && sessions.valid(request, fingerprint());
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
