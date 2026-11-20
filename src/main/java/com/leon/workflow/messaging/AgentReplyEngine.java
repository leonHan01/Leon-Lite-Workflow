package com.leon.workflow.messaging;

import jakarta.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@org.springframework.context.annotation.DependsOn("chatUserMigration")
public class AgentReplyEngine {
    private final DeviceMessageStore store; private final AgentRegistry agents; private final AgentModelClient model;
    private final AgentContextManager contexts;
    private final ChatImageService images;
    private final boolean enabled;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"agent-dispatch");t.setDaemon(true);return t;});
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{var t=new Thread(r,"agent-reply");t.setDaemon(true);return t;});
    private final AtomicInteger running=new AtomicInteger();
    public AgentReplyEngine(DeviceMessageStore store,AgentRegistry agents,AgentModelClient model,AgentContextManager contexts,ChatImageService images,@Value("${leon.agents.dispatch-enabled:true}") boolean enabled) { this.store=store;this.agents=agents;this.model=model;this.contexts=contexts;this.images=images;this.enabled=enabled; }
    @PostConstruct void start() {
        store.recoverInterrupted();
        contexts.recoverCompressions();
        if(enabled) timer.scheduleWithFixedDelay(this::dispatch,500,500,TimeUnit.MILLISECONDS);
    }
    private void dispatch() {
        try {
            while(running.get()<4) {
                var compression=contexts.claimCompression();
                if(compression!=null) {
                    running.incrementAndGet();
                    workers.execute(()->{try { contexts.executeCompression(compression); } finally { running.decrementAndGet(); }});
                    continue;
                }
                var work=store.claim(); if(work==null) return; running.incrementAndGet();
                workers.execute(()->{try { execute(work); } finally { running.decrementAndGet(); }});
            }
        } catch (Exception ignored) { /* Retry dispatch, never log conversation or credential data. */ }
    }
    // Deterministic entry for integration tests; normal operation uses the bounded worker pool.
    public boolean runNext() {
        var compression=contexts.claimCompression();if(compression!=null) { contexts.executeCompression(compression);return true; }
        var work=store.claim();if(work==null)return false;execute(work);return true;
    }
    private void execute(DeviceMessageStore.Work work) {
        AgentUsageContext.run(work.userId(),()->reply(work));
    }

    private void reply(DeviceMessageStore.Work work) {
        try {
            var config=agents.runtime(work.agentId()); var agent=config.agent;
            if(!agent.enabled()) { store.fail(work,"Agent 已停用，请启用后手动重试");return; }
            var messages=contexts.prepare(work,agent,config.key);
            store.phase(work,"REPLYING");
            var reply=agent.imageGenerationEnabled()?model.reply(agent,config.key,messages):new AgentModelClient.Reply(model.complete(agent,config.key,messages),List.of());
            if(reply==null||reply.images()==null||reply.images().size()>ChatImageService.MAX_PER_MESSAGE)throw new AgentModelClient.Failure("模型返回无效回复");
            String answer=reply.text();
            if(answer==null||answer.isBlank()||answer.length()>8000) throw new AgentModelClient.Failure("模型回复为空或过长");
            if(!config.key.isEmpty()) answer=answer.replace(config.key,"[已隐藏凭据]");
            if(reply.images().isEmpty())store.complete(work,agent,answer);
            else images.completeGenerated(work,agent,answer,reply.images());
        } catch (InterruptedException e) { Thread.currentThread().interrupt();store.fail(work,"服务中断了回复，请确认后手动重试"); }
        catch (AgentModelClient.Failure e) { store.fail(work,e.getMessage()); }
        catch (Exception e) { store.fail(work,"模型调用未完成，请检查 Agent 配置、模型权限和网络后手动重试"); }
    }
    @PreDestroy void close() { timer.shutdownNow();workers.shutdownNow(); }
}
