package com.leon.workflow.messaging;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.automation.WorkflowModels;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AgentController {
    private final WorkflowModels models;
    private final AgentRegistry agents;
    private final AgentChatService chat;
    private final ApiTokenInterceptor auth;
    private final AgentAvatarService avatars;
    private final DeviceMessageStore store;
    private final ChatAccess access;

    public AgentController(AgentRegistry agents,AgentChatService chat,ApiTokenInterceptor auth,AgentAvatarService avatars,
                           WorkflowModels models,DeviceMessageStore store,ChatAccess access){
        this.models=models;this.agents=agents;this.chat=chat;this.auth=auth;this.avatars=avatars;
        this.store=store;this.access=access;
    }
    @GetMapping("/api/agents") public ResponseEntity<?> agents() { return response(agents.summaries()); }
    @GetMapping("/api/agents/usage") public ResponseEntity<?> usage(HttpServletRequest request) {
        return response(models.userUsageSummaries(access.user(request).id()));
    }
    @GetMapping("/api/conversations") public ResponseEntity<?> conversations(HttpServletRequest request) {
        var user=access.user(request);
        return response(chat.conversations().stream().filter(room->access.canRead(user,room.id())).toList());
    }
    public record Group(String name,List<String> agentIds,List<String> usernames) {}
    @PostMapping("/api/conversations/groups") public ResponseEntity<?> group(@RequestBody Group input,HttpServletRequest request) {
        var user=access.user(request);
        var members=access.invitees(input.usernames());
        synchronized(store){
            return response(store.atomic(()->{
                var room=chat.group(input.name(),input.agentIds());
                access.setOwner(room.id(),user);
                access.addMembers(room.id(),members);
                return store.conversation(room.id());
            }));
        }
    }

    public record Members(List<String> usernames) {}
    @GetMapping("/api/conversations/{id}/members") public ResponseEntity<?> members(@PathVariable String id,HttpServletRequest request){
        return response(access.members(request,id));
    }
    @PostMapping("/api/conversations/{id}/members") public ResponseEntity<?> addMembers(
            @PathVariable String id,@RequestBody Members input,HttpServletRequest request){
        String roomId=access.room(request,id,true);
        synchronized(store){
            var room=store.conversation(roomId);
            if(!room.kind().equals("group")||ChatAccess.personalRoom(roomId))
                throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"请新建群聊后添加用户");
            var users=access.invitees(input.usernames());
            return response(store.atomic(()->{
                access.addMembers(roomId,users);
                return access.members(request,roomId);
            }));
        }
    }

    @PostMapping("/api/conversations/direct/{agentId}") public ResponseEntity<?> direct(@PathVariable String agentId,HttpServletRequest request) {
        var user=access.user(request);
        var agent=chat.active(agentId);
        String id=access.legacy(user)?"direct:"+agentId:"dm:"+user.id()+":"+agentId;
        synchronized(store){
            return response(store.atomic(()->{
                var room=store.direct(agent,id);
                access.setOwner(id,user);
                return store.conversation(room.id());
            }));
        }
    }
    public record ThreadRequest(String requestId,String name) {}
    @PostMapping("/api/conversations/direct/{agentId}/threads") public ResponseEntity<?> thread(
            @PathVariable String agentId,@RequestBody ThreadRequest input,HttpServletRequest request) {
        var user=access.user(request);
        var agent=chat.active(agentId);
        String key=DeviceMessageStore.uuid(input.requestId());
        String id=access.legacy(user)?"thread:"+key:"thread:"+user.id()+":"+key;
        synchronized(store){
            return response(store.atomic(()->{
                var room=store.createNamedDirect(agent,id,input.name());
                access.setOwner(id,user);
                return store.conversation(room.id());
            }));
        }
    }
    @GetMapping("/api/agent-admin") public ResponseEntity<?> admin(HttpServletRequest request) { adminOnly(request);return response(Map.of("agents",agents.list(),"keyStorageEnabled",agents.keyStorageEnabled(),"avatars",avatars.catalog(),"models",models.list())); }
    @PostMapping("/api/agent-admin") public ResponseEntity<?> create(@RequestBody AgentRegistry.Edit input,HttpServletRequest request) { adminOnly(request);return response(agents.save(null,input)); }
    @PutMapping("/api/agent-admin/{id}") public ResponseEntity<?> update(@PathVariable String id,@RequestBody AgentRegistry.Edit input,HttpServletRequest request) { adminOnly(request);return response(agents.save(id,input)); }
    public record AppMessage(String agentId,String conversationId,String text,String clientMessageId) {}
    @PostMapping("/api/agent-admin/messages") public ResponseEntity<?> sendToApp(@RequestBody AppMessage input,HttpServletRequest request) {
        adminOnly(request);
        if(auth.enabled()&&!auth.validAdminWrite(request)) throw AgentRegistry.failure(HttpStatus.FORBIDDEN,"请从本站管理页面发送消息");
        return response(chat.publishAgentMessage(DeviceMessageStore.uuid(input.agentId()),input.conversationId(),input.text(),DeviceMessageStore.uuid(input.clientMessageId())));
    }
    private void adminOnly(HttpServletRequest request) { if(auth.enabled()&&!auth.validBrowserSession(request)) throw AgentRegistry.failure(HttpStatus.FORBIDDEN,"请使用管理页面登录后配置 Agent"); }
    private ResponseEntity<?> response(Object value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
