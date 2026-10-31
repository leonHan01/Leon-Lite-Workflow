package com.leon.workflow.handler.impl;

import com.leon.workflow.domain.bo.AlfredCommonUrl;
import com.leon.workflow.domain.bo.UrlTag;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonUrlHandlerTest {

    private final AlfredDiamondService diamond = mock(AlfredDiamondService.class);
    private final CommonUrlHandler handler = new CommonUrlHandler();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "alfredDiamondService", diamond);
    }

    @Test
    void listsTagsAndFiltersByTitlePinyinOrTag() {
        UrlTag work = new UrlTag();
        work.setTag("work");
        work.setCount(2);
        when(diamond.getTags()).thenReturn(List.of(work));

        AlfredCommonUrl docs = url("文档", "https://docs.example", List.of("java"), List.of("wendang"));
        AlfredCommonUrl duplicate = url("文档", "https://copy.example", List.of("java"), List.of("wendang"));
        AlfredCommonUrl other = url("Other", "https://other.example", List.of("life"), List.of("other"));
        when(diamond.getCommonUrlList()).thenReturn(List.of(docs, duplicate, other));

        List<AlfredResult> tags = handler.doHandleAlfredEvent(param("tags"));
        List<AlfredResult> found = handler.doHandleAlfredEvent(param("wen ja"));
        List<AlfredResult> missing = handler.doHandleAlfredEvent(param("wen life"));

        assertEquals("[work] 书签数:2", tags.getFirst().getTitle());
        assertEquals("runScript", tags.getFirst().getType());
        assertEquals("work", tags.getFirst().getAutoComplete());
        assertEquals(List.of("文档 🔗"), found.stream().map(AlfredResult::getTitle).toList());
        assertEquals("https://docs.example", found.getFirst().getUri());
        assertEquals("openurl", found.getFirst().getType());
        assertEquals("[java]", found.getFirst().getSubtitle());
        assertTrue(missing.isEmpty());
        assertTrue(handler.matchEvent(param("docs"), false));
    }

    private static AlfredCommonUrl url(String title, String link, List<String> tags, List<String> pinyin) {
        AlfredCommonUrl url = new AlfredCommonUrl();
        url.setTitle(title);
        url.setUrl(link);
        url.getTags().addAll(tags);
        url.getPinyinList().addAll(pinyin);
        return url;
    }

    private static AlfredParam param(String input) {
        return new AlfredParam(input, List.of(input.split("\\s+")));
    }
}
