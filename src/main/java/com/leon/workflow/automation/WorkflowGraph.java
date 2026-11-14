package com.leon.workflow.automation;

import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** The first release supports a rooted DAG with branches, but no joins or loops. */
public record WorkflowGraph(List<Node> nodes, List<Edge> edges) {
    public record Node(String id, String type, String name, Map<String, Object> config, Map<String, Object> position) {
        public Map<String, Object> settings() { return config == null ? Map.of() : config; }
    }
    public record Edge(String id, String source, String target, String branch) {}

    public static WorkflowGraph parse(Object value) {
        return JSON.convertValue(value, WorkflowGraph.class).validate();
    }
    public static WorkflowGraph initial() {
        return new WorkflowGraph(List.of(new Node("start", "manual", "手动触发", Map.of(), Map.of("x", 80, "y", 140))), List.of());
    }
    public WorkflowGraph validate() {
        if (nodes == null || nodes.isEmpty() || nodes.size() > 64 || edges == null || edges.size() > 128)
            throw new IllegalArgumentException("流程需要 1–64 个节点");
        checkSize(this);
        Map<String, Node> byId = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>(), paths = new HashSet<>(), targets = new HashSet<>();
        for (Node node : nodes) {
            if (node == null || node.id() == null || !node.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || byId.put(node.id(), node) != null)
                throw new IllegalArgumentException("节点 ID 无效或重复");
            required(node.name(), "节点名称需要 1–80 个字符", 80);
            validateNode(node);
        }
        List<Node> starts = nodes.stream().filter(n -> "manual".equals(n.type())).toList();
        if (starts.size() != 1) throw new IllegalArgumentException("流程需要且只能有一个手动触发节点");
        for (Edge edge : edges) {
            if (edge == null || edge.id() == null || !edgeIds.add(edge.id()) || !byId.containsKey(edge.source()) || !byId.containsKey(edge.target()))
                throw new IllegalArgumentException("连线 ID 重复或引用了不存在的节点");
            if (edge.target().equals(starts.getFirst().id())) throw new IllegalArgumentException("不能连接到触发节点");
            if (!targets.add(edge.target())) throw new IllegalArgumentException("一期暂不支持多路汇合，请为每个分支使用独立节点");
            Node source = byId.get(edge.source());
            String branch = edge.branch() == null ? "" : edge.branch();
            if ("if".equals(source.type()) ? !Set.of("true", "false").contains(branch) : !branch.isEmpty())
                throw new IllegalArgumentException("IF 连线必须选择 true/false，其他节点使用默认出口");
            if (!paths.add(edge.source() + ":" + branch)) throw new IllegalArgumentException("同一个出口只能连接一个节点");
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add(starts.getFirst().id());
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!visited.add(id)) throw new IllegalArgumentException("流程不能包含循环");
            edges.stream().filter(e -> e.source().equals(id)).forEach(e -> queue.add(e.target()));
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("存在未连接到触发节点的节点或循环");
        return this;
    }
    public static void validateNode(Node node) {
        Map<String, Object> c = node.settings();
        switch (text(node.type())) {
            case "manual" -> {}
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
