package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Login uses the service token. Subsequent chat requests use a scoped personal session. */
@RestController
@RequestMapping({"/api/chat-users","/api/conversations/account"})
public class ChatUserController {
    private final ChatUserRegistry users;
    private final ChatAccess access;
    public record LoginRequest(String username) {}

    public ChatUserController(ChatUserRegistry users,ChatAccess access){
        this.users=users;
        this.access=access;
    }
    @PostMapping("/login") public ResponseEntity<ChatUserRegistry.Login> login(@RequestBody LoginRequest input,HttpServletRequest request){
        if(ChatUserRegistry.requestUser(request)!=null)throw DeviceMessageStore.failure(HttpStatus.FORBIDDEN,"请使用服务访问令牌登录");
        var login=users.login(input.username());
        access.ensureHome(login.user());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login);
    }
    @GetMapping("/me") public ResponseEntity<ChatUserRegistry.User> me(HttpServletRequest request){
        var user=ChatUserRegistry.requestUser(request);
        if(user==null)throw DeviceMessageStore.failure(HttpStatus.UNAUTHORIZED,"请先登录");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
    }
    @PostMapping("/logout") public ResponseEntity<?> logout(HttpServletRequest request,@RequestHeader("Authorization") String authorization){
        if(ChatUserRegistry.requestUser(request)==null)throw DeviceMessageStore.failure(HttpStatus.UNAUTHORIZED,"请先登录");
        users.logout(authorization.substring(7));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("signedOut",true));
    }
}
