package com.leon.workflow.automation;

import org.springframework.scheduling.support.CronExpression;
import java.time.*;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Calendar semantics live here; the scheduler and editor preview use the same calculation. */
public record WorkflowScheduleRule(String cron, ZoneId zone, CronExpression expression) {
    public static WorkflowScheduleRule parse(Object cron, Object timeZone) {
        String value = required(cron, "请填写 Cron 表达式（最多 120 个字符）", 120);
        ZoneId zone;
        try { zone = ZoneId.of(required(timeZone, "请填写时区", 80)); }
        catch (DateTimeException e) { throw new IllegalArgumentException("时区无效，请使用 Asia/Shanghai 等时区名称"); }
        try { return new WorkflowScheduleRule(value, zone, CronExpression.parse(value)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Cron 无效，请使用六段格式：秒 分 时 日 月 周"); }
    }
    public Instant next(Instant after) {
        ZonedDateTime result = expression.next(after.atZone(zone));
        if (result == null) throw new IllegalArgumentException("Cron 表达式没有可用的后续执行时间");
        return result.toInstant();
    }
    public List<String> preview(Instant after) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < 5; i++) { after = next(after); result.add(after.toString()); }
        return result;
    }
}
