package com.leon.workflow.automation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Metadata only: never include payload bodies, recipient addresses, secrets or provider replies. */
final class WorkflowDiagnostics {
    private WorkflowDiagnostics() {}
    static Consumer<String> trace(StringBuilder buffer, WorkflowPrivacy privacy, Consumer<String> sink) {
        long started = System.nanoTime();
        return event -> {
            String next = "[+" + (System.nanoTime() - started) / 1_000_000 + " ms] " + privacy.message(event) + "\n";
            String bounded = combine(buffer.toString(), next);
            buffer.setLength(0); buffer.append(bounded); sink.accept(bounded);
        };
    }
    static String summary(Object value) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("items") instanceof List<?> items)) return "无 items 数据";
        var first = !items.isEmpty() && items.getFirst() instanceof Map<?, ?> item ? item.get("json") : null;
        String fields = first instanceof Map<?, ?> json ? json.keySet().stream().map(WorkflowJson::text).sorted().limit(30).toList().toString() : "[]";
        return "items=" + items.size() + "，首项字段=" + fields;
    }
    static String templates(WorkflowGraph.Node node) {
        List<String> refs = new ArrayList<>();
        for (String field : List.of("text", "title", "subject", "body"))
            for (String path : WorkflowExpressions.references(node.settings().get(field))) refs.add(field + " → " + path);
        return refs.isEmpty() ? "未使用消息字段引用" : "消息字段引用：" + String.join("；", refs);
    }
    static String resolvedLengths(WorkflowGraph.Node node, Map<String, Object> input, Map<String, Object> ctx) {
        List<String> fields = "feishu".equals(node.type()) ? List.of("text") : "email".equals(node.type()) ? List.of("subject", "body") : List.of();
        List<String> lengths = new ArrayList<>();
        for (String key : fields) {
            Object value = WorkflowExpressions.resolve(node.settings().get(key), input, ctx);
            lengths.add(key + "=" + text(value).length() + " 字符");
        }
        return String.join("，", lengths);
    }
    static String lengths(Object output) {
        if (!(output instanceof Map<?, ?> map) || !(map.get("items") instanceof List<?> rows) || rows.isEmpty()
                || !(rows.getFirst() instanceof Map<?, ?> item) || !(item.get("json") instanceof Map<?, ?> json)) return "";
        List<String> lengths = new ArrayList<>();
        for (String key : List.of("feishuText", "feishuMarkdown", "emailSubject", "emailBody", "emailHtml"))
            if (json.get(key) instanceof String content) lengths.add(key + "=" + content.length() + " 字符");
        return String.join("，", lengths);
    }
    static String combine(String... parts) {
        String joined = String.join("\n", Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
        if (joined.getBytes(StandardCharsets.UTF_8).length <= 16_384) return joined;
        int end = 0, size = 0;
        while (end < joined.length()) {
            int cp = joined.codePointAt(end), bytes = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (size + bytes > 16_350) break;
            size += bytes; end += Character.charCount(cp);
        }
        return joined.substring(0, end) + "\n[日志已截断]";
    }
}
