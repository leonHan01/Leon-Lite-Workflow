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
    private final WorkflowFeishu feishu;
    private final WorkflowEmail email;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().daemon().name("workflow-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    public record NodeResult(Map<String, Object> output, String logs, String branch) {}
    private record PendingNode(WorkflowGraph.Node node, Map<String, Object> input) {}
    public WorkflowEngine(WorkflowStore store, ScriptProcessRunner scripts, WorkflowHttp http, WorkflowFeishu feishu, WorkflowEmail email) { this.store = store; this.scripts = scripts; this.http = http; this.feishu = feishu; this.email = email; }
    public Map<String, String> runtimes() { return Map.of("javascript", "GraalJS 25.0.2", "groovy", "Groovy " + groovy.lang.GroovySystem.getVersion()); }
    public Map<String, Object> capabilities() { return Map.of("runtimes", runtimes(), "sandbox", scripts.sandbox(), "maxNodes", WorkflowGraph.MAX_NODES, "maxConcurrentRuns", 2, "scheduledExecution", true); }
    public Map<String, Object> start(String workflowId, Map<String, Object> input) {
        envelope(input);
        var workflow = store.get(workflowId);
        int version = ((Number) workflow.get("publishedVersion")).intValue();
        WorkflowGraph graph = store.version(workflowId, version);
        Map<String, Object> isolated = read(write(input));
        WorkflowPrivacy initialPrivacy = new WorkflowPrivacy(); initialPrivacy.collect(isolated);
        String id = store.createRun(workflowId, version, graph, initialPrivacy.mask(isolated));
        submit(id, graph, isolated);
        return store.run(id);
    }
    void submit(String id, WorkflowGraph graph, Map<String, Object> input) {
        FutureTask<Void> task = new FutureTask<>(() -> { try { run(id, graph, input); } finally { tasks.remove(id); } return null; });
        tasks.put(id, task);
        try { workers.execute(task); }
        catch (RejectedExecutionException error) { tasks.remove(id); store.finish(id, "FAILED", null, "执行队列已满，请稍后重试"); throw busy(); }
    }
    private void run(String id, WorkflowGraph graph, Map<String, Object> input) {
        if (!store.start(id)) return;
        WorkflowPrivacy privacy = new WorkflowPrivacy(); privacy.collect(input);
        WorkflowGraph.Node start = graph.nodes().stream().filter(n -> "start".equals(n.type())).findFirst().orElseThrow();
        WorkflowGraph.Node end = graph.nodes().stream().filter(n -> "end".equals(n.type())).findFirst().orElseThrow();
        Map<String, WorkflowGraph.Node> nodes = new HashMap<>(); graph.nodes().forEach(n -> nodes.put(n.id(), n));
        Deque<PendingNode> pending = new ArrayDeque<>(); pending.add(new PendingNode(start, input));
        List<Map<String, Object>> outputs = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            while (!pending.isEmpty()) {
                checkRun(id, deadline);
                PendingNode current = pending.removeFirst();
                NodeResult result = runNode(id, current.node(), current.input(), privacy);
                var next = graph.edges().stream().filter(e -> e.source().equals(current.node().id()) && Objects.equals(Objects.requireNonNullElse(e.branch(), ""), result.branch())).toList();
                if (next.isEmpty()) throw new IllegalArgumentException("执行路径未连接到结束节点");
                for (var edge : next) {
                    Map<String, Object> data = read(write(result.output()));
                    if (edge.target().equals(end.id())) outputs.add(data);
                    else pending.addLast(new PendingNode(nodes.get(edge.target()), data));
                }
            }
            checkRun(id, deadline);
            NodeResult result = runNode(id, end, terminalInput(outputs), privacy);
            store.finish(id, "SUCCEEDED", privacy.mask(result.output()), null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); store.finish(id, "CANCELLED", null, "执行已取消；已发送的请求或邮件可能已在对端生效");
        } catch (Exception error) { store.finish(id, "FAILED", null, error(error, privacy)); }
    }
    private void checkRun(String id, long deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || store.cancelled(id)) throw new InterruptedException();
        if (System.nanoTime() > deadline) throw new IllegalArgumentException("工作流运行超过 5 分钟，请拆分为较短流程");
    }
    private Map<String, Object> terminalInput(List<Map<String, Object>> outputs) {
        if (outputs.isEmpty()) throw new IllegalArgumentException("执行路径未连接到结束节点");
        if (outputs.size() == 1) return outputs.getFirst();
        List<Object> items = new ArrayList<>();
        for (var output : outputs) {
            items.addAll((List<?>) output.get("items"));
            if (items.size() > 1000) throw new IllegalArgumentException("分支汇总结果超过 1000 条，请减少输出数据");
        }
        return envelope(Map.of("items", items));
    }
    private NodeResult runNode(String id, WorkflowGraph.Node node, Map<String, Object> input, WorkflowPrivacy privacy) throws Exception {
        store.startNode(id, node, privacy.mask(input));
        try {
            NodeResult result = execute(node, input, id, privacy);
            privacy.collect(result.output());
            if (Thread.currentThread().isInterrupted() || store.cancelled(id)) throw new InterruptedException();
            store.finishNode(id, node.id(), "SUCCEEDED", privacy.mask(result.output()), privacy.message(result.logs()), null);
            return result;
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            String logs = error instanceof ScriptProcessRunner.ScriptFailure failure ? privacy.message(failure.logs()) : null;
            store.finishNode(id, node.id(), "FAILED", null, logs, error(error, privacy)); throw error;
        }
    }
    private NodeResult execute(WorkflowGraph.Node node, Map<String, Object> input, String runId, WorkflowPrivacy privacy) throws Exception {
        Map<String, Object> config = node.settings();
        Map<String, Object> ctx = Map.of("runId", runId, "nodeId", node.id(), "params", config.getOrDefault("params", Map.of()));
        return switch (node.type()) {
            case "start", "end" -> new NodeResult(input, "", "");
            case "script" -> {
                var result = scripts.execute(text(config.get("language")), text(config.get("source")), input, ctx, integer(config.get("timeoutMs"), 10_000, 100, 30_000));
                yield new NodeResult(result.output(), result.logs(), "");
            }
            case "http" -> new NodeResult(envelope(http.execute(config, input, ctx, privacy)), "", "");
            case "feishu" -> new NodeResult(feishu.execute(config, input, ctx, privacy), "飞书消息已发送", "");
            case "email" -> new NodeResult(email.execute(config, input, ctx, privacy), "邮件已提交至 SMTP 服务器", "");
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
        store.finish(id, "CANCELLED", null, "执行已取消；已发送的请求或邮件可能已在对端生效");
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
