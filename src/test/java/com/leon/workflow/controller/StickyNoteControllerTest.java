package com.leon.workflow.controller;

import com.leon.workflow.service.StickyNoteService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StickyNoteControllerTest {
    @Test
    void crudDelegatesToService() {
        StickyNoteService service = mock(StickyNoteService.class);
        StickyNoteController controller = new StickyNoteController(service);
        when(service.listNotes()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.createNote(Map.of("content", "hi"))).thenReturn(Map.of("id", 2L));

        assertEquals(1, ((List<?>) controller.listNotes().getBody().get("data")).size());
        assertEquals(2L, ((Map<?, ?>) controller.createNote(Map.of("content", "hi")).getBody().get("data")).get("id"));
        when(service.updateNote(2L, Map.of("content", "新"))).thenReturn(Map.of("content", "新"));
        controller.deleteNote(2L);
        verify(service).deleteNote(2L);
        assertEquals("deleted", controller.deleteNote(2L).getBody().get("data"));
        assertEquals("新", ((Map<?, ?>) controller.updateNote(2L, Map.of("content", "新")).getBody().get("data")).get("content"));
    }
}
