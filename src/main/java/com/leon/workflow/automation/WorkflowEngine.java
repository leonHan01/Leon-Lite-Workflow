package com.leon.workflow.automation;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static com.leon.workflow.automation.WorkflowJson.*;

@Service
public class WorkflowEngine {
    private final WorkflowStore store;
    private final ScriptProcessRunner scripts;
    private final WorkflowHttp http;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().daemon().name("workflow-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    public record NodeResult(Map<String, Object> output, String logs, String branch) {}
    public WorkflowEngine(WorkflowStore store, ScriptProcessRunner scripts, WorkflowHttp http) { this.store = store; this.scripts = scripts; this.http = http; }
    public Map<String, String> runtimes() { return Map.of("javascript", "GraalJS 25.0.2", "groovy", "Groovy " + groovy.lang.GroovySystem.getVersion()); }
    public Map<String, Object> capabilities() { return Map.of("runtimes", runtimes(), "sandbox", scripts.sandbox(), "maxNodes", 64, "maxConcurrentRuns", 2); }
    public Map<String, Object> start(String workflowId, Map<String, Object> input) {
        envelope(input);
        var workflow = store.get(workflowId);
        int version = ((Number) workflow.get("publishedVersion")).intValue();
        WorkflowGraph graph = store.version(workflowId, version);
        Map<String, Object> isolated = read(write(input));
        WorkflowPrivacy initialPrivacy = new WorkflowPrivacy(); initialPrivacy.collect(isolated);
        String id = store.createRun(workflowId, version, graph, initialPrivacy.mask(isolated));
        FutureTask<Void> task = new FutureTask<>(() -> { try { run(id, graph, isolated); } finally { tasks.remove(id); } return null; });
        tasks.put(id, task);
        try { workers.execute(task); }
        catch (RejectedExecutionException error) { tasks.remove(id); store.finish(id, "FAILED", null, "执行队列已满，请稍后重试"); throw busy(); }
        return store.run(id);
    }
    private void run(String id, WorkflowGraph graph, Map<String, Object> input) {
        if (!store.start(id)) return;
        WorkflowPrivacy privacy = new WorkflowPrivacy(); privacy.collect(input);
        WorkflowGraph.Node current = graph.nodes().stream().filter(n -> "manual".equals(n.type())).findFirst().orElseThrow();
        Map<String, WorkflowGraph.Node> nodes = new HashMap<>(); graph.nodes().forEach(n -> nodes.put(n.id(), n));
        Map<String, Object> data = input;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            while (current != null) {
                if (Thread.currentThread().isInterrupted() || store.cancelled(id)) throw new InterruptedException();
                if (System.nanoTime() > deadline) throw new IllegalArgumentException("工作流运行超过 5 分钟，请拆分为较短流程");
                store.startNode(id, current, privacy.mask(data));
                try {
                    NodeResult result = execute(current, data, id, privacy);
                    privacy.collect(result.output());
                    if (Thread.currentThread().isInterrupted() || store.cancelled(id)) throw new InterruptedException();
                    store.finishNode(id, current.id(), "SUCCEEDED", privacy.mask(result.output()), privacy.message(result.logs()), null);
                    data = result.output();
                    String nodeId = current.id(), branch = result.branch();
                    var next = graph.edges().stream().filter(e -> e.source().equals(nodeId) && Objects.equals(Objects.requireNonNullElse(e.branch(), ""), branch)).findFirst();
                    current = next.map(e -> nodes.get(e.target())).orElse(null);
                } catch (InterruptedException error) { throw error; }
                catch (Exception error) {
                    String logs = error instanceof ScriptProcessRunner.ScriptFailure failure ? privacy.message(failure.logs()) : null;
                    store.finishNode(id, current.id(), "FAILED", null, logs, error(error, privacy)); throw error;
                }
            }
            store.finish(id, "SUCCEEDED", privacy.mask(data), null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); store.finish(id, "CANCELLED", null, "执行已取消；已发送的 HTTP 请求可能已在对端生效");
        } catch (Exception error) { store.finish(id, "FAILED", null, error(error, privacy)); }
    }
    private NodeResult execute(WorkflowGraph.Node node, Map<String, Object> input, String runId, WorkflowPrivacy privacy) throws Exception {
        Map<String, Object> config = node.settings();
        Map<String, Object> ctx = Map.of("runId", runId, "nodeId", node.id(), "params", config.getOrDefault("params", Map.of()));
        return switch (node.type()) {
            case "manual" -> new NodeResult(input, "", "");
            case "script" -> {
                var result = scripts.execute(text(config.get("language")), text(config.get("source")), input, ctx, integer(config.get("timeoutMs"), 10_000, 100, 30_000));
                yield new NodeResult(result.output(), result.logs(), "");
            }
            case "http" -> new NodeResult(envelope(http.execute(config, input, ctx, privacy)), "", "");
            case "if" -> new NodeResult(input, "", String.valueOf(WorkflowExpressions.condition(config, input, ctx)));
            default -> throw new IllegalArgumentException("不支持的节点类型");
        };
    }
    public Map<String, Object> test(WorkflowGraph.Node node, Map<String, Object> input) {
        if (node == null || node.id() == null || !node.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("请选择有效的调试节点");
        WorkflowGraph.validateNode(node); envelope(input);
        WorkflowPrivacy privacy = new WorkflowPrivacy(); privacy.collect(input); long started = System.currentTimeMillis();
        Future<NodeResult> task;
        try { task = workers.submit(() -> execute(node, input, "preview-" + UUID.randomUUID(), privacy)); }
        catch (RejectedExecutionException e) { throw busy(); }
        try {
            NodeResult result = task.get(32, TimeUnit.SECONDS); privacy.collect(result.output());
            return Map.of("output", privacy.mask(result.output()), "logs", privacy.message(result.logs()), "branch", result.branch(), "durationMs", System.currentTimeMillis() - started);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("调试已取消"); }
        catch (TimeoutException e) { throw new IllegalArgumentException("调试等待超时，请稍后重试"); }
        catch (ExecutionException e) { throw new IllegalArgumentException(error(e.getCause(), privacy)); }
        finally { task.cancel(true); }
    }
    public Map<String, Object> cancel(String id) {
        store.run(id);
        store.finish(id, "CANCELLED", null, "执行已取消；已发送的 HTTP 请求可能已在对端生效");
        FutureTask<Void> task = tasks.remove(id); if (task != null) { task.cancel(true); workers.remove(task); }
        return store.run(id);
    }
    private String error(Throwable error, WorkflowPrivacy privacy) {
        String message = privacy.message(error.getMessage());
        if (message == null || message.isBlank()) message = "节点执行失败，请检查配置及输入数据";
        return message.substring(0, Math.min(950, message.length()));
    }
    private ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "执行队列已满，请稍后重试"); }
    @PreDestroy public void close() {
        tasks.keySet().forEach(id -> store.finish(id, "CANCELLED", null, "服务正在关闭，执行已中止"));
        workers.shutdownNow();
        try { workers.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
