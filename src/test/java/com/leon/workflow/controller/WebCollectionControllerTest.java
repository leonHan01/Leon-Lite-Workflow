package com.leon.workflow.controller;

import com.leon.workflow.service.WebCollectionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebCollectionControllerTest {
    @Test
    void collectionAndItemRoutesDelegate() {
        WebCollectionService service = mock(WebCollectionService.class);
        WebCollectionController controller = new WebCollectionController(service);
        when(service.listCollections()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.listCollectionDetails()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.getCollection(2L)).thenReturn(Map.of("id", 2L));
        when(service.createCollection(Map.of("name", "科技"))).thenReturn(Map.of("id", 2L));
        when(service.updateCollection(2L, Map.of("name", "科技"))).thenReturn(Map.of("id", 2L));
        when(service.createGroup(2L, Map.of("name", "文档"))).thenReturn(Map.of("id", 3L));
        when(service.updateGroup(2L, 3L, Map.of("name", "文档"))).thenReturn(Map.of("id", 3L));
        when(service.createItem(2L, Map.of("url", "https://a.com"))).thenReturn(Map.of("id", 9L));
        when(service.updateItem(2L, 9L, Map.of("name", "飞书"))).thenReturn(Map.of("id", 9L));

        assertEquals(1, ((List<?>) controller.listCollections().getBody().get("data")).size());
        assertEquals(1, ((List<?>) controller.listCollectionDetails().getBody().get("data")).size());
        assertEquals(2L, ((Map<?, ?>) controller.getCollection(2L).getBody().get("data")).get("id"));
        assertEquals(2L, ((Map<?, ?>) controller.createCollection(Map.of("name", "科技")).getBody().get("data")).get("id"));
        assertEquals(2L, ((Map<?, ?>) controller.updateCollection(2L, Map.of("name", "科技")).getBody().get("data")).get("id"));
        assertEquals(3L, ((Map<?, ?>) controller.createGroup(2L, Map.of("name", "文档")).getBody().get("data")).get("id"));
        assertEquals(3L, ((Map<?, ?>) controller.updateGroup(2L, 3L, Map.of("name", "文档")).getBody().get("data")).get("id"));
        assertEquals(9L, ((Map<?, ?>) controller.createItem(2L, Map.of("url", "https://a.com")).getBody().get("data")).get("id"));
        assertEquals(9L, ((Map<?, ?>) controller.updateItem(2L, 9L, Map.of("name", "飞书")).getBody().get("data")).get("id"));
        assertEquals("deleted", controller.deleteCollection(2L).getBody().get("data"));
        assertEquals("saved", controller.reorderCollections(Map.of("collectionIds", List.of(2L))).getBody().get("data"));
        assertEquals("saved", controller.reorderGroups(2L, Map.of("groupIds", List.of(3L))).getBody().get("data"));
        assertEquals("saved", controller.reorderItems(2L, 3L, Map.of("itemIds", List.of(9L))).getBody().get("data"));
        assertEquals("deleted", controller.deleteGroup(2L, 3L).getBody().get("data"));
        assertEquals("deleted", controller.deleteItem(2L, 9L).getBody().get("data"));
        verify(service).deleteCollection(2L);
        verify(service).reorderCollections(Map.of("collectionIds", List.of(2L)));
        verify(service).reorderGroups(2L, Map.of("groupIds", List.of(3L)));
        verify(service).reorderItems(2L, 3L, Map.of("itemIds", List.of(9L)));
        verify(service).deleteGroup(2L, 3L);
        verify(service).deleteItem(2L, 9L);
    }
}
