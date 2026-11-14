package com.leon.workflow.automation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

@Component
public class WorkflowSchedules {
    public record Due(String workflowId, long scheduledAt) {}
    public record Launch(String runId, WorkflowGraph graph, Map<String, Object> input) {}
    private final JdbcTemplate jdbc;
    private final WorkflowStore store;
    public WorkflowSchedules(JdbcTemplate jdbc, WorkflowStore store) { this.jdbc = jdbc; this.store = store; }
    public Map<String, Object> get(String workflowId) {
        store.get(workflowId);
        var rows = jdbc.query("SELECT * FROM automation_schedule WHERE workflow_id=?", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("enabled", rs.getBoolean("enabled")); row.put("cron", rs.getString("cron")); row.put("timeZone", rs.getString("time_zone"));
            row.put("input", read(rs.getString("input_json"))); row.put("revision", rs.getLong("revision"));
            row.put("nextRunAt", time(rs.getObject("next_run_at"))); row.put("lastScheduledAt", time(rs.getObject("last_scheduled_at")));
            row.put("lastRunId", rs.getString("last_run_id")); row.put("lastOutcome", rs.getString("last_outcome")); row.put("lastError", rs.getString("last_error")); return row;
        }, workflowId);
        if (!rows.isEmpty()) return rows.getFirst();
        Map<String, Object> value = new LinkedHashMap<>(Map.of("enabled", false, "cron", "0 0 9 * * *", "timeZone", "Asia/Shanghai", "input", Map.of("items", List.of(Map.of("json", Map.of()))), "revision", 0));
        for (String field : List.of("nextRunAt", "lastScheduledAt", "lastRunId", "lastOutcome", "lastError")) value.put(field, null);
        return value;
    }
    @Transactional public Map<String, Object> save(String workflowId, Map<String, Object> body, Instant now) {
        lock(workflowId);
        var workflow = store.get(workflowId);
        if (!(body.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("请选择定时任务是否启用");
        if (enabled && ((Number) workflow.get("publishedVersion")).intValue() == 0) throw new IllegalArgumentException("请先发布工作流再启用定时执行");
        var rule = WorkflowScheduleRule.parse(body.get("cron"), body.get("timeZone"));
        Instant next = rule.next(now);
        var input = envelope(body.get("input"));
        int revision = integer(body.get("revision"), -1, 0, Integer.MAX_VALUE - 1);
        var current = get(workflowId);
        if (((Number) current.get("revision")).longValue() != revision) throw new ResponseStatusException(HttpStatus.CONFLICT, "定时配置已在其他页面修改，请重新打开后编辑");
        if (revision == 0) jdbc.update("INSERT INTO automation_schedule(workflow_id,enabled,cron,time_zone,input_json,revision,next_run_at,updated_at) VALUES(?,?,?,?,?,1,?,?)",
                workflowId, enabled, rule.cron(), rule.zone().getId(), write(input), enabled ? next.toEpochMilli() : null, now.toEpochMilli());
        else jdbc.update("UPDATE automation_schedule SET enabled=?,cron=?,time_zone=?,input_json=?,revision=revision+1,next_run_at=?,last_error=NULL,updated_at=? WHERE workflow_id=?",
                enabled, rule.cron(), rule.zone().getId(), write(input), enabled ? next.toEpochMilli() : null, now.toEpochMilli(), workflowId);
        return get(workflowId);
    }
    public List<Due> due(Instant now) {
        return jdbc.query("SELECT s.workflow_id,s.next_run_at FROM automation_schedule s JOIN automation_workflow w ON w.id=s.workflow_id WHERE s.enabled=TRUE AND w.deleted=FALSE AND s.next_run_at<=? ORDER BY s.next_run_at,s.workflow_id LIMIT 20",
                (rs, i) -> new Due(rs.getString(1), rs.getLong(2)), now.toEpochMilli());
    }
    /** Advancing the cursor and recording the QUEUED instance commit together, before any worker executes. */
    @Transactional public Launch claim(Due due, Instant now) {
        lock(due.workflowId());
        var rows = jdbc.queryForList("SELECT * FROM automation_schedule WHERE workflow_id=? FOR UPDATE", due.workflowId());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        Object next = row.get("NEXT_RUN_AT");
        if (!Boolean.TRUE.equals(row.get("ENABLED")) || !(next instanceof Number n) || n.longValue() != due.scheduledAt() || due.scheduledAt() > now.toEpochMilli()) return null;
        var workflow = store.get(due.workflowId());
        var rule = WorkflowScheduleRule.parse(row.get("CRON"), row.get("TIME_ZONE"));
        long following = rule.next(now).toEpochMilli();
        boolean active = jdbc.queryForObject("SELECT COUNT(*) FROM automation_run WHERE workflow_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, due.workflowId()) > 0;
        if (active) {
            jdbc.update("UPDATE automation_schedule SET next_run_at=?,last_scheduled_at=?,last_outcome='SKIPPED',last_error=? WHERE workflow_id=?",
                    following, due.scheduledAt(), "已有排队或运行实例，本次定时执行点已跳过", due.workflowId()); return null;
        }
        int version = ((Number) workflow.get("publishedVersion")).intValue();
        WorkflowGraph graph = store.version(due.workflowId(), version);
        Map<String, Object> input = envelope(read(jdbc.queryForObject("SELECT input_json FROM automation_schedule WHERE workflow_id=?", String.class, due.workflowId())));
        WorkflowPrivacy privacy = new WorkflowPrivacy(); privacy.collect(input);
        String run = store.createRun(due.workflowId(), version, graph, privacy.mask(input), "SCHEDULED", due.scheduledAt());
        jdbc.update("UPDATE automation_schedule SET next_run_at=?,last_scheduled_at=?,last_run_id=?,last_outcome='TRIGGERED',last_error=NULL WHERE workflow_id=?",
                following, due.scheduledAt(), run, due.workflowId());
        return new Launch(run, graph, input);
    }
    @Transactional public void skipMissed(Due due, Instant now) {
        lock(due.workflowId());
        var current = get(due.workflowId());
        if (!Boolean.TRUE.equals(current.get("enabled"))) return;
        Object next = current.get("nextRunAt");
        if (next == null || Instant.parse(next.toString()).toEpochMilli() != due.scheduledAt()) return;
        long following = WorkflowScheduleRule.parse(current.get("cron"), current.get("timeZone")).next(now).toEpochMilli();
        jdbc.update("UPDATE automation_schedule SET next_run_at=?,last_outcome='MISSED',last_error=? WHERE workflow_id=?", following, "服务离线期间的执行点已跳过", due.workflowId());
    }
    public List<Due> missed(Instant now) {
        return jdbc.query("SELECT s.workflow_id,s.next_run_at FROM automation_schedule s JOIN automation_workflow w ON w.id=s.workflow_id WHERE s.enabled=TRUE AND w.deleted=FALSE AND s.next_run_at<? ORDER BY s.next_run_at",
                (rs, i) -> new Due(rs.getString(1), rs.getLong(2)), now.toEpochMilli());
    }
    public void enqueueFailed(String workflowId, String runId) {
        jdbc.update("UPDATE automation_schedule SET last_outcome='FAILED',last_error=? WHERE workflow_id=? AND last_run_id=?", "定时实例未能入队，请查看运行实例中的错误", workflowId, runId);
    }
    public void invalid(Due due) {
        jdbc.update("UPDATE automation_schedule SET enabled=FALSE,next_run_at=NULL,revision=revision+1,last_outcome='FAILED',last_error=? WHERE workflow_id=? AND next_run_at=?", "定时配置无效，已停用，请重新保存配置", due.workflowId(), due.scheduledAt());
    }
    private void lock(String id) { jdbc.queryForList("SELECT id FROM automation_workflow WHERE id=? FOR UPDATE", id); }
    private static String time(Object value) { return value == null ? null : Instant.ofEpochMilli(((Number) value).longValue()).toString(); }
}
