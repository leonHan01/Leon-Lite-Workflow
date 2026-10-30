package com.leon.workflow.controller;

import com.leon.workflow.service.LlmMessageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmMessageControllerTest {
    @Test
    void listAndCreateSession() {
        LlmMessageService service = mock(LlmMessageService.class);
        LlmMessageController controller = new LlmMessageController(service);
        when(service.listSessions()).thenReturn(List.of(Map.of("id", 1L)));
        when(service.createSession(Map.of())).thenReturn(Map.of("id", 2L, "title", "新对话"));

        assertEquals(1, ((List<?>) controller.listSessions().getBody().get("data")).size());
        assertEquals("新对话", ((Map<?, ?>) controller.createSession(null).getBody().get("data")).get("title"));
        controller.deleteSession(2L);
        verify(service).deleteSession(2L);
    }

    @Test
    void fullGetAndAddMessage() {
        LlmMessageService service = mock(LlmMessageService.class);
        LlmMessageController controller = new LlmMessageController(service);
        when(service.listSessionsFull()).thenReturn(List.of(Map.of("id", 1L, "messages", List.of())));
        when(service.getSession(1L)).thenReturn(Map.of("id", 1L, "title", "工作分析"));
        when(service.addMessage(1L, Map.of("role", "user", "content", "hi"))).thenReturn(Map.of("id", 9L));

        assertEquals(1, ((List<?>) controller.listSessionsFull().getBody().get("data")).size());
        assertEquals("工作分析", ((Map<?, ?>) controller.getSession(1L).getBody().get("data")).get("title"));
        assertEquals(9L, ((Map<?, ?>) controller.addMessage(1L, Map.of("role", "user", "content", "hi")).getBody().get("data")).get("id"));
        assertEquals("deleted", controller.deleteSession(1L).getBody().get("data"));
    }
}
