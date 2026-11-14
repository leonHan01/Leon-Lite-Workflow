package com.leon.workflow.automation;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Owns persistence and atomic state transitions; network/script work never holds a transaction. */
@Repository
public class WorkflowStore {
    private final JdbcTemplate jdbc;
    public WorkflowStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS automation_workflow (
              id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, draft CLOB NOT NULL,
              revision BIGINT NOT NULL DEFAULT 1, published_version INT NOT NULL DEFAULT 0,
              deleted BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS automation_version (
              workflow_id VARCHAR(36) NOT NULL, version INT NOT NULL, definition CLOB NOT NULL,
              runtimes VARCHAR(250) NOT NULL, created_at BIGINT NOT NULL,
              PRIMARY KEY(workflow_id, version), FOREIGN KEY(workflow_id) REFERENCES automation_workflow(id))
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS automation_run (
              id VARCHAR(36) PRIMARY KEY, workflow_id VARCHAR(36) NOT NULL, version INT NOT NULL,
              status VARCHAR(20) NOT NULL, input_json CLOB NOT NULL, output_json CLOB,
              error VARCHAR(1000), created_at BIGINT NOT NULL, started_at BIGINT, finished_at BIGINT,
              FOREIGN KEY(workflow_id, version) REFERENCES automation_version(workflow_id, version))
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_automation_run_workflow ON automation_run(workflow_id, created_at)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS automation_node_run (
              run_id VARCHAR(36) NOT NULL, node_id VARCHAR(64) NOT NULL, node_name VARCHAR(80) NOT NULL,
              node_type VARCHAR(20) NOT NULL, node_order INT NOT NULL, status VARCHAR(20) NOT NULL,
              input_json CLOB, output_json CLOB, logs CLOB, error VARCHAR(1000), started_at BIGINT, finished_at BIGINT,
              PRIMARY KEY(run_id, node_id), FOREIGN KEY(run_id) REFERENCES automation_run(id))
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS automation_credential (
              id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, kind VARCHAR(20) NOT NULL,
              encrypted CLOB NOT NULL, created_at BIGINT NOT NULL)
            """);
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE automation_run SET status='FAILED', error=?, finished_at=? WHERE status IN ('QUEUED','RUNNING')",
                "服务重启中断了执行；未自动重放，请确认外部接口结果后重新运行", now);
        jdbc.update("UPDATE automation_node_run SET status='FAILED', error=?, finished_at=? WHERE status='RUNNING'", "执行因服务重启中断", now);
        jdbc.update("UPDATE automation_node_run SET status='SKIPPED' WHERE status='PENDING'");
    }
    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT id,name,revision,published_version,created_at,updated_at FROM automation_workflow WHERE deleted=FALSE ORDER BY updated_at DESC",
                (rs, i) -> summary(rs));
    }
    private Map<String, Object> summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getString("id")); result.put("name", rs.getString("name"));
        result.put("revision", rs.getLong("revision")); result.put("publishedVersion", rs.getInt("published_version"));
        result.put("createdAt", time(rs.getObject("created_at"))); result.put("updatedAt", time(rs.getObject("updated_at")));
        return result;
    }
    public Map<String, Object> get(String id) {
        var rows = jdbc.query("SELECT * FROM automation_workflow WHERE id=? AND deleted=FALSE", (rs, i) -> {
            Map<String, Object> result = summary(rs); result.put("definition", read(rs.getString("draft"))); return result;
        }, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在");
        return rows.getFirst();
    }
    public Map<String, Object> create(String name) {
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO automation_workflow(id,name,draft,created_at,updated_at) VALUES(?,?,?,?,?)", id, name, write(WorkflowGraph.initial()), now, now);
        return get(id);
    }
    public Map<String, Object> save(String id, String name, Object graph, long revision) {
        checkSize(graph);
        var definition = JSON.convertValue(graph, WorkflowGraph.class);
        if (definition.nodes() == null || definition.nodes().size() > 64 || definition.edges() == null || definition.edges().size() > 128)
            throw new IllegalArgumentException("流程最多包含 64 个节点和 128 条连线");
        get(id);
        int changed = jdbc.update("UPDATE automation_workflow SET name=?,draft=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND deleted=FALSE",
                name, write(definition), System.currentTimeMillis(), id, revision);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿已在其他页面修改，请重新加载后编辑");
        return get(id);
    }
    @Transactional public Map<String, Object> publish(String id, long revision, String runtimes) {
        jdbc.queryForList("SELECT id FROM automation_workflow WHERE id=? FOR UPDATE", id);
        var workflow = get(id);
        if (((Number) workflow.get("revision")).longValue() != revision)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿版本已变化，请重新保存后发布");
        WorkflowGraph graph = WorkflowGraph.parse(workflow.get("definition"));
        int version = ((Number) workflow.get("publishedVersion")).intValue() + 1;
        jdbc.update("INSERT INTO automation_version(workflow_id,version,definition,runtimes,created_at) VALUES(?,?,?,?,?)",
                id, version, write(graph), runtimes, System.currentTimeMillis());
        jdbc.update("UPDATE automation_workflow SET published_version=?,updated_at=? WHERE id=?", version, System.currentTimeMillis(), id);
        return get(id);
    }
    public List<Map<String, Object>> versions(String id) {
        get(id);
        return jdbc.query("SELECT version,runtimes,created_at FROM automation_version WHERE workflow_id=? ORDER BY version DESC",
                (rs, i) -> Map.of("version", rs.getInt(1), "runtimes", read(rs.getString(2)), "createdAt", time(rs.getObject(3))), id);
    }
    public WorkflowGraph version(String id, int version) {
        var rows = jdbc.queryForList("SELECT definition FROM automation_version WHERE workflow_id=? AND version=?", String.class, id, version);
        if (rows.isEmpty()) throw new IllegalArgumentException("请先发布工作流");
        return WorkflowGraph.parse(read(rows.getFirst()));
    }
    @Transactional public void delete(String id) {
        jdbc.queryForList("SELECT id FROM automation_workflow WHERE id=? FOR UPDATE", id);
        get(id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM automation_run WHERE workflow_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先停止正在执行的工作流");
        jdbc.update("UPDATE automation_workflow SET deleted=TRUE WHERE id=?", id);
    }
    @Transactional public String createRun(String workflowId, int version, WorkflowGraph graph, Object input) {
        jdbc.queryForList("SELECT id FROM automation_workflow WHERE id=? FOR UPDATE", workflowId); get(workflowId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO automation_run(id,workflow_id,version,status,input_json,created_at) VALUES(?,?,?,'QUEUED',?,?)",
                id, workflowId, version, write(redact(input)), System.currentTimeMillis());
        int order = 0;
        for (var node : graph.nodes()) jdbc.update("INSERT INTO automation_node_run(run_id,node_id,node_name,node_type,node_order,status) VALUES(?,?,?,?,?,'PENDING')",
                id, node.id(), node.name(), node.type(), order++);
        return id;
    }
    public List<Map<String, Object>> runs(String workflowId) {
        get(workflowId);
        return jdbc.query("SELECT * FROM automation_run WHERE workflow_id=? ORDER BY created_at DESC LIMIT 50", (rs, i) -> runRow(rs, false), workflowId);
    }
    public Map<String, Object> run(String id) {
        var rows = jdbc.query("SELECT * FROM automation_run WHERE id=?", (rs, i) -> runRow(rs, true), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在");
        var result = rows.getFirst();
        result.put("nodes", jdbc.query("SELECT * FROM automation_node_run WHERE run_id=? ORDER BY node_order", (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", rs.getString("node_id")); row.put("name", rs.getString("node_name")); row.put("type", rs.getString("node_type"));
            row.put("status", rs.getString("status")); row.put("input", nullableJson(rs.getString("input_json"))); row.put("output", nullableJson(rs.getString("output_json")));
            row.put("logs", rs.getString("logs")); row.put("error", rs.getString("error"));
            row.put("startedAt", time(rs.getObject("started_at"))); row.put("finishedAt", time(rs.getObject("finished_at"))); return row;
        }, id));
        return result;
    }
    private Map<String, Object> runRow(java.sql.ResultSet rs, boolean data) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id")); row.put("workflowId", rs.getString("workflow_id")); row.put("version", rs.getInt("version"));
        row.put("status", rs.getString("status")); row.put("error", rs.getString("error"));
        row.put("createdAt", time(rs.getObject("created_at"))); row.put("startedAt", time(rs.getObject("started_at"))); row.put("finishedAt", time(rs.getObject("finished_at")));
        if (data) {
            row.put("input", nullableJson(rs.getString("input_json"))); row.put("output", nullableJson(rs.getString("output_json")));
            row.put("definition", read(jdbc.queryForObject("SELECT definition FROM automation_version WHERE workflow_id=? AND version=?", String.class, rs.getString("workflow_id"), rs.getInt("version"))));
        }
        return row;
    }
    public boolean start(String id) { return jdbc.update("UPDATE automation_run SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'", System.currentTimeMillis(), id) == 1; }
    public boolean cancelled(String id) { return "CANCELLED".equals(jdbc.queryForObject("SELECT status FROM automation_run WHERE id=?", String.class, id)); }
    public void startNode(String run, WorkflowGraph.Node node, Object input) {
        jdbc.update("UPDATE automation_node_run SET status='RUNNING',started_at=?,input_json=? WHERE run_id=? AND node_id=? AND status='PENDING'",
                System.currentTimeMillis(), write(redact(input)), run, node.id());
    }
    public void finishNode(String run, String node, String status, Object output, String logs, String error) {
        jdbc.update("UPDATE automation_node_run SET status=?,output_json=?,logs=?,error=?,finished_at=? WHERE run_id=? AND node_id=? AND status='RUNNING'",
                status, output == null ? null : write(redact(output)), logs, error, System.currentTimeMillis(), run, node);
    }
    @Transactional public void finish(String id, String status, Object output, String error) {
        jdbc.update("UPDATE automation_run SET status=?,output_json=?,error=?,finished_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",
                status, output == null ? null : write(redact(output)), error, System.currentTimeMillis(), id);
        jdbc.update("UPDATE automation_node_run SET status='SKIPPED' WHERE run_id=? AND status='PENDING'", id);
        jdbc.update("UPDATE automation_node_run SET status=?,error=?,finished_at=? WHERE run_id=? AND status='RUNNING'", status, error, System.currentTimeMillis(), id);
    }
    private Object nullableJson(String value) { return value == null ? null : JSON.readValue(value, Object.class); }
    private static String time(Object value) { return value == null ? null : Instant.ofEpochMilli(((Number) value).longValue()).toString(); }
}
