package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;

@Component
public class TimeHandler extends AbstractEventHandler {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter BEIJING_DATE_TIME_INPUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern NOW_EXPRESSION_PATTERN = Pattern.compile("^(?:now|time|shijian|时间)((?:[+-]\\d+[smhd])*)$");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("([+-])(\\d+)([smhd])");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(?:\\d{10}|\\d{13})");
    private static final Pattern BEIJING_DATE_TIME_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    private Clock clock = Clock.system(BEIJING_ZONE);

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String input = normalizeInput(alfredParam.getOriginParams());
        if (TIMESTAMP_PATTERN.matcher(StringUtils.trimToEmpty(alfredParam.getOriginParams())).matches()) {
            long timestamp = Long.parseLong(input);
            Instant instant = input.length() == 10 ? Instant.ofEpochSecond(timestamp) : Instant.ofEpochMilli(timestamp);
            String beijing = instant.atZone(BEIJING_ZONE).format(DATE_TIME_FORMATTER);
            String utc = instant.atZone(ZoneId.of("UTC")).format(DATE_TIME_FORMATTER);
            results.add(buildTimeResult("北京时间：" + beijing, "点击复制北京时间", beijing));
            results.add(buildTimeResult("UTC：" + utc, "点击复制 UTC 时间", utc));
            return results;
        }
        Optional<ZonedDateTime> beijingDateTime = parseBeijingDateTime(StringUtils.trimToEmpty(alfredParam.getOriginParams()));
        if (beijingDateTime.isPresent()) {
            return copyTimestampResults(beijingDateTime.get());
        }
        String offsets = nowOffsets(input);
        if (offsets == null) {
            return results;
        }

        return copyTimestampResults(applyOffsets(ZonedDateTime.now(clock), offsets));
    }

    private Optional<ZonedDateTime> parseBeijingDateTime(String input) {
        if (!BEIJING_DATE_TIME_PATTERN.matcher(input).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(input, BEIJING_DATE_TIME_INPUT).atZone(BEIJING_ZONE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private List<AlfredResult> copyTimestampResults(ZonedDateTime dateTime) {
        long millis = dateTime.toInstant().toEpochMilli();
        long seconds = millis / 1000;
        String beijingTime = dateTime.format(DATE_TIME_FORMATTER);
        List<AlfredResult> results = new ArrayList<>();
        results.add(buildTimeResult("北京时间：" + beijingTime, "点击复制北京时间", beijingTime));
        results.add(buildTimeResult("秒级时间戳：" + seconds, "点击复制秒级时间戳", String.valueOf(seconds)));
        results.add(buildTimeResult("毫秒时间戳：" + millis, "点击复制毫秒时间戳", String.valueOf(millis)));
        return results;
    }

    private String normalizeInput(String input) {
        return StringUtils.trimToEmpty(input).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isNowExpression(String input) {
        return nowOffsets(input) != null;
    }

    private String nowOffsets(String input) {
        Matcher matcher = NOW_EXPRESSION_PATTERN.matcher(input);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private ZonedDateTime applyOffsets(ZonedDateTime dateTime, String offsets) {
        Matcher matcher = OFFSET_PATTERN.matcher(offsets);
        ZonedDateTime result = dateTime;
        while (matcher.find()) {
            int sign = "+".equals(matcher.group(1)) ? 1 : -1;
            long amount = Long.parseLong(matcher.group(2)) * sign;
            String unit = matcher.group(3);
            result = switch (unit) {
                case "s" -> result.plusSeconds(amount);
                case "m" -> result.plusMinutes(amount);
                case "h" -> result.plusHours(amount);
                case "d" -> result.plusDays(amount);
                default -> throw new IllegalArgumentException("Unsupported time unit: " + unit);
            };
        }
        return result;
    }

    private AlfredResult buildTimeResult(String title, String subtitle, String msgNeedToCopy) {
        return AlfredResult.builder()
                .title(title)
                .subtitle(subtitle)
                .type(COPY_TO_CLIPBOARD)
                .msgNeedToCopy(msgNeedToCopy)
                .build();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Boolean matchEvent(AlfredParam alfredParam, Boolean alreadyHaveResult) {
        String raw = StringUtils.trimToEmpty(alfredParam.getOriginParams());
        return isNowExpression(normalizeInput(raw))
                || TIMESTAMP_PATTERN.matcher(raw).matches()
                || parseBeijingDateTime(raw).isPresent();
    }

    @Override
    public Integer getPriority() {
        return 48;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
