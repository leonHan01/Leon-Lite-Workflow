package com.leon.workflow.automation;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Field references only; expressions never evaluate user code in the application JVM. */
public final class WorkflowExpressions {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:(?:\\.[A-Za-z_][A-Za-z0-9_]*)|(?:\\[\\d{1,6}]))*");
    private WorkflowExpressions() {}
    public static List<String> references(Object value) {
        if (!(value instanceof String s)) return List.of();
        List<String> result = new ArrayList<>(); Matcher matcher = TEMPLATE.matcher(s);
        while (matcher.find()) { String path = matcher.group(1).trim(); if (PATH.matcher(path).matches()) result.add(path); }
        return result;
    }
    public static Object resolveRequired(Object value, Map<String, Object> input, Map<String, Object> ctx, String label) {
        for (String path : references(value)) {
            Object resolved = field(path, input, ctx);
            if (resolved == null || resolved instanceof String s && s.isBlank())
                throw new IllegalArgumentException(label + "引用的字段 " + path + " 不存在或为空，请检查上游节点输出");
        }
        return resolve(value, input, ctx);
    }
    public static Object resolve(Object value, Map<String, Object> input, Map<String, Object> ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(text(k), resolve(v, input, ctx))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> resolve(v, input, ctx)).toList();
        if (!(value instanceof String s)) return value;
        Matcher matcher = TEMPLATE.matcher(s);
        if (matcher.matches()) return field(matcher.group(1).trim(), input, ctx);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object resolved = field(matcher.group(1).trim(), input, ctx);
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved instanceof Map || resolved instanceof List ? write(resolved) : text(resolved)));
        }
        matcher.appendTail(result);
        if (result.length() > MAX_BYTES) throw new IllegalArgumentException("表达式结果过大");
        return result.toString();
    }
    private static Object field(String path, Map<String, Object> input, Map<String, Object> ctx) {
        if (!PATH.matcher(path).matches()) throw new IllegalArgumentException("只支持 input / ctx 的字段引用");
        String[] tokens = path.replace("[", ".").replace("]", "").split("\\.");
        Object current = Map.of("input", input, "ctx", ctx);
        for (String token : tokens) {
            if (Set.of("__proto__", "constructor", "prototype").contains(token)) throw new IllegalArgumentException("字段引用无效");
            if (current instanceof Map<?, ?> map) current = map.get(token);
            else if (current instanceof List<?> list && token.matches("\\d+")) {
                int index = Integer.parseInt(token); current = index < list.size() ? list.get(index) : null;
            } else return null;
        }
        return current;
    }
    public static boolean condition(Map<String, Object> c, Map<String, Object> input, Map<String, Object> ctx) {
        Object left = resolve(c.get("left"), input, ctx), right = resolve(c.get("right"), input, ctx);
        String op = text(c.get("operator"));
        if ("exists".equals(op)) return left != null;
        if ("truthy".equals(op)) return left != null && !Boolean.FALSE.equals(left) && !"".equals(left) && !(left instanceof Number n && n.doubleValue() == 0);
        if ("contains".equals(op)) return left instanceof List<?> list ? list.contains(right) : left instanceof String s && s.contains(text(right));
        boolean equal = left instanceof Number && right instanceof Number ? decimal(left).compareTo(decimal(right)) == 0 : Objects.equals(left, right);
        if ("eq".equals(op)) return equal;
        if ("neq".equals(op)) return !equal;
        if (left == null || right == null) return false;
        int comparison = decimal(left).compareTo(decimal(right));
        return switch (op) { case "gt" -> comparison > 0; case "gte" -> comparison >= 0; case "lt" -> comparison < 0; case "lte" -> comparison <= 0; default -> false; };
    }
    private static BigDecimal decimal(Object value) {
        try { return new BigDecimal(text(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("大小比较需要数值"); }
    }
}
