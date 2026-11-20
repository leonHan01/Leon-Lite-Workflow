package com.leon.workflow.messaging;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AgentChatService {
    private final DeviceMessageStore store;
    private final AgentRegistry agents;
    private static final Pattern MENTION=Pattern.compile("(?<![\\p{L}\\p{N}_])@([a-z][a-z0-9_-]{1,31})(?![\\p{L}\\p{N}_-])",Pattern.CASE_INSENSITIVE);
    public AgentChatService(DeviceMessageStore store,AgentRegistry agents) { this.store=store; this.agents=agents; }
    public List<DeviceMessageStore.Conversation> conversations() {
        return store.conversations().stream().map(c -> {
            if(c.kind().equals("direct")&&(c.id().equals("direct:"+c.agentId())||c.id().startsWith("dm:")) && !store.hasAutomaticTitle(c.id())) {
                var agent=agents.get(c.agentId()); return new DeviceMessageStore.Conversation(c.id(),agent.name(),c.kind(),c.agentId(),c.agentIds(),c.ownerId());
            }
            if(ChatAccess.personalRoom(c.id())) return new DeviceMessageStore.Conversation(c.id(),c.name(),c.kind(),null,agents.summaries().stream().filter(AgentRegistry.Summary::enabled).map(AgentRegistry.Summary::id).toList(),c.ownerId());
            return c;
        }).toList();
    }
    public DeviceMessageStore.Conversation group(String name,List<String> ids) {
        List<String> members=ids==null?List.of():ids.stream().map(DeviceMessageStore::uuid).distinct().toList();
        for(String id:members) active(id); return store.createGroup(name,members);
    }
    public DeviceMessageStore.Conversation direct(String id) { return store.direct(active(id)); }
    public DeviceMessageStore.Conversation createDirect(String id,String requestId,String name) { return store.createDirect(active(id),requestId,name); }
    public DeviceMessageStore.Message send(DeviceMessageStore.Send input) { return send(input,null); }
    DeviceMessageStore.Message send(DeviceMessageStore.Send input,String userId) {
        synchronized(store) {
            var retry=store.retry(input,userId); if(retry!=null) return retry;
            var conversation=store.conversation(DeviceMessageStore.conversationId(input));
            var targetIds=new LinkedHashSet<String>();
            if(conversation.kind().equals("direct")) targetIds.add(conversation.agentId());
            else {
                if(input.mentionAgentIds()!=null) for(String id:input.mentionAgentIds()) targetIds.add(DeviceMessageStore.uuid(id));
                var matcher=MENTION.matcher(input.text());
                var byHandle=new HashMap<String,String>();
                for(var agent:agents.summaries()) if(agent.enabled()) byHandle.put(agent.handle(),agent.id());
                while(matcher.find()) { String id=byHandle.get(matcher.group(1).toLowerCase(Locale.ROOT)); if(id!=null) targetIds.add(id); }
                if(!ChatAccess.personalRoom(conversation.id())&&!conversation.agentIds().containsAll(targetIds)) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"被 @ 的 Agent 不在此群聊中");
            }
            if(targetIds.size()>4) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"每条消息最多 @4 个 Agent");
            var targets=targetIds.stream().map(this::active).toList();
            if(!DeviceMessageStore.imageIds(input).isEmpty()&&targets.stream().anyMatch(a->!a.provider().equals("openai")))
                throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"当前 Agent 的模型不支持图片，请选择支持视觉输入的 OpenAI 模型");
            return store.submit(input,targets,userId);
        }
    }
    /** Publish a management-page or workflow message as the Agent without starting a model reply. */
    public DeviceMessageStore.Message publishAgentMessage(String agentId,String conversationId,String text,String messageId) {
        synchronized(store) {
            var agent=active(agentId);
            var room=conversationId==null||conversationId.isBlank()?store.direct(agent):store.conversation(conversationId);
            if(room.kind().equals("direct")&&!agent.id().equals(room.agentId()) || room.kind().equals("group")&&!room.id().equals("shared")&&!room.agentIds().contains(agent.id()))
                throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"所选 Agent 不在目标会话中");
            return store.publishAgent(agent,room.id(),text,messageId);
        }
    }
    AgentRegistry.Agent active(String id) {
        var agent=agents.get(id);
        if(!agent.enabled()) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"Agent 已停用，请选择其他 Agent"); return agent;
    }
}
