package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowScheduleRuleTest {
    @Test void previewRespectsTimeZonesAndWeekdays() {
        Instant saturday = Instant.parse("2026-11-14T02:00:00Z");
        var shanghai = WorkflowScheduleRule.parse("0 0 9 * * MON-FRI", "Asia/Shanghai");
        assertEquals(Instant.parse("2026-11-16T01:00:00Z"), shanghai.next(saturday));
        assertEquals(5, shanghai.preview(saturday).size());
        assertEquals(Instant.parse("2026-11-14T09:00:00Z"), WorkflowScheduleRule.parse("0 0 9 * * *", "UTC").next(saturday));
    }
    @Test void invalidSchedulesFailClearlyAndNextIsExclusive() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowScheduleRule.parse("0 0 9 * *", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowScheduleRule.parse("0 * * * * *", "Invalid/Zone"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowScheduleRule.parse("0 0 0 31 2 *", "UTC").next(Instant.parse("2026-01-01T00:00:00Z")));
        var minute = WorkflowScheduleRule.parse("0 * * * * *", "UTC");
        assertEquals(Instant.parse("2026-11-14T02:01:00Z"), minute.next(Instant.parse("2026-11-14T02:00:00Z")));
    }
}
