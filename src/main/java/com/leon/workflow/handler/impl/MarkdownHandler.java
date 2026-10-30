package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
public class MarkdownHandler extends AbstractEventHandler {

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam alfredParam) {
        List<AlfredResult> results = new ArrayList<>();
        results.add(AlfredResult.builder().title("callout 问题 思考").msgNeedToCopy(">[!QUESTION] 思考\n" +
                "> test").icon("question.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("note 笔记").msgNeedToCopy(">[!note] 笔记\n" +
                "> test").icon("note.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("tip 提示").msgNeedToCopy(">[!tip] 提示\n" +
                "> test").icon("tip.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("bug 缺陷").msgNeedToCopy(">[!bug] bug\n" +
                "> test").icon("bug.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("info 信息").msgNeedToCopy(">[!info] info\n" +
                "> test").icon("info.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("warning 警告").msgNeedToCopy(">[!warning] 警告\n" +
                "> test").icon("warning.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("danger error 错误").msgNeedToCopy(">[!error] 错误\n" +
                "> test").icon("error.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("quote 引用").msgNeedToCopy(">[!quote] quote\n" +
                "> test").icon("quote.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("example 例子").msgNeedToCopy(">[!example] 例子\n" +
                "> test").icon("example.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());

        results.add(AlfredResult.builder().title("tldr 摘要").msgNeedToCopy(">[!tldr] 摘要\n" +
                "> test").icon("tldr.png").type(COPY_TO_CLIPBOARD_AND_PASTE).build());
        return results;
    }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return param.getOriginParams().startsWith("md");
    }
}
