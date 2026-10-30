package com.leon.workflow.controller;

import com.leon.workflow.service.TodoDigestService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodoDigestControllerTest {
    @Test
    void statsReturnsSentOrSkipped() {
        TodoDigestService service = mock(TodoDigestService.class);
        TodoDigestController controller = new TodoDigestController(service);
        when(service.sendStats("手动")).thenReturn(true);
        ResponseEntity<Map<String, Object>> sent = controller.sendStats();
        assertEquals(true, sent.getBody().get("success"));
        assertEquals("sent", sent.getBody().get("data"));

        when(service.sendStats("手动")).thenReturn(false);
        assertEquals("skipped", controller.sendStats().getBody().get("data"));
    }

    @Test
    void summaryReturnsSent() {
        TodoDigestService service = mock(TodoDigestService.class);
        TodoDigestController controller = new TodoDigestController(service);
        when(service.sendMidnightSummary()).thenReturn(true);
        assertEquals("sent", controller.sendSummary().getBody().get("data"));
    }
}
