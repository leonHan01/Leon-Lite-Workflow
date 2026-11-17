package com.leon.workflow.messaging;

import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.TimeUnit;

@RestController
public class AgentAvatarController {
    private final AgentAvatarService avatars;
    private final ApiTokenInterceptor auth;
    public AgentAvatarController(AgentAvatarService avatars,ApiTokenInterceptor auth){this.avatars=avatars;this.auth=auth;}
    @GetMapping("/api/agent-avatars") public ResponseEntity<?> catalog(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.catalog());}
    @GetMapping("/api/agent-avatars/{id}") public ResponseEntity<byte[]> image(@PathVariable String id){
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff")
            .cacheControl(CacheControl.maxAge(1,TimeUnit.DAYS).cachePrivate()).body(avatars.image(id));
    }
    @PostMapping(value="/api/agent-admin/avatars",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,HttpServletRequest request){
        if(auth.enabled()&&!auth.validBrowserSession(request))throw AgentRegistry.failure(HttpStatus.FORBIDDEN,"请使用管理页面登录后上传头像");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.upload(file));
    }
}
