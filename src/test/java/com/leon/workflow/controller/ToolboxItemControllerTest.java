package com.leon.workflow.controller;

import com.leon.workflow.service.ToolboxItemService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolboxItemControllerTest {
    @Test
    void crudDelegatesToService() {
        ToolboxItemService service = mock(ToolboxItemService.class);
        ToolboxItemController controller = new ToolboxItemController(service);
        when(service.listItems()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.createItem(Map.of("url", "https://a.com"))).thenReturn(Map.of("id", 2L));
        when(service.updateItem(2L, Map.of("icon", "star"))).thenReturn(Map.of("icon", "star"));

        assertEquals(1, ((List<?>) controller.listItems().getBody().get("data")).size());
        assertEquals(2L, ((Map<?, ?>) controller.createItem(Map.of("url", "https://a.com")).getBody().get("data")).get("id"));
        assertEquals("star", ((Map<?, ?>) controller.updateItem(2L, Map.of("icon", "star")).getBody().get("data")).get("icon"));
        assertEquals("deleted", controller.deleteItem(2L).getBody().get("data"));
        verify(service).deleteItem(2L);
    }
}
