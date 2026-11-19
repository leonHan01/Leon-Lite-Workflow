package com.leon.workflow.messaging;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent of workflow and todo scheduling; slow models never block their timers. */
@Component
public class HelperOutreachScheduler {
    private final HelperOutreachService service;
    private final boolean enabled;
    private final ScheduledExecutorService loop=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("helper-scheduler").factory());
    private final ExecutorService workers=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("helper-outreach-",0).factory());
    private final AtomicInteger running=new AtomicInteger();
    public HelperOutreachScheduler(HelperOutreachService service,@Value("${leon.agents.helper-enabled:${leon.agents.dispatch-enabled:true}}") boolean enabled) {
        this.service=service;this.enabled=enabled;
    }
    @EventListener(ApplicationReadyEvent.class) public void ready() {
        if(enabled)loop.scheduleWithFixedDelay(this::poll,0,15,TimeUnit.SECONDS);
    }
    private void poll() {
        try {
            var now=Instant.now();service.plan(now);
            while(running.get()<2) {
                var delivery=service.claim(now);if(delivery==null)return;
                running.incrementAndGet();
                try { workers.execute(()->{try { service.deliver(delivery); } finally { running.decrementAndGet(); }}); }
                catch(RejectedExecutionException stopped) { running.decrementAndGet();return; }
            }
        } catch(Exception ignored) { /* Retry polling without logging private conversation data. */ }
    }
    @PreDestroy public void close() { loop.shutdownNow();workers.shutdownNow(); }
}
