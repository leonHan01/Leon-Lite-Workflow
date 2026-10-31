package com.leon.workflow.handler.impl;

import com.leon.workflow.dao.entity.ToolboxItem;
import com.leon.workflow.dao.entity.WebCollection;
import com.leon.workflow.dao.entity.WebCollectionGroup;
import com.leon.workflow.dao.entity.WebCollectionItem;
import com.leon.workflow.dao.repository.ToolboxItemRepository;
import com.leon.workflow.dao.repository.WebCollectionGroupRepository;
import com.leon.workflow.dao.repository.WebCollectionItemRepository;
import com.leon.workflow.dao.repository.WebCollectionRepository;
import com.leon.workflow.domain.dto.AlfredParam;
import com.leon.workflow.domain.dto.AlfredResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginLinksHandlerTest {

    private final WebCollectionRepository collections = mock(WebCollectionRepository.class);
    private final WebCollectionGroupRepository groups = mock(WebCollectionGroupRepository.class);
    private final WebCollectionItemRepository items = mock(WebCollectionItemRepository.class);
    private final ToolboxItemRepository tools = mock(ToolboxItemRepository.class);
    private final PluginLinksHandler handler = new PluginLinksHandler(collections, groups, items, tools);

    @Test
    void matchesEveryTermAndSkipsUrlsThatAreNotWebPages() {
        ToolboxItem tool = tool("蓝图工具", "https://tools.example.org");
        ToolboxItem script = tool("蓝图不安全", "javascript://example.org");
        WebCollection collection = new WebCollection();
        collection.setId(1L);
        collection.setName("项目蓝图");
        WebCollectionGroup group = new WebCollectionGroup();
        group.setId(2L);
        group.setCollectionId(1L);
        group.setName("资料");
        WebCollectionItem item = item(1L, 2L, "工程文档", "https://docs.example.org/guide", "部署手册");
        WebCollectionItem wrongGroup = item(9L, 2L, "错组", "https://wrong.example", "部署");
        when(tools.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(tool, script));
        when(collections.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(collection));
        when(groups.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(anyList())).thenReturn(List.of(group));
        when(items.findByCollectionIdInAndDeletedFalseOrderBySortOrderAscIdAsc(anyList())).thenReturn(List.of(item, wrongGroup));

        List<AlfredResult> results = handler.doHandleAlfredEvent(param("蓝图 部署"));

        assertEquals(List.of("工程文档"), results.stream().map(AlfredResult::getTitle).toList());
        assertEquals("https://docs.example.org/guide", results.getFirst().getUri());
        assertEquals("https://docs.example.org/guide", results.getFirst().getMsgNeedToCopy());
        assertEquals("openurl", results.getFirst().getType());
        assertEquals("link.png", results.getFirst().getIcon());
        assertTrue(results.getFirst().getSubtitle().contains("项目蓝图"));
        assertTrue(results.getFirst().getSubtitle().contains("资料"));

        List<AlfredResult> toolOnly = handler.doHandleAlfredEvent(param("TOOLS.EXAMPLE"));
        assertEquals("蓝图工具", toolOnly.getFirst().getTitle());
        assertEquals("工具 · https://tools.example.org", toolOnly.getFirst().getSubtitle());
    }

    @Test
    void emptyInputDoesNotMatch() {
        assertFalse(handler.matchEvent(new AlfredParam("", List.of()), false));
        assertTrue(handler.matchEvent(param("文档"), false));
        assertEquals(49, handler.getPriority());
    }

    private static ToolboxItem tool(String name, String url) {
        ToolboxItem tool = new ToolboxItem();
        tool.setName(name);
        tool.setUrl(url);
        return tool;
    }

    private static WebCollectionItem item(Long collectionId, Long groupId, String name, String url, String note) {
        WebCollectionItem item = new WebCollectionItem();
        item.setCollectionId(collectionId);
        item.setGroupId(groupId);
        item.setName(name);
        item.setUrl(url);
        item.setNote(note);
        return item;
    }

    private static AlfredParam param(String input) {
        return new AlfredParam(input, List.of(input.split("\\s+")));
    }
}
