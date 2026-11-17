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
            if(c.kind().equals("direct")) {
                var agent=agents.get(c.agentId()); return new DeviceMessageStore.Conversation(c.id(),agent.name(),c.kind(),c.agentId(),c.agentIds());
            }
            if(c.id().equals("shared")) return new DeviceMessageStore.Conversation(c.id(),c.name(),c.kind(),null,agents.summaries().stream().filter(AgentRegistry.Summary::enabled).map(AgentRegistry.Summary::id).toList());
            return c;
        }).toList();
    }
    public DeviceMessageStore.Conversation group(String name,List<String> ids) {
        List<String> members=ids==null?List.of():ids.stream().map(DeviceMessageStore::uuid).distinct().toList();
        for(String id:members) active(id); return store.createGroup(name,members);
    }
    public DeviceMessageStore.Conversation direct(String id) { return store.direct(active(id)); }
    public DeviceMessageStore.Message send(DeviceMessageStore.Send input) {
        synchronized(store) {
            var retry=store.retry(input); if(retry!=null) return retry;
            var conversation=store.conversation(DeviceMessageStore.conversationId(input));
            var targetIds=new LinkedHashSet<String>();
            if(conversation.kind().equals("direct")) targetIds.add(conversation.agentId());
            else {
                if(input.mentionAgentIds()!=null) for(String id:input.mentionAgentIds()) targetIds.add(DeviceMessageStore.uuid(id));
                var matcher=MENTION.matcher(input.text());
                var byHandle=new HashMap<String,String>();
                for(var agent:agents.summaries()) if(agent.enabled()) byHandle.put(agent.handle(),agent.id());
                while(matcher.find()) { String id=byHandle.get(matcher.group(1).toLowerCase(Locale.ROOT)); if(id!=null) targetIds.add(id); }
                if(!conversation.id().equals("shared")&&!conversation.agentIds().containsAll(targetIds)) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"被 @ 的 Agent 不在此群聊中");
            }
            if(targetIds.size()>4) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"每条消息最多 @4 个 Agent");
            var targets=targetIds.stream().map(this::active).toList();
            return store.submit(input,targets);
        }
    }
    private AgentRegistry.Agent active(String id) {
        var agent=agents.get(id);
        if(!agent.enabled()) throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"Agent 已停用，请选择其他 Agent"); return agent;
    }
}
