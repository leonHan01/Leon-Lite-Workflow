package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.handler.AbstractEventHandler;
import com.leon.workflow.service.YoudaoTranslationService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

import static com.leon.workflow.constant.AlfredConstant.AlfredMsgType.COPY_TO_CLIPBOARD_AND_PASTE;

@Component
public class TranslateHandler extends AbstractEventHandler {
    private final YoudaoTranslationService translator;

    public TranslateHandler(YoudaoTranslationService translator) { this.translator = translator; }

    @Override
    public List<AlfredResult> doHandleAlfredEvent(AlfredParam param) {
        String text = param.getParamsWithoutInstruction();
        if (StringUtils.isBlank(text)) return List.of(hint("请输入要翻译的内容"));
        if (!translator.configured()) return List.of(hint("请配置有道翻译密钥"));
        return translator.translate(text).stream()
                .filter(value -> value.source() != null || !value.value().equals(param.getOriginParams()))
                .map(value -> AlfredResult.builder()
                        .title(value.source() == null ? value.value() : value.source() + ": " + value.value())
                        .type(COPY_TO_CLIPBOARD_AND_PASTE).icon("translate.png").msgNeedToCopy(value.value()).build()).toList();
    }

    private AlfredResult hint(String title) { return AlfredResult.builder().title(title).icon("translate.png").build(); }

    @Override
    public Boolean matchEvent(AlfredParam param, Boolean alreadyHaveResult) {
        return StringUtils.defaultString(param.getInstruction()).toLowerCase(Locale.ROOT).startsWith("fy");
    }

    @Override
    public Boolean breakNextHandlerIfHaveResult() { return true; }
}
