package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.service.SimpleKVCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultHandlerTest {

    private final SimpleKVCacheService cache = mock(SimpleKVCacheService.class);
    private final DefaultHandler handler = new DefaultHandler(cache);

    @Test
    void showsHomeItemsAndFallsBackWhenNoneAreMarked() {
        SimpleKV link = new SimpleKV();
        link.setKvKey("主页");
        link.setKvValue("https://example.com");
        link.setKvType("url");
        link.setKvIcon("home.png");
        when(cache.getHomeItems()).thenReturn(List.of(link));

        List<AlfredResult> results = handler.doHandleAlfredEvent(new AlfredParam());

        assertEquals("主页", results.getFirst().getTitle());
        assertEquals("https://example.com", results.getFirst().getUri());
        assertEquals("openurl", results.getFirst().getType());
        assertEquals("home.png", results.getFirst().getIcon());
        assertFalse(handler.matchEvent(new AlfredParam("anything", List.of("anything")), false));

        when(cache.getHomeItems()).thenReturn(List.of());
        assertEquals("你好 from Leon Localhost", handler.doHandleAlfredEvent(new AlfredParam()).getFirst().getTitle());
    }
}
