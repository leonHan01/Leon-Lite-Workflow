package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;

@Component
public class TimeHandler extends AbstractEventHandler {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NOW_EXPRESSION_PATTERN = Pattern.compile("^now((?:[+-]\\d+[smhd])*)$");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("([+-])(\\d+)([smhd])");

    private Clock clock = Clock.system(BEIJING_ZONE);

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        String input = normalizeInput(alfredParam.getOriginParams());
        if (!isNowExpression(input)) {
            return results;
        }

        ZonedDateTime dateTime = applyOffsets(ZonedDateTime.now(clock), input);
        long millis = dateTime.toInstant().toEpochMilli();
        long seconds = millis / 1000;
        String beijingTime = dateTime.format(DATE_TIME_FORMATTER);

        results.add(buildTimeResult("北京时间：" + beijingTime, "点击复制北京时间", beijingTime));
        results.add(buildTimeResult("秒级时间戳：" + seconds, "点击复制秒级时间戳", String.valueOf(seconds)));
        results.add(buildTimeResult("毫秒时间戳：" + millis, "点击复制毫秒时间戳", String.valueOf(millis)));
        return results;
    }

    private String normalizeInput(String input) {
        return StringUtils.trimToEmpty(input).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isNowExpression(String input) {
        return NOW_EXPRESSION_PATTERN.matcher(input).matches();
    }

    private ZonedDateTime applyOffsets(ZonedDateTime dateTime, String input) {
        Matcher matcher = OFFSET_PATTERN.matcher(input.substring(3));
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
        return isNowExpression(normalizeInput(alfredParam.getOriginParams()));
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
