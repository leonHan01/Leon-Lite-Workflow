package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.service.YoudaoTranslationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranslateHandlerTest {
    private final YoudaoTranslationService translator = mock(YoudaoTranslationService.class);
    private final TranslateHandler handler = new TranslateHandler(translator);

    @Test void hintsDoNotSendTranslationRequests() {
        assertEquals("请输入要翻译的内容", handler.doHandleAlfredEvent(param("fy")).getFirst().getTitle());
        assertEquals("请配置有道翻译密钥", handler.doHandleAlfredEvent(param("fy hello")).getFirst().getTitle());
        verify(translator, never()).translate(anyString());
        assertFalse(handler.matchEvent(new AlfredParam(), false));
        assertTrue(handler.matchEvent(param("FY hello"), false));
    }

    @Test void rendersDictionarySourcesButCopiesOnlyTranslatedValues() {
        when(translator.configured()).thenReturn(true);
        when(translator.translate("hello")).thenReturn(List.of(new YoudaoTranslationService.Translation("你好", null),
                new YoudaoTranslationService.Translation("您好", "hello")));
        var results = handler.doHandleAlfredEvent(param("fy hello"));
        assertEquals(List.of("你好", "hello: 您好"), results.stream().map(r -> r.getTitle()).toList());
        assertEquals("您好", results.get(1).getMsgNeedToCopy());
        assertEquals("translate.png", results.getFirst().getIcon());
    }

    private AlfredParam param(String input) { return new AlfredParam(input, List.of(input.split("\\s+"))); }
}
