package com.leon.workflow.automation;

import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Branches share one terminal node; processing nodes cannot join or loop. */
public record WorkflowGraph(List<Node> nodes, List<Edge> edges) {
    // One extra terminal preserves capacity for legacy workflows with 64 nodes.
    public static final int MAX_NODES = 65;
    public record Node(String id, String type, String name, Map<String, Object> config, Map<String, Object> position) {
        public Map<String, Object> settings() { return config == null ? Map.of() : config; }
    }
    public record Edge(String id, String source, String target, String branch) {}

    public static WorkflowGraph parse(Object value) {
        return JSON.convertValue(value, WorkflowGraph.class).validate();
    }
    public static WorkflowGraph initial() {
        return new WorkflowGraph(List.of(
                new Node("start", "start", "开始", Map.of(), Map.of("x", 80, "y", 140)),
                new Node("end", "end", "结束", Map.of(), Map.of("x", 420, "y", 140))),
                List.of(new Edge("start_end", "start", "end", "")));
    }
    /** Adapt stored legacy definitions without rewriting immutable versions or historical runs. */
    public static WorkflowGraph stored(Object value) {
        WorkflowGraph graph = JSON.convertValue(value, WorkflowGraph.class);
        if (graph.nodes() == null || graph.edges() == null || graph.nodes().stream().noneMatch(n -> n != null && "manual".equals(n.type()))) return graph;
        List<Node> nodes = new ArrayList<>();
        for (Node node : graph.nodes()) nodes.add(node != null && "manual".equals(node.type())
                ? new Node(node.id(), "start", "手动触发".equals(node.name()) ? "开始" : node.name(), node.config(), node.position()) : node);
        if (nodes.stream().anyMatch(n -> n != null && "end".equals(n.type()))) return new WorkflowGraph(nodes, graph.edges());
        Set<String> ids = new HashSet<>(), edgeIds = new HashSet<>(), exits = new HashSet<>();
        int x = 80, y = 140;
        for (Node node : nodes) {
            if (node == null) continue;
            ids.add(node.id());
            if (node.position() != null) {
                if (node.position().get("x") instanceof Number n) x = Math.max(x, Math.min(50000, n.intValue()));
                if (node.position().get("y") instanceof Number n) y = Math.max(y, Math.min(50000, n.intValue()));
            }
        }
        String end = "end";
        for (int i = 1; ids.contains(end); i++) end = "end_" + i;
        List<Edge> edges = new ArrayList<>(graph.edges());
        for (Edge edge : edges) if (edge != null) { edgeIds.add(edge.id()); exits.add(edge.source() + ":" + Objects.requireNonNullElse(edge.branch(), "")); }
        int sequence = 0;
        for (Node node : nodes) {
            if (node == null) continue;
            for (String branch : "if".equals(node.type()) ? List.of("true", "false") : List.of("")) {
                if (exits.contains(node.id() + ":" + branch)) continue;
                String edgeId;
                do { edgeId = "terminal_" + sequence++; } while (!edgeIds.add(edgeId));
                edges.add(new Edge(edgeId, node.id(), end, branch));
            }
        }
        nodes.add(new Node(end, "end", "结束", Map.of(), Map.of("x", Math.min(50000, x + 280), "y", x > 49720 ? Math.min(50000, y + 160) : y)));
        return new WorkflowGraph(nodes, edges);
    }
    public WorkflowGraph validateDraft() {
        if (nodes == null || nodes.size() < 2 || nodes.size() > MAX_NODES || edges == null || edges.size() > 128)
            throw new IllegalArgumentException("流程需要 2–" + MAX_NODES + " 个节点，且包含开始和结束节点");
        checkSize(this);
        Set<String> ids = new HashSet<>();
        for (Node node : nodes) {
            if (node == null || node.id() == null || !node.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ids.add(node.id()))
                throw new IllegalArgumentException("节点 ID 无效或重复");
            if ("manual".equals(node.type())) throw new IllegalArgumentException("手动触发节点已改为开始节点，请重新加载后保存");
        }
        if (nodes.stream().filter(n -> "start".equals(n.type())).count() != 1) throw new IllegalArgumentException("流程需要且只能有一个开始节点");
        if (nodes.stream().filter(n -> "end".equals(n.type())).count() != 1) throw new IllegalArgumentException("流程需要且只能有一个结束节点");
        return this;
    }
    public WorkflowGraph validate() {
        validateDraft();
        Map<String, Node> byId = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>(), paths = new HashSet<>(), connections = new HashSet<>(), targets = new HashSet<>();
        for (Node node : nodes) {
            byId.put(node.id(), node);
            required(node.name(), "节点名称需要 1–80 个字符", 80);
            validateNode(node);
        }
        Node start = nodes.stream().filter(n -> "start".equals(n.type())).findFirst().orElseThrow();
        for (Edge edge : edges) {
            if (edge == null || edge.id() == null || !edgeIds.add(edge.id()) || !byId.containsKey(edge.source()) || !byId.containsKey(edge.target()))
                throw new IllegalArgumentException("连线 ID 重复或引用了不存在的节点");
            if (edge.target().equals(start.id())) throw new IllegalArgumentException("开始节点不能有入口");
            if (!targets.add(edge.target()) && !"end".equals(byId.get(edge.target()).type())) throw new IllegalArgumentException("仅结束节点支持多路汇入，请为每个分支使用独立处理节点");
            Node source = byId.get(edge.source());
            if ("end".equals(source.type())) throw new IllegalArgumentException("结束节点不能有出口");
            String branch = edge.branch() == null ? "" : edge.branch();
            if ("if".equals(source.type()) ? !Set.of("true", "false").contains(branch) : !branch.isEmpty())
                throw new IllegalArgumentException("IF 连线必须选择 true/false，其他节点使用默认出口");
            paths.add(edge.source() + ":" + branch);
            if (!connections.add(edge.source() + ":" + branch + ":" + edge.target())) throw new IllegalArgumentException("同一个出口不能重复连接同一个节点");
        }
        for (Node node : nodes) {
            if ("end".equals(node.type())) continue;
            for (String branch : "if".equals(node.type()) ? List.of("true", "false") : List.of(""))
                if (!paths.contains(node.id() + ":" + branch)) throw new IllegalArgumentException("所有执行路径必须连接到结束节点，请连接“" + node.name() + "”的" + (branch.isEmpty() ? "出口" : branch + " 分支"));
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add(start.id());
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!visited.add(id)) {
                if ("end".equals(byId.get(id).type())) continue;
                throw new IllegalArgumentException("流程不能包含循环");
            }
            edges.stream().filter(e -> e.source().equals(id)).forEach(e -> queue.add(e.target()));
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("存在未连接到开始节点的节点或循环");
        return this;
    }
    public static void validateNode(Node node) {
        Map<String, Object> c = node.settings();
        switch (text(node.type())) {
            case "start", "end" -> {}
            case "feishu" -> WorkflowFeishu.validateConfig(c);
            case "email" -> WorkflowEmail.validateConfig(c);
            case "github-trending" -> WorkflowGithubTrending.validateConfig(c);
            case "tech-weekly" -> WorkflowGithubTrending.validateWeeklyConfig(c);
            case "rss" -> WorkflowRss.validateConfig(c);
            case "ai-summary" -> WorkflowAiSummary.validateConfig(c);
            case "script" -> {
                if (!Set.of("javascript", "groovy").contains(text(c.get("language")))) throw new IllegalArgumentException("请选择 JavaScript 或 Groovy");
                required(c.get("source"), "脚本不能为空且不能超过 64 KB", 65_536);
                integer(c.get("timeoutMs"), 10_000, 100, 30_000);
                if (c.containsKey("params")) object(c.get("params"));
            }
            case "http" -> {
                required(c.get("url"), "请填写 HTTP 地址", 8192);
                if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(text(c.getOrDefault("method", "GET"))))
                    throw new IllegalArgumentException("不支持的 HTTP 方法");
                if (c.containsKey("headers")) object(c.get("headers"));
                integer(c.get("timeoutMs"), 10_000, 100, 30_000);
            }
            case "if" -> {
                if (!Set.of("eq", "neq", "gt", "gte", "lt", "lte", "contains", "exists", "truthy").contains(text(c.get("operator"))))
                    throw new IllegalArgumentException("请选择有效的判断条件");
            }
            default -> throw new IllegalArgumentException("不支持的节点类型");
        }
    }
}
