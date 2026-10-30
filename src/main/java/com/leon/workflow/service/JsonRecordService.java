package com.leon.workflow.service;

import com.leon.workflow.dao.entity.JsonRecord;
import com.leon.workflow.dao.repository.JsonRecordRepository;
import com.leon.workflow.domain.dto.JsonRecordSearchResult;
import com.leon.workflow.domain.dto.ParsedJsonRecord;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class JsonRecordService {

    private final JsonRecordRepository jsonRecordRepository;

    private final JsonRecordParser jsonRecordParser;

    public JsonRecordService(JsonRecordRepository jsonRecordRepository, JsonRecordParser jsonRecordParser) {
        this.jsonRecordRepository = jsonRecordRepository;
        this.jsonRecordParser = jsonRecordParser;
    }

    public JsonRecord save(String input) {
        ParsedJsonRecord parsed = parse(input);
        if (!parsed.isValid()) {
            throw new IllegalArgumentException("input does not contain valid json");
        }

        String contentHash = sha256(parsed.getRawText());
        Optional<JsonRecord> existing = jsonRecordRepository.findByContentHash(contentHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDateTime now = LocalDateTime.now();
        JsonRecord jsonRecord = new JsonRecord();
        jsonRecord.setContentHash(contentHash);
        jsonRecord.setSourceType(parsed.getSourceType());
        jsonRecord.setRawText(parsed.getRawText());
        jsonRecord.setBodyJson(parsed.getBodyJson());
        jsonRecord.setReqJson(parsed.getReqJson());
        jsonRecord.setRespJson(parsed.getRespJson());
        jsonRecord.setCreateTime(now);
        jsonRecord.setUpdateTime(now);
        try {
            return jsonRecordRepository.saveAndFlush(jsonRecord);
        } catch (DataIntegrityViolationException conflict) {
            // The repository write transaction has rolled back before checking a concurrent winner.
            return jsonRecordRepository.findByContentHash(contentHash).orElseThrow(() -> conflict);
        }
    }

    public Optional<JsonRecord> findById(Long id) {
        return jsonRecordRepository.findById(id);
    }

    public List<JsonRecordSearchResult> search(String keyword, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 100));
        String safeKeyword = StringUtils.trimToEmpty(keyword);
        return jsonRecordRepository.search(safeKeyword, PageRequest.of(0, safeLimit))
                .stream()
                .map(record -> buildSearchResult(record, safeKeyword))
                .toList();
    }

    public ParsedJsonRecord parse(String input) {
        return jsonRecordParser.parse(input);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not supported", e);
        }
    }

    private JsonRecordSearchResult buildSearchResult(JsonRecord record, String keyword) {
        JsonRecordSearchResult result = new JsonRecordSearchResult();
        result.setId(record.getId());
        result.setSourceType(record.getSourceType());
        result.setCreateTime(record.getCreateTime());
        result.setUpdateTime(record.getUpdateTime());
        SearchText match = matchedText(record, keyword);
        result.setMatchField(match.field());
        result.setPreview(buildPreview(match.value(), keyword));
        return result;
    }

    private record SearchText(String field, String value) {}

    private SearchText matchedText(JsonRecord record, String keyword) {
        List<SearchText> fields = List.of(new SearchText("reqJson", record.getReqJson()),
                new SearchText("respJson", record.getRespJson()), new SearchText("bodyJson", record.getBodyJson()),
                new SearchText("rawText", record.getRawText()));
        SearchText fallback = fields.stream().filter(field -> StringUtils.isNotBlank(field.value()))
                .findFirst().orElse(fields.getLast());
        if (StringUtils.isBlank(keyword)) return fallback;
        return fields.stream().filter(field -> StringUtils.containsIgnoreCase(field.value(), keyword))
                .findFirst().orElse(fallback);
    }

    private String buildPreview(String value, String keyword) {
        String compact = StringUtils.defaultString(value).replaceAll("\\s+", " ");
        if (StringUtils.isBlank(keyword)) {
            return StringUtils.abbreviate(compact, 220);
        }

        int matchIndex = StringUtils.indexOfIgnoreCase(compact, keyword);
        if (matchIndex < 0) {
            return StringUtils.abbreviate(compact, 220);
        }

        int start = Math.max(0, matchIndex - 80);
        int end = Math.min(compact.length(), matchIndex + keyword.length() + 120);
        String preview = compact.substring(start, end);
        if (start > 0) {
            preview = "..." + preview;
        }
        if (end < compact.length()) {
            preview = preview + "...";
        }
        return preview;
    }
}
