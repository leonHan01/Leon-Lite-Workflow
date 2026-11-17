package com.leon.workflow.automation;

import com.leon.workflow.messaging.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Local delivery persists an Agent message; it neither invokes a model nor queues a reply. */
@Component
public class WorkflowAppMessage {
    private final AgentChatService chat;
    private final AgentRegistry agents;
    public WorkflowAppMessage(AgentChatService chat,AgentRegistry agents) { this.chat=chat;this.agents=agents; }
    public Map<String,Object> targets() { return Map.of("agents",agents.summaries(),"conversations",chat.conversations()); }
    public static void validateConfig(Map<String,Object> config) {
        String id=required(config.get("agentId"),"请选择发送消息的 Agent",36);
        if(!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Agent 引用无效");
        String target=text(config.getOrDefault("targetType","direct"));
        if(!Set.of("direct","group").contains(target)) throw new IllegalArgumentException("请选择 Agent 私聊或群聊");
        if(target.equals("group")) {
            String room=required(config.get("conversationId"),"请选择目标群聊",80);
            if(!room.equals("shared")&&!room.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("群聊引用无效");
        }
        required(config.get("text"),"请填写 App 消息正文，最多 8000 字符",8000);
    }
    public Map<String,Object> execute(Map<String,Object> config,Map<String,Object> input,Map<String,Object> ctx,WorkflowPrivacy privacy,Consumer<String> trace) throws InterruptedException {
        validateConfig(config);
        if(((List<?>)input.get("items")).isEmpty()) return input;
        String content=required(WorkflowExpressions.resolveRequired(config.get("text"),input,ctx,"App 消息正文"),"App 消息正文解析后需要 1–8000 字符",8000);
        String run=required(ctx.get("runId"),"缺少工作流实例标识",100), node=required(ctx.get("nodeId"),"缺少节点标识",64);
        String id=UUID.nameUUIDFromBytes(("workflow-app-message:"+run+":"+node).getBytes(StandardCharsets.UTF_8)).toString();
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var message=chat.publishAgentMessage(text(config.get("agentId")).toLowerCase(Locale.ROOT),"group".equals(config.get("targetType"))?text(config.get("conversationId")):null,privacy.message(content),id);
        trace.accept("App 消息已保存，消息 ID="+message.id()+"；在线设备轮询后可见");
        return input;
    }
}
