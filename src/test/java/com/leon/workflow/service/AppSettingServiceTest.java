package com.leon.workflow.service;

import com.leon.workflow.dao.entity.AppSetting;
import com.leon.workflow.dao.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingServiceTest {
    @Mock
    private AppSettingRepository appSettingRepository;

    @InjectMocks
    private AppSettingService appSettingService;

    @Test
    void getSettingReturnsEmptyWhenMissing() {
        when(appSettingRepository.findById("FEISHU_WEBHOOK_URL")).thenReturn(Optional.empty());

        Map<String, Object> result = appSettingService.getSetting("FEISHU_WEBHOOK_URL");

        assertEquals("FEISHU_WEBHOOK_URL", result.get("key"));
        assertEquals("", result.get("value"));
        assertEquals("", appSettingService.getValue("FEISHU_WEBHOOK_URL"));
    }

    @Test
    void getSettingReturnsStoredValue() {
        AppSetting setting = new AppSetting();
        setting.setSettingKey("DEEPSEEK_API_KEY");
        setting.setSettingValue(" sk-test ");
        when(appSettingRepository.findById("DEEPSEEK_API_KEY")).thenReturn(Optional.of(setting));

        assertEquals(" sk-test ", appSettingService.getSetting("DEEPSEEK_API_KEY").get("value"));
        assertEquals("sk-test", appSettingService.getValue("DEEPSEEK_API_KEY"));
    }

    @Test
    void saveSettingCreatesAndUpdates() {
        when(appSettingRepository.findById("FEISHU_WEBHOOK_SECRET")).thenReturn(Optional.empty());
        when(appSettingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> saved = appSettingService.saveSetting("FEISHU_WEBHOOK_SECRET", Map.of("value", "  secret  "));

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertEquals("FEISHU_WEBHOOK_SECRET", captor.getValue().getSettingKey());
        assertEquals("secret", captor.getValue().getSettingValue());
        assertEquals("FEISHU_WEBHOOK_SECRET", saved.get("key"));
    }

    @Test
    void blankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> appSettingService.getSetting("  "));
    }
}
