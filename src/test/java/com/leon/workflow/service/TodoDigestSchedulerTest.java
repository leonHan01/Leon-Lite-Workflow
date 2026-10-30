package com.leon.workflow.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TodoDigestSchedulerTest {
    @Test
    void scheduledSlotsDelegateToService() {
        TodoDigestService service = mock(TodoDigestService.class);
        TodoDigestScheduler scheduler = new TodoDigestScheduler(service);

        scheduler.statsAtTen();
        scheduler.statsAtFourteen();
        scheduler.statsAtTwenty();
        scheduler.summaryAtMidnight();

        verify(service).sendStats("10:00");
        verify(service).sendStats("14:00");
        verify(service).sendStats("20:00");
        verify(service).sendMidnightSummary();
    }
}
