package com.leon.workflow.automation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.concurrent.*;

/** Dedicated polling loop: does not enable unrelated @Scheduled plugin jobs. */
@Component
public class WorkflowScheduler {
    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);
    private final WorkflowSchedules schedules;
    private final WorkflowEngine engine;
    private final boolean enabled;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("workflow-scheduler").factory());
    private boolean initialized, failureLogged;
    public WorkflowScheduler(WorkflowSchedules schedules, WorkflowEngine engine, @Value("${leon.workflows.scheduling-enabled:true}") boolean enabled) {
        this.schedules = schedules; this.engine = engine; this.enabled = enabled;
    }
    @EventListener(ApplicationReadyEvent.class) public void ready() {
        if (enabled) loop.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }
    private void poll() {
        try {
            Instant now = Instant.now();
            if (!initialized) {
                for (var missed : schedules.missed(now)) schedules.skipMissed(missed, now);
                initialized = true;
            }
            tick(now); failureLogged = false;
        } catch (Exception error) {
            if (!failureLogged) log.warn("工作流定时调度暂不可用 ({})", error.getClass().getSimpleName());
            failureLogged = true;
        }
    }
    void tick(Instant now) {
        for (var due : schedules.due(now)) {
            WorkflowSchedules.Launch launch;
            try { launch = schedules.claim(due, now); }
            catch (IllegalArgumentException error) { schedules.invalid(due); continue; }
            if (launch == null) continue;
            try { engine.submit(launch.runId(), launch.graph(), launch.input()); }
            catch (Exception error) { schedules.enqueueFailed(due.workflowId(), launch.runId()); }
        }
    }
    @PreDestroy public void close() {
        loop.shutdownNow();
        try { loop.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
