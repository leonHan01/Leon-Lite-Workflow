package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.bo.AlfredKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KVHandlerTest {

    private final AlfredDiamondService diamond = mock(AlfredDiamondService.class);
    private final KVHandler handler = new KVHandler(diamond);

    @Test
    void filtersByTagOrPinyinAndKeepsEveryTerm() {
        AlfredKV alpha = kv("Alpha", "hello", null, List.of("work"), List.of("alpha"));
        AlfredKV beta = kv("Beta", "", "https://beta.example", List.of("work"), List.of("beta"));
        AlfredKV gamma = kv("Gamma", "secret", null, List.of("other"), List.of("gamma"));
        gamma.getCmds().add("paste");
        gamma.getCmds().add("star.png");
        gamma.setIcon("old.png");
        AlfredKV untagged = kv("Bare", "x", null, List.of(), List.of("bare"));
        when(diamond.getAlfredKVList()).thenReturn(List.of(alpha, beta, gamma, untagged));

        List<AlfredResult> work = handler.doHandleAlfredEvent(param("wo"));
        List<AlfredResult> pasted = handler.doHandleAlfredEvent(param("gam"));
        List<AlfredResult> none = handler.doHandleAlfredEvent(param("work other"));

        assertEquals(List.of("Alpha:hello", "Beta"), work.stream().map(AlfredResult::getTitle).toList());
        assertEquals("hello", work.getFirst().getMsgNeedToCopy());
        assertEquals("clipboard", work.getFirst().getType());
        assertEquals("[work]", work.getFirst().getSubtitle());
        assertEquals("https://beta.example", work.get(1).getUri());
        assertEquals("openurl", work.get(1).getType());
        assertEquals("Gamma:secret", pasted.getFirst().getTitle());
        assertEquals("secret", pasted.getFirst().getMsgNeedToCopy());
        assertEquals("clipboardAndPaste", pasted.getFirst().getType());
        assertEquals("star.png", pasted.getFirst().getIcon());
        assertTrue(none.isEmpty());
        assertTrue(handler.matchEvent(param("wo"), false));
    }

    private static AlfredKV kv(String key, String value, String url, List<String> tags, List<String> pinyin) {
        AlfredKV item = new AlfredKV();
        item.setKey(key);
        item.setValue(value);
        item.setUrl(url);
        item.getTags().addAll(tags);
        item.setPinyin(pinyin);
        return item;
    }

    private static AlfredParam param(String input) {
        return new AlfredParam(input, List.of(input.split("\\s+")));
    }
}
