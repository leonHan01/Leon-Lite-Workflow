package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD;

@Component
public class CalendarHandler extends AbstractEventHandler {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String[] WEEKDAY_LABELS = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] WEEKDAY_NAMES = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final String PAD = "\u3000";
    private static final String TODAY_LEFT = "\uFF3B";
    private static final String TODAY_RIGHT = "\uFF3D";

    private Clock clock = Clock.system(BEIJING_ZONE);

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        if (!matches(alfredParam.getOriginParams())) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.withDayOfMonth(1);
        String monthTitle = first.getYear() + "年" + first.getMonthValue() + "月";
        String header = weekdayHeader();
        List<String> weeks = weeksOf(first, today);
        String todayLine = "今天：" + today.format(DATE_FORMATTER);
        String calendar = monthTitle + "\n"
                + header + "\n"
                + String.join("\n", weeks) + "\n"
                + todayLine;

        List<AlfredResult> results = new ArrayList<>();
        results.add(line(monthTitle, "", calendar));
        results.add(line(header, "", calendar));
        for (String week : weeks) {
            results.add(line(week, "", week));
        }
        results.add(line(todayLine, WEEKDAY_NAMES[today.getDayOfWeek().getValue() - 1], today.format(DATE_FORMATTER)));
        return results;
    }

    private static String weekdayHeader() {
        StringBuilder header = new StringBuilder();
        for (String label : WEEKDAY_LABELS) {
            header.append(cellText(PAD, label, false));
        }
        return header.toString();
    }

    private static List<String> weeksOf(LocalDate first, LocalDate today) {
        LocalDate date = first.minusDays(first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        List<String> weeks = new ArrayList<>();
        while (!date.isAfter(last)) {
            StringBuilder week = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                week.append(cell(date, first, today));
                date = date.plusDays(1);
            }
            weeks.add(week.toString());
        }
        return weeks;
    }

    private static String cell(LocalDate date, LocalDate month, LocalDate today) {
        if (date.getMonthValue() != month.getMonthValue()) {
            return cellText(PAD, PAD, false);
        }
        String digits = toFullwidth(date.getDayOfMonth());
        String tens = digits.length() == 1 ? PAD : digits.substring(0, 1);
        String ones = digits.substring(digits.length() - 1);
        return cellText(tens, ones, date.equals(today));
    }

    private static String cellText(String tens, String ones, boolean today) {
        String left = today ? TODAY_LEFT : PAD;
        String right = today ? TODAY_RIGHT : PAD;
        return left + tens + ones + right;
    }

    private static String toFullwidth(int day) {
        String ascii = Integer.toString(day);
        StringBuilder out = new StringBuilder(ascii.length());
        for (int i = 0; i < ascii.length(); i++) {
            out.append((char) ('\uFF10' + ascii.charAt(i) - '0'));
        }
        return out.toString();
    }

    private static AlfredResult line(String title, String subtitle, String copy) {
        return AlfredResult.builder()
                .title(title)
                .subtitle(subtitle)
                .msgNeedToCopy(copy)
                .type(COPY_TO_CLIPBOARD)
                .icon("185_日程.png")
                .build();
    }

    private static boolean matches(String origin) {
        String command = StringUtils.trimToEmpty(origin).toLowerCase(Locale.ROOT);
        return "cal".equals(command) || "日历".equals(command);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return matches(param.getOriginParams());
    }

    @Override
    public Integer getPriority() {
        return 46;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }
}
