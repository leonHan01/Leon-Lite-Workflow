package com.leon.workflow.automation;

import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;

/** JSON is the only interchange format between the engine and node runners. */
public final class WorkflowJson {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int MAX_BYTES = 1_048_576;
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(authorization|cookie|password|secret|token|api[-_]?key|credential).* ".trim());
    private WorkflowJson() {}

    public static String write(Object value) { return JSON.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("需要 JSON 对象");
        return (Map<String, Object>) value;
    }
    public static Map<String, Object> read(String value) { return object(JSON.readValue(value, Map.class)); }
    public static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    public static String required(Object value, String message, int max) {
        String s = text(value).trim();
        if (s.isEmpty() || s.length() > max) throw new IllegalArgumentException(message);
        return s;
    }
    public static int integer(Object value, int fallback, int min, int max) {
        try {
            int n = value == null ? fallback : Integer.parseInt(value.toString());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("数值范围应为 " + min + "–" + max);
    }
    public static Map<String, Object> envelope(Object value) {
        Map<String, Object> map = object(value);
        if (!(map.get("items") instanceof List<?> items) || items.size() > 1000)
            throw new IllegalArgumentException("输入输出必须包含 items 数组，最多 1000 条");
        for (Object item : items) object(object(item).get("json"));
        checkSize(map);
        return map;
    }
    public static void checkSize(Object value) {
        if (write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("节点数据不能超过 1 MB");
    }
    public static Object redact(Object value) { return redact(value, 0); }
    private static Object redact(Object value, int depth) {
        if (depth > 32) return "[内容过深]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, v) -> result.put(text(key), SENSITIVE.matcher(text(key)).matches() ? "[已隐藏]" : redact(v, depth + 1)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> redact(v, depth + 1)).toList();
        return value;
    }
}
