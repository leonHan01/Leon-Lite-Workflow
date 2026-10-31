package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
public class MarkdownHandler extends AbstractEventHandler {

    private static final List<Syntax> SYNTAX = List.of(
            syntax("标题", "h1 h2 heading 标题", "# 标题", "201_文档.png"),
            syntax("加粗", "bold strong 加粗", "**加粗**", "201_文档.png"),
            syntax("斜体", "italic em 斜体", "*斜体*", "201_文档.png"),
            syntax("链接", "link url 链接", "[文字](https://example.com)", "link.png"),
            syntax("图片", "image 图片", "![替代文字](https://example.com/image.png)", "image.png"),
            syntax("行内代码", "inline code 行内代码", "`code`", "代码.png"),
            syntax("代码块", "code fence block 代码块", "```\ncode\n```", "代码.png"),
            syntax("表格", "table 表格", "| 列1 | 列2 |\n| --- | --- |\n|  |  |", "201_文档.png"),
            syntax("列表", "ul ol list 列表 无序 有序", "- 项目\n1. 项目", "201_文档.png"),
            syntax("任务列表", "task todo checkbox 任务 待办", "- [ ] 待办", "note.png"),
            syntax("callout 问题 思考", "question callout 标注", ">[!QUESTION] 思考\n> test", "question.png"),
            syntax("note 笔记", "note callout 笔记", ">[!note] 笔记\n> test", "note.png"),
            syntax("tip 提示", "tip callout 提示", ">[!tip] 提示\n> test", "tip.png"),
            syntax("bug 缺陷", "bug callout 缺陷", ">[!bug] bug\n> test", "bug.png"),
            syntax("info 信息", "info callout 信息", ">[!info] info\n> test", "info.png"),
            syntax("warning 警告", "warning callout 警告", ">[!warning] 警告\n> test", "warning.png"),
            syntax("danger error 错误", "danger error callout 错误", ">[!error] 错误\n> test", "error.png"),
            syntax("quote 引用", "quote callout 引用", ">[!quote] quote\n> test", "quote.png"),
            syntax("example 例子", "example callout 例子", ">[!example] 例子\n> test", "example.png"),
            syntax("tldr 摘要", "tldr callout 摘要", ">[!tldr] 摘要\n> test", "tldr.png")
    );

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        String query = queryText(alfredParam.getOriginParams());
        if (query == null) {
            return List.of();
        }
        List<String> terms = query.isEmpty() ? List.of() : List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        List<AlfredResult> results = new ArrayList<>();
        for (Syntax syntax : SYNTAX) {
            if (matches(syntax, terms)) {
                results.add(syntax.toResult());
            }
        }
        if (results.isEmpty()) {
            results.add(AlfredResult.builder()
                    .title("没有匹配的 Markdown 语法")
                    .subtitle("试试 md 表格、md 代码、md 链接")
                    .icon("201_文档.png")
                    .autoComplete("md ")
                    .build());
        }
        return results;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return queryText(param.getOriginParams()) != null;
    }

    @Override
    public Integer getPriority() {
        return 47;
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() {
        return true;
    }

    private static String queryText(String origin) {
        String raw = StringUtils.trimToEmpty(origin);
        if (raw.length() < 2 || !raw.regionMatches(true, 0, "md", 0, 2)) {
            return null;
        }
        if (raw.length() == 2) {
            return "";
        }
        if (!Character.isWhitespace(raw.charAt(2))) {
            return null;
        }
        return raw.substring(2).trim();
    }

    private static boolean matches(Syntax syntax, List<String> terms) {
        String haystack = (syntax.title + " " + syntax.keywords).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private static Syntax syntax(String title, String keywords, String body, String icon) {
        return new Syntax(title, keywords, body, icon);
    }

    private record Syntax(String title, String keywords, String body, String icon) {
        private AlfredResult toResult() {
            String preview = body.replace('\n', ' ');
            return AlfredResult.builder()
                    .title(title)
                    .subtitle(preview)
                    .msgNeedToCopy(body)
                    .autoComplete("md " + title)
                    .icon(icon)
                    .type(COPY_TO_CLIPBOARD_AND_PASTE)
                    .build();
        }
    }
}
