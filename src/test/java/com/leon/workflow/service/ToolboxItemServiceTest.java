package com.leon.workflow.service;

import com.leon.workflow.dao.entity.ToolboxItem;
import com.leon.workflow.dao.repository.ToolboxItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolboxItemServiceTest {
    @Mock
    private ToolboxItemRepository toolboxItemRepository;

    @InjectMocks
    private ToolboxItemService toolboxItemService;

    @Test
    void createItemAddsHttpsAndIcon() {
        when(toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(toolboxItemRepository.save(any())).thenAnswer(invocation -> {
            ToolboxItem item = invocation.getArgument(0);
            item.setId(1L);
            return item;
        });

        Map<String, Object> created = toolboxItemService.createItem(Map.of(
                "name", "飞书",
                "url", "www.feishu.cn",
                "icon", "message"
        ));

        assertEquals("https://www.feishu.cn", created.get("url"));
        assertEquals("message", created.get("icon"));
        assertEquals(0, created.get("sortOrder"));
    }

    @Test
    void createItemReturnsExistingByUrl() {
        ToolboxItem existing = new ToolboxItem();
        existing.setId(7L);
        existing.setName("旧");
        existing.setUrl("https://www.feishu.cn");
        existing.setIcon("star");
        existing.setSortOrder(0);
        when(toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(existing));

        Map<String, Object> created = toolboxItemService.createItem(Map.of(
                "name", "飞书",
                "url", "https://www.feishu.cn"
        ));

        assertEquals(7L, created.get("id"));
        assertEquals("旧", created.get("name"));
    }

    @Test
    void updateItemChangesIcon() {
        ToolboxItem item = new ToolboxItem();
        item.setId(1L);
        item.setName("飞书");
        item.setUrl("https://www.feishu.cn");
        item.setDeleted(false);
        when(toolboxItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(toolboxItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = toolboxItemService.updateItem(1L, Map.of("icon", "rocket"));

        assertEquals("rocket", updated.get("icon"));
    }

    @Test
    void deleteItemSoftDeletes() {
        ToolboxItem item = new ToolboxItem();
        item.setId(1L);
        item.setDeleted(false);
        when(toolboxItemRepository.findById(1L)).thenReturn(Optional.of(item));

        toolboxItemService.deleteItem(1L);

        assertTrue(item.getDeleted());
        verify(toolboxItemRepository).save(item);
    }

    @Test
    void listItemsMapsRows() {
        ToolboxItem item = new ToolboxItem();
        item.setId(1L);
        item.setName("飞书");
        item.setUrl("https://www.feishu.cn");
        item.setIcon("message");
        when(toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of(item));

        List<Map<String, Object>> list = toolboxItemService.listItems();
        assertEquals(1, list.size());
        assertEquals("飞书", list.get(0).get("name"));
        assertEquals("message", list.get(0).get("icon"));
    }

    @Test
    void missingItemThrows() {
        when(toolboxItemRepository.findById(8L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> toolboxItemService.deleteItem(8L));
    }

    @Test
    void missingNameRejected() {
        when(toolboxItemRepository.findByDeletedFalseOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> toolboxItemService.createItem(Map.of("url", "https://a.com")));
    }
}
