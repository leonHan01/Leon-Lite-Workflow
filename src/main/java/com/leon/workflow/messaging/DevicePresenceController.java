package com.leon.workflow.messaging;

import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class DevicePresenceController {
    private final DevicePresenceService presence;
    private final ApiTokenInterceptor auth;
    public DevicePresenceController(DevicePresenceService presence,ApiTokenInterceptor auth) { this.presence=presence;this.auth=auth; }
    @PostMapping("/api/device-messages/presence") public ResponseEntity<?> heartbeat(@RequestBody DevicePresenceService.Heartbeat input,HttpServletRequest request) {
        var user=ChatUserRegistry.requestUser(request);
        var identity=user==null?input:new DevicePresenceService.Heartbeat(input.deviceId(),user.username(),input.platform());
        var device=presence.heartbeat(identity);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("lastSeenAt",device.lastSeenAt()));
    }
    @GetMapping("/api/agent-admin/devices") public ResponseEntity<?> online(HttpServletRequest request) {
        if(auth.enabled()&&!auth.validBrowserSession(request)) throw DeviceMessageStore.failure(HttpStatus.FORBIDDEN,"请登录控制台后查看在线设备");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(presence.online());
    }
}
