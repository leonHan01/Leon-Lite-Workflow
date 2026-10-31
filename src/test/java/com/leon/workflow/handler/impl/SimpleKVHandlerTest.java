package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.service.SimpleKVCacheService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleKVHandlerTest {

    private final SimpleKVCacheService cache = mock(SimpleKVCacheService.class);
    private final SimpleKVHandler handler = new SimpleKVHandler(cache);

    @Test
    void pagesAllItemsAndCopiesThePageCommand() {
        List<SimpleKV> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            items.add(text("k" + i, "v" + i));
        }
        when(cache.getAll()).thenReturn(items);
        when(cache.getTotalPages(9)).thenReturn(2);
        when(cache.getAllPaged(1, 9)).thenReturn(items.subList(0, 9));
        when(cache.getAllPaged(2, 9)).thenReturn(items.subList(9, 10));

        List<AlfredResult> first = handler.doHandleAlfredEvent(param("ALL"));
        List<AlfredResult> second = handler.doHandleAlfredEvent(param("all2"));

        assertEquals("全部 KV（第 1 / 2 页，共 10 条）", first.getFirst().getTitle());
        assertEquals("all1", first.getFirst().getMsgNeedToCopy());
        assertEquals(10, first.size());
        assertEquals("k1", first.get(1).getTitle());
        assertEquals("k10", second.get(1).getTitle());
        assertEquals("v10", second.get(1).getMsgNeedToCopy());
    }

    @Test
    void putsPinnedMatchesFirstAndOpensUrlValues() {
        SimpleKV plain = text("普通", "文字");
        SimpleKV pinned = url("置顶链接", "https://example.com");
        pinned.setKvAction("置顶");
        pinned.setKvIcon("red.png");
        when(cache.fuzzyMatch("链接")).thenReturn(new ArrayList<>(List.of(plain, pinned)));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param("链接"));

        assertEquals(List.of("置顶链接", "普通"), results.stream().map(AlfredResult::getTitle).toList());
        assertEquals("https://example.com", results.getFirst().getUri());
        assertEquals("openurl", results.getFirst().getType());
        assertEquals("red.png", results.getFirst().getIcon());
        assertEquals("clipboard", results.get(1).getType());
        assertEquals("文字", results.get(1).getMsgNeedToCopy());
    }

    @Test
    void blankInputDoesNotMatchAndBlankValueCopiesTheKey() {
        assertFalse(handler.matchEvent(param("  "), false));
        assertTrue(handler.doHandleAlfredEvent(param("  ")).isEmpty());

        SimpleKV blank = text("只有键", " ");
        when(cache.fuzzyMatch("只有键")).thenReturn(new ArrayList<>(List.of(blank)));

        assertEquals("只有键", handler.doHandleAlfredEvent(param("只有键")).getFirst().getMsgNeedToCopy());
        assertTrue(handler.matchEvent(param("all 2"), false));
    }

    private static SimpleKV text(String key, String value) {
        SimpleKV kv = new SimpleKV();
        kv.setKvKey(key);
        kv.setKvValue(value);
        kv.setKvType("text");
        return kv;
    }

    private static SimpleKV url(String key, String value) {
        SimpleKV kv = text(key, value);
        kv.setKvType("url");
        return kv;
    }

    private static AlfredParam param(String input) {
        return new AlfredParam(input, List.of(input.trim().split("\\s+")));
    }
}
