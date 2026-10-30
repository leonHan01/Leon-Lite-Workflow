package com.leon.workflow.service;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

final class Payloads {
    private Payloads() {
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String requiredString(Object value, String message) {
        String text = stringValue(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String normalizeUrl(String url) {
        String value = url.trim();
        return value.contains("://") ? value : "https://" + value;
    }

    static Long longValue(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("ID 必须是有效的长整数");
            }
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            Long id = longValue(item);
            if (id == null) throw new IllegalArgumentException("ID 列表不能包含空值");
            result.add(id);
        }
        return result;
    }

    static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
