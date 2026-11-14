package com.leon.workflow.automation;

import java.util.*;
import java.util.regex.Pattern;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Per-run redaction, including secrets echoed into otherwise innocuous fields or log messages. */
public final class WorkflowPrivacy {
    private static final Pattern PRIVATE = Pattern.compile("(?i).*(authorization|cookie|password|secret|token|api[-_]?key|credential).*");
    private final Set<String> secrets = new HashSet<>();
    public void secret(String value) { if (value != null && value.length() >= 3) secrets.add(value); }
    public void collect(Object value) {
        if (value instanceof Map<?, ?> map) map.forEach((k, v) -> {
            if (PRIVATE.matcher(text(k)).matches() && v instanceof String s) secret(s);
            collect(v);
        });
        else if (value instanceof List<?> list) list.forEach(this::collect);
    }
    public String message(String value) {
        if (value == null) return null;
        String result = value;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) result = result.replace(secret, "[已隐藏]");
        return result;
    }
    public Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(text(k), PRIVATE.matcher(text(k)).matches() ? "[已隐藏]" : mask(v))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::mask).toList();
        return value instanceof String s ? message(s) : value;
    }
}
