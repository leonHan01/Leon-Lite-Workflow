package com.leon.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TodoDigestScheduler {
    private static final Logger log = LoggerFactory.getLogger(TodoDigestScheduler.class);
    private static final String ZONE = "Asia/Shanghai";

    private final TodoDigestService todoDigestService;

    public TodoDigestScheduler(TodoDigestService todoDigestService) {
        this.todoDigestService = todoDigestService;
    }

    @Scheduled(cron = "0 0 10 * * *", zone = ZONE)
    public void statsAtTen() {
        runStats("10:00");
    }

    @Scheduled(cron = "0 0 14 * * *", zone = ZONE)
    public void statsAtFourteen() {
        runStats("14:00");
    }

    @Scheduled(cron = "0 0 20 * * *", zone = ZONE)
    public void statsAtTwenty() {
        runStats("20:00");
    }

    @Scheduled(cron = "0 0 0 * * *", zone = ZONE)
    public void summaryAtMidnight() {
        log.info("开始发送 24:00 待办 LLM 日结");
        todoDigestService.sendMidnightSummary();
    }

    private void runStats(String slot) {
        log.info("开始发送 {} 待办统计", slot);
        todoDigestService.sendStats(slot);
    }
}
