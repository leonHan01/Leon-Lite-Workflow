package com.leon.workflow.messaging;

import jakarta.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AgentReplyEngine {
    private final DeviceMessageStore store; private final AgentRegistry agents; private final AgentModelClient model;
    private final boolean enabled;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"agent-dispatch");t.setDaemon(true);return t;});
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{var t=new Thread(r,"agent-reply");t.setDaemon(true);return t;});
    private final AtomicInteger running=new AtomicInteger();
    public AgentReplyEngine(DeviceMessageStore store,AgentRegistry agents,AgentModelClient model,@Value("${leon.agents.dispatch-enabled:true}") boolean enabled) { this.store=store;this.agents=agents;this.model=model;this.enabled=enabled; }
    @PostConstruct void start() {
        store.recoverInterrupted();
        if(enabled) timer.scheduleWithFixedDelay(this::dispatch,500,500,TimeUnit.MILLISECONDS);
    }
    private void dispatch() {
        try {
            while(running.get()<4) {
                var work=store.claim(); if(work==null) return; running.incrementAndGet();
                workers.execute(()->{try { execute(work); } finally { running.decrementAndGet(); }});
            }
        } catch (Exception ignored) { /* Retry dispatch, never log conversation or credential data. */ }
    }
    // Deterministic entry for integration tests; normal operation uses the bounded worker pool.
    public boolean runNext() { var work=store.claim();if(work==null)return false;execute(work);return true; }
    private void execute(DeviceMessageStore.Work work) {
        try {
            var config=agents.runtime(work.agentId()); var agent=config.agent;
            if(!agent.enabled()) { store.fail(work,"Agent 已停用，请启用后手动重试");return; }
            var messages=new ArrayList<Map<String,String>>();
            String instruction=agent.systemPrompt().isBlank()?"你是 "+agent.name()+"，请清晰、准确地回答用户。":agent.systemPrompt();
            messages.add(Map.of("role",agent.provider().equals("openai")?"developer":"system","content",instruction));
            var history=store.context(work); var bounded=new LinkedList<Map<String,String>>(); int remaining=32000;
            for(int i=history.size()-1;i>=0&&remaining>0;i--) {
                var message=history.get(i);String content=message.text();
                if(!agent.id().equals(message.agentId())) content=message.sender()+": "+content;
                if(content.length()>remaining) content=content.substring(content.length()-remaining);
                remaining-=content.length(); bounded.addFirst(Map.of("role",agent.id().equals(message.agentId())?"assistant":"user","content",content));
            }
            messages.addAll(bounded);
            String answer=model.complete(agent,config.key,messages);
            if(answer==null||answer.isBlank()||answer.length()>8000) throw new AgentModelClient.Failure("模型回复为空或过长");
            if(!config.key.isEmpty()) answer=answer.replace(config.key,"[已隐藏凭据]");
            store.complete(work,agent,answer);
        } catch (InterruptedException e) { Thread.currentThread().interrupt();store.fail(work,"服务中断了回复，请确认后手动重试"); }
        catch (AgentModelClient.Failure e) { store.fail(work,e.getMessage()); }
        catch (Exception e) { store.fail(work,"模型调用未完成，请检查 Agent 配置、模型权限和网络后手动重试"); }
    }
    @PreDestroy void close() { timer.shutdownNow();workers.shutdownNow(); }
}
