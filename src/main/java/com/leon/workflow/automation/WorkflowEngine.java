package com.leon.workflow.automation;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;

@Service
public class WorkflowEngine {
    static final int MAX_PARALLEL_NODES = 4;
    private final WorkflowStore store;
    private final ScriptProcessRunner scripts;
    private final WorkflowHttp http;
    private final WorkflowFeishu feishu;
    private final WorkflowQq qq;
    private final WorkflowEmail email;
    private final WorkflowGithubTrending trending;
    private final WorkflowRss rss;
    private final WorkflowAiSummary aiSummary;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().daemon().name("workflow-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    public record NodeResult(Map<String, Object> output, String logs, String branch) {}
    public static final class DebugFailure extends IllegalArgumentException {
        private final String logs;
        public DebugFailure(String message, String logs) { super(message); this.logs = logs; }
        public String logs() { return logs; }
    }
    private record CompletedNode(WorkflowGraph.Node node, NodeResult result, Exception failure) {}
    public WorkflowEngine(WorkflowStore store, ScriptProcessRunner scripts, WorkflowHttp http, WorkflowFeishu feishu, WorkflowEmail email, WorkflowGithubTrending trending, WorkflowRss rss, WorkflowAiSummary aiSummary, WorkflowQq qq) { this.store = store; this.scripts = scripts; this.http = http; this.feishu = feishu; this.email = email; this.trending = trending; this.rss = rss; this.aiSummary = aiSummary; this.qq = qq; }
    public Map<String, String> runtimes() { return Map.of("javascript", "GraalJS 25.0.2", "groovy", "Groovy " + groovy.lang.GroovySystem.getVersion()); }
    public Map<String, Object> capabilities() { return Map.of("runtimes", runtimes(), "sandbox", scripts.sandbox(), "maxNodes", WorkflowGraph.MAX_NODES, "maxConcurrentRuns", 2, "maxParallelNodes", MAX_PARALLEL_NODES, "scheduledExecution", true, "githubTrendingReady", trending.configured()); }
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
        graph.nodes().forEach(node -> privacy.collect(node.settings()));
        WorkflowGraph.Node start = graph.nodes().stream().filter(n -> "start".equals(n.type())).findFirst().orElseThrow();
        WorkflowGraph.Node end = graph.nodes().stream().filter(n -> "end".equals(n.type())).findFirst().orElseThrow();
        Map<String, WorkflowGraph.Node> nodes = new HashMap<>(); graph.nodes().forEach(n -> nodes.put(n.id(), n));
        var executor = new ThreadPoolExecutor(MAX_PARALLEL_NODES, MAX_PARALLEL_NODES, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(WorkflowGraph.MAX_NODES), Thread.ofPlatform().daemon().name("workflow-node-", 0).factory());
        var completed = new ExecutorCompletionService<CompletedNode>(executor);
        Set<Future<CompletedNode>> inFlight = new HashSet<>();
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        List<Exception> failures = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            inFlight.add(submitNode(completed, id, start, input, privacy, deadline));
            while (!inFlight.isEmpty()) {
                checkRun(id, deadline);
                Future<CompletedNode> finished = completed.poll(100, TimeUnit.MILLISECONDS);
                if (finished == null) continue;
                inFlight.remove(finished);
                CompletedNode current = finished.get();
                if (current.failure() instanceof InterruptedException interrupted) throw interrupted;
                if (current.failure() != null) { failures.add(current.failure()); continue; }
                NodeResult result = current.result();
                var next = graph.edges().stream().filter(e -> e.source().equals(current.node().id()) && Objects.equals(Objects.requireNonNullElse(e.branch(), ""), result.branch())).toList();
                if (next.isEmpty()) throw new IllegalArgumentException("执行路径未连接到结束节点");
                for (var edge : next) {
                    Map<String, Object> data = read(write(result.output()));
                    if (edge.target().equals(end.id())) outputs.put(edge.id(), data);
                    else inFlight.add(submitNode(completed, id, nodes.get(edge.target()), data, privacy, deadline));
                }
            }
            checkRun(id, deadline);
            if (failures.size() == 1) throw failures.getFirst();
            if (failures.size() > 1) throw new IllegalArgumentException("分支执行失败（" + failures.size() + " 个节点）；" + error(failures.getFirst(), privacy));
            NodeResult result = runNode(id, end, terminalInput(terminalOrder(graph, start).stream().filter(outputs::containsKey).map(outputs::get).toList()), privacy);
            store.finish(id, "SUCCEEDED", privacy.mask(result.output()), null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); store.finish(id, "CANCELLED", null, "执行已取消；已发送的请求或邮件可能已在对端生效");
        } catch (Exception error) { store.finish(id, "FAILED", null, error(error, privacy)); }
        finally { stopNodes(executor, inFlight); }
    }
    private Future<CompletedNode> submitNode(CompletionService<CompletedNode> completed, String id, WorkflowGraph.Node node,
                                              Map<String, Object> input, WorkflowPrivacy privacy, long deadline) {
        return completed.submit(() -> {
            try { checkRun(id, deadline); return new CompletedNode(node, runNode(id, node, input, privacy), null); }
            catch (Exception failure) { return new CompletedNode(node, null, failure); }
        });
    }
    /** Preserve the former breadth-first connection order, regardless of branch completion order. */
    private List<String> terminalOrder(WorkflowGraph graph, WorkflowGraph.Node start) {
        List<String> result = new ArrayList<>(); Deque<String> pending = new ArrayDeque<>(); pending.add(start.id());
        Set<String> ends = new HashSet<>(); graph.nodes().stream().filter(n -> "end".equals(n.type())).forEach(n -> ends.add(n.id()));
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            for (var edge : graph.edges()) if (edge.source().equals(node)) {
                if (ends.contains(edge.target())) result.add(edge.id()); else pending.addLast(edge.target());
            }
        }
        return result;
    }
    private void stopNodes(ExecutorService executor, Set<? extends Future<?>> inFlight) {
        boolean interrupted = Thread.interrupted();
        inFlight.forEach(task -> task.cancel(true)); executor.shutdownNow();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
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
        if (Thread.currentThread().isInterrupted() || !store.startNode(id, node, privacy.mask(input))) throw new InterruptedException();
        StringBuilder debug = new StringBuilder();
        Consumer<String> trace = WorkflowDiagnostics.trace(debug, privacy, logs -> store.updateNodeLogs(id, node.id(), logs));
        trace.accept("开始节点，类型=" + node.type() + "；输入 " + WorkflowDiagnostics.summary(input));
        trace.accept(WorkflowDiagnostics.templates(node));
        try {
            NodeResult result = execute(node, input, id, privacy, trace);
            privacy.collect(result.output());
            if (Thread.currentThread().isInterrupted() || store.cancelled(id)) throw new InterruptedException();
            trace.accept("节点完成；输出 " + WorkflowDiagnostics.summary(result.output()) + "；" + WorkflowDiagnostics.lengths(result.output()));
            store.finishNode(id, node.id(), "SUCCEEDED", privacy.mask(result.output()), privacy.message(WorkflowDiagnostics.combine(debug.toString(), result.logs())), null);
            return result;
        } catch (InterruptedException error) { throw error; }
        catch (Exception error) {
            trace.accept("节点失败：" + error(error, privacy));
            String logs = privacy.message(WorkflowDiagnostics.combine(debug.toString(), error instanceof ScriptProcessRunner.ScriptFailure failure ? failure.logs() : ""));
            store.finishNode(id, node.id(), "FAILED", null, logs, error(error, privacy)); throw error;
        }
    }
    private NodeResult execute(WorkflowGraph.Node node, Map<String, Object> input, String runId, WorkflowPrivacy privacy, Consumer<String> trace) throws Exception {
        Map<String, Object> config = node.settings();
        privacy.collect(config);
        Map<String, Object> ctx = Map.of("runId", runId, "nodeId", node.id(), "params", config.getOrDefault("params", Map.of()));
        if (Set.of("feishu", "email", "qq").contains(node.type())) trace.accept("解析消息模板；" + WorkflowDiagnostics.resolvedLengths(node, input, ctx));
        if (Set.of("feishu", "email", "qq").contains(node.type()) && ((List<?>)input.get("items")).isEmpty())
            return new NodeResult(input, "没有新增内容，跳过发送", "");
        return switch (node.type()) {
            case "start", "end" -> new NodeResult(input, "", "");
            case "script" -> {
                var result = scripts.execute(text(config.get("language")), text(config.get("source")), input, ctx, integer(config.get("timeoutMs"), 10_000, 100, 30_000));
                yield new NodeResult(result.output(), result.logs(), "");
            }
            case "http" -> new NodeResult(envelope(http.execute(config, input, ctx, privacy)), "", "");
            case "feishu" -> new NodeResult(feishu.execute(config, input, ctx, privacy), "飞书消息已发送", "");
            case "qq" -> new NodeResult(qq.execute(config, input, ctx, privacy, trace), "QQ 平台已接受消息", "");
            case "email" -> new NodeResult(email.execute(config, input, ctx, privacy), "邮件已提交至 SMTP 服务器", "");
            case "github-trending" -> new NodeResult(trending.execute(config, privacy, trace), "已抓取 GitHub Trending 并由所选模型生成项目解读、飞书消息和邮件正文", "");
            case "tech-weekly" -> new NodeResult(trending.executeWeekly(config, privacy, trace), "已分析最新科技爱好者周刊", "");
            case "rss" -> new NodeResult(rss.execute(config, runId, node.id(), privacy, trace), "已读取 RSS / Atom 订阅", "");
            case "ai-summary" -> new NodeResult(aiSummary.execute(config, input, privacy, trace), "已完成 AI 总结", "");
            case "if" -> new NodeResult(input, "", String.valueOf(WorkflowExpressions.condition(config, input, ctx)));
            default -> throw new IllegalArgumentException("不支持的节点类型");
        };
    }
    public Map<String, Object> test(WorkflowGraph.Node node, Map<String, Object> input) {
        if (node == null || node.id() == null || !node.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("请选择有效的调试节点");
        WorkflowGraph.validateNode(node); envelope(input);
        WorkflowPrivacy privacy = new WorkflowPrivacy(); privacy.collect(input); long started = System.currentTimeMillis();
        StringBuilder debug = new StringBuilder(); Consumer<String> trace = WorkflowDiagnostics.trace(debug, privacy, logs -> {});
        trace.accept("开始调试，类型=" + node.type() + "；输入 " + WorkflowDiagnostics.summary(input)); trace.accept(WorkflowDiagnostics.templates(node));
        Future<NodeResult> task;
        try { task = workers.submit(() -> execute(node, input, "preview-" + UUID.randomUUID(), privacy, trace)); }
        catch (RejectedExecutionException e) { throw busy(); }
        try {
            long timeout = "rss".equals(node.type()) ? integer(node.settings().get("timeoutMs"), WorkflowRss.DEFAULT_TIMEOUT, 1000, WorkflowRss.MAX_TIMEOUT) + 2000L : Set.of("github-trending", "tech-weekly", "ai-summary").contains(node.type()) ? integer(node.settings().get("timeoutMs"), WorkflowGithubTrending.DEFAULT_TIMEOUT, 1000, WorkflowGithubTrending.MAX_TIMEOUT) + 2000L : 32_000L;
            NodeResult result = task.get(timeout, TimeUnit.MILLISECONDS); privacy.collect(result.output());
            trace.accept("调试完成；输出 " + WorkflowDiagnostics.summary(result.output()) + "；" + WorkflowDiagnostics.lengths(result.output()));
            return Map.of("output", privacy.mask(result.output()), "logs", privacy.message(WorkflowDiagnostics.combine(debug.toString(), result.logs())), "branch", result.branch(), "durationMs", System.currentTimeMillis() - started);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("调试已取消"); }
        catch (TimeoutException e) { throw new IllegalArgumentException("调试等待超时，请稍后重试"); }
        catch (ExecutionException e) { throw new DebugFailure(error(e.getCause(), privacy), privacy.message(WorkflowDiagnostics.combine(debug.toString(), e.getCause() instanceof ScriptProcessRunner.ScriptFailure failure ? failure.logs() : ""))); }
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
