package com.leon.workflow.controller;

import com.leon.workflow.service.AppSettingService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppSettingControllerTest {
    @Test
    void getAndSaveDelegate() {
        AppSettingService service = mock(AppSettingService.class);
        AppSettingController controller = new AppSettingController(service);
        when(service.getSetting("DEEPSEEK_API_KEY")).thenReturn(Map.of("key", "DEEPSEEK_API_KEY", "value", "sk"));
        when(service.saveSetting("DEEPSEEK_API_KEY", Map.of("value", "sk-2")))
                .thenReturn(Map.of("key", "DEEPSEEK_API_KEY", "value", "sk-2"));

        assertEquals("sk", ((Map<?, ?>) controller.getSetting("DEEPSEEK_API_KEY").getBody().get("data")).get("value"));
        assertEquals("sk-2", ((Map<?, ?>) controller.saveSetting("DEEPSEEK_API_KEY", Map.of("value", "sk-2")).getBody().get("data")).get("value"));
    }
}
