package com.leon.workflow.service;

import com.leon.workflow.dao.entity.TodoItem;
import com.leon.workflow.dao.repository.TodoItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

@Service
public class TodoDigestService {
    private static final Logger log = LoggerFactory.getLogger(TodoDigestService.class);
    private static final Pattern TLDR_PREFIX = Pattern.compile("(?i)^TL;\\s*DR\\s*[:：]?\\s*");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SUMMARY_SYSTEM = """
            你是工作效率顾问。根据用户的待办列表做日结总结。
            规则：
            1. 使用中文。
            2. 回复第一行必须是「TL;DR：」后接一句不超过 40 字的总结，前面不能有其他文字。
            3. 空一行后写：今日完成情况、仍待处理的重点、明天建议先做的 3 件事以内。
            4. 不要客套，不要复述全部原文。""";

    private final TodoItemRepository todoItemRepository;
    private final FeishuBotService feishuBotService;
    private final DeepseekChatService deepseekChatService;

    public TodoDigestService(TodoItemRepository todoItemRepository,
                             FeishuBotService feishuBotService,
                             DeepseekChatService deepseekChatService) {
        this.todoItemRepository = todoItemRepository;
        this.feishuBotService = feishuBotService;
        this.deepseekChatService = deepseekChatService;
    }

    public boolean sendStats(String slotLabel) {
        TodoSnapshot todos = activeTodos();
        String title = "待办统计 " + slotLabel;
        String markdown = statsMarkdown(todos);
        boolean ok = feishuBotService.sendCard(title, markdown, "blue");
        log.info("待办统计已{}：全部 {}，未完成 {}，已完成 {}", ok ? "发送" : "跳过", todos.total(), todos.open().size(), todos.done().size());
        return ok;
    }

    public boolean sendMidnightSummary() {
        TodoSnapshot todos = activeTodos();
        try {
            String summary = deepseekChatService.complete(SUMMARY_SYSTEM, summaryPrompt(todos));
            boolean ok = feishuBotService.sendCard("待办日结", ensureTldr(summary), "purple");
            log.info("待办 LLM 日结已{}：全部 {}，未完成 {}，已完成 {}", ok ? "发送" : "跳过", todos.total(), todos.open().size(), todos.done().size());
            return ok;
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted()) return false;
            log.warn("待办 LLM 日结失败，改为发送统计");
            String fallback = statsMarkdown(todos) + "\n\n_LLM 总结失败，已改为原始统计。_";
            return feishuBotService.sendCard("待办日结", fallback, "purple");
        }
    }

    private record TodoSnapshot(List<TodoItem> open, List<TodoItem> done) {
        int total() { return open.size() + done.size(); }
    }

    private TodoSnapshot activeTodos() {
        List<TodoItem> open = new ArrayList<>();
        List<TodoItem> done = new ArrayList<>();
        for (TodoItem todo : todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()) {
            (Boolean.TRUE.equals(todo.getDone()) ? done : open).add(todo);
        }
        return new TodoSnapshot(List.copyOf(open), List.copyOf(done));
    }

    private String statsMarkdown(TodoSnapshot todos) {
        StringBuilder text = new StringBuilder();
        text.append("**待办统计** ").append(LocalDateTime.now().format(CLOCK)).append("\n\n");
        text.append("- 全部：**").append(todos.total()).append("**\n");
        text.append("- 未完成：**").append(todos.open().size()).append("**\n");
        text.append("- 已完成：**").append(todos.done().size()).append("**\n");
        text.append("\n**未完成**\n");
        appendNumbered(text, todos.open());
        text.append("\n**已完成**\n");
        appendNumbered(text, todos.done());
        return text.toString();
    }

    private String summaryPrompt(TodoSnapshot todos) {
        StringBuilder text = new StringBuilder();
        text.append("请总结我今天的待办，回复必须以「TL;DR：」开头。\n\n");
        text.append("全部 ").append(todos.total()).append(" 条，未完成 ").append(todos.open().size())
                .append(" 条，已完成 ").append(todos.done().size()).append(" 条。\n\n");
        text.append("【未完成待办】\n");
        appendNumbered(text, todos.open());
        text.append("\n【已完成待办】\n");
        appendNumbered(text, todos.done());
        return text.toString();
    }

    private void appendNumbered(StringBuilder text, List<TodoItem> items) {
        if (items.isEmpty()) {
            text.append("（无）\n");
            return;
        }
        int index = 1;
        for (TodoItem item : items) {
            text.append(index++).append(". ").append(safeContent(item)).append("\n");
        }
    }

    private String safeContent(TodoItem item) {
        String content = item.getContent() == null ? "" : item.getContent().trim().replace("\n", " ");
        return content.isBlank() ? "(空)" : content;
    }

    private String ensureTldr(String content) {
        String text = content == null ? "" : content.trim();
        var prefix = TLDR_PREFIX.matcher(text);
        if (prefix.find()) {
            String[] lines = text.substring(prefix.end()).split("\\R", 2);
            return "TL;DR：" + summaryLine(lines[0]) + (lines.length > 1 ? "\n" + lines[1] : "");
        }
        String first = text.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("见下文总结。");
        return "TL;DR：" + summaryLine(first) + "\n\n" + text;
    }
    private String summaryLine(String value) {
        String line = value.replaceFirst("^[#*\\-\\s]+", "").trim();
        if (line.isBlank()) return "见下文总结。";
        return line.codePointCount(0, line.length()) <= 40 ? line : line.substring(0, line.offsetByCodePoints(0, 40));
    }
}
