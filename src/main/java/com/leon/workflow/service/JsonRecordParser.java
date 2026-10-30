package com.leon.workflow.service;

import com.alibaba.fastjson.JSON;
import com.leon.workflow.domain.dto.ParsedJsonRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JsonRecordParser {

    private static final Pattern REQ_PATTERN = Pattern.compile("\\breq\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESP_PATTERN = Pattern.compile("\\bresp\\s*=", Pattern.CASE_INSENSITIVE);

    public ParsedJsonRecord parse(String input) {
        String rawText = StringUtils.trimToEmpty(input);
        ParsedJsonRecord parsed = new ParsedJsonRecord();
        parsed.setRawText(rawText);

        if (StringUtils.isBlank(rawText)) {
            return parsed;
        }

        String directJson = normalizeJson(rawText);
        if (directJson != null) {
            parsed.setSourceType("JSON");
            parsed.setBodyJson(directJson);
            return parsed;
        }

        String reqJson = extractJsonAfterMarker(rawText, REQ_PATTERN);
        String respJson = extractJsonAfterMarker(rawText, RESP_PATTERN);
        if (reqJson == null && respJson == null) {
            List<String> jsonSnippets = extractJsonSnippets(rawText);
            if (jsonSnippets.size() < 2) {
                return parsed;
            }
            reqJson = jsonSnippets.get(0);
            respJson = jsonSnippets.get(1);
        }

        parsed.setReqJson(reqJson);
        parsed.setRespJson(respJson);
        if (reqJson != null && respJson != null) {
            parsed.setSourceType("REQ_RESP");
        } else if (reqJson != null) {
            parsed.setSourceType("REQ");
        } else {
            parsed.setSourceType("RESP");
        }
        return parsed;
    }

    private String extractJsonAfterMarker(String text, Pattern markerPattern) {
        Matcher matcher = markerPattern.matcher(text);
        while (matcher.find()) {
            int jsonStart = findJsonStart(text, matcher.end());
            if (jsonStart < 0) {
                continue;
            }

            String jsonCandidate = extractBalancedJson(text, jsonStart);
            String normalized = normalizeJson(jsonCandidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private List<String> extractJsonSnippets(String text) {
        List<String> jsonSnippets = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c != '{' && c != '[') {
                index++;
                continue;
            }

            String jsonCandidate = extractBalancedJson(text, index);
            String normalized = normalizeJson(jsonCandidate);
            if (normalized != null) {
                jsonSnippets.add(normalized);
                index += jsonCandidate.length();
            } else {
                index++;
            }
        }
        return jsonSnippets;
    }

    private int findJsonStart(String text, int fromIndex) {
        for (int i = fromIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ':' || c == '-') {
                continue;
            }
            if (c == '{' || c == '[') {
                return i;
            }
            return -1;
        }
        return -1;
    }

    private String extractBalancedJson(String text, int startIndex) {
        StringBuilder expectedClosers = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = startIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                expectedClosers.append('}');
            } else if (c == '[') {
                expectedClosers.append(']');
            } else if (expectedClosers.length() > 0 && c == expectedClosers.charAt(expectedClosers.length() - 1)) {
                expectedClosers.deleteCharAt(expectedClosers.length() - 1);
                if (expectedClosers.length() == 0) {
                    return text.substring(startIndex, i + 1);
                }
            }
        }
        return null;
    }

    private String normalizeJson(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]")))) {
            return null;
        }
        try {
            return JSON.toJSONString(JSON.parse(trimmed));
        } catch (Exception e) {
            return null;
        }
    }
}
