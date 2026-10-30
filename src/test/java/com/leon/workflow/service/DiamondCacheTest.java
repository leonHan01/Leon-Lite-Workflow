package com.leon.workflow.service;

import com.leon.workflow.dao.entity.LeonDiamond;
import com.leon.workflow.dao.repository.LeonDiamondRepository;
import com.leon.workflow.util.diamond.AlfredDiamondService;
import com.leon.workflow.util.diamond.DiamondConfigParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiamondCacheTest {
    @Test
    void parseFailureKeepsEntireLastSuccessfulSnapshot() {
        LeonDiamondRepository repository = mock(LeonDiamondRepository.class);
        AlfredDiamondService cache = new AlfredDiamondService(repository, new DiamondConfigParser());
        when(repository.findAll()).thenReturn(List.of(diamond("kv-test", "名称:旧值"),
                diamond("WebUrlDiamond", "[{\"name\":\"旧网站 tag\",\"url\":\"https://old.example\"}]")));
        cache.init();
        var oldUrls = cache.getCommonUrlList();
        var oldTags = cache.getTags();
        var oldValues = cache.getAlfredKVList();
        when(repository.findAll()).thenReturn(List.of(diamond("kv-test", "invalid"),
                diamond("WebUrlDiamond", "[{\"name\":\"新网站 tag\",\"url\":\"https://new.example\"}]")));

        assertThrows(IllegalArgumentException.class, cache::init);
        assertSame(oldUrls, cache.getCommonUrlList());
        assertSame(oldTags, cache.getTags());
        assertSame(oldValues, cache.getAlfredKVList());

        when(repository.findAll()).thenReturn(List.of());
        cache.init();
        assertTrue(cache.getCommonUrlList().isEmpty());
        assertTrue(cache.getTags().isEmpty());
        assertTrue(cache.getAlfredKVList().isEmpty());
    }

    @Test
    void parserKeepsMenusDefaultsCommandsAndPinyinSearchTerms() {
        LeonDiamond diamond = diamond("kv-test", "===-二级菜单:工作-menu.png\n搜索:内容<https://example.com>[标签]{paste}");
        diamond.setDefaultIcon("default.png");
        diamond.setDefaultTags("默认");
        var value = new DiamondConfigParser().parseKeyValues(List.of(diamond)).getFirst();
        assertEquals("二级菜单:工作", value.getSecondDiamond());
        assertEquals("menu.png", value.getIcon());
        assertEquals("https://example.com", value.getUrl());
        assertEquals("内容", value.getValue());
        assertTrue(value.getTags().containsAll(List.of("标签", "默认", "搜索", "kv-test")));
        assertEquals(List.of("paste"), value.getCmds());
        assertTrue(value.getPinyin().contains("sousuo"));
    }

    private LeonDiamond diamond(String key, String value) {
        LeonDiamond diamond = new LeonDiamond();
        diamond.setDiamondKey(key);
        diamond.setDiamondValue(value);
        return diamond;
    }
}
