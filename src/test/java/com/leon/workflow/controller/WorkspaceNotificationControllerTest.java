package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import com.leon.workflow.service.AppSettingService;
import com.leon.workflow.service.FeishuBotService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceNotificationControllerTest {
    @Test void sendsOnlyToValidatedFeishuWebhook() {
        var auth=mock(ApiTokenInterceptor.class); var service=mock(FeishuBotService.class); var settings=mock(AppSettingService.class);
        var controller=new WorkspaceNotificationController(auth,service,settings);
        var input=new WorkspaceNotificationController.Notification("新增便签", "示例");
        when(settings.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("http://127.0.0.1/", "https://open.feishu.cn/open-apis/bot/v2/hook/test-id");
        assertEquals(400,controller.send(input,mock(HttpServletRequest.class)).getStatusCode().value());
        verifyNoInteractions(service);
        when(service.sendCardToWebhook("新增便签","示例","blue","https://open.feishu.cn/open-apis/bot/v2/hook/test-id")).thenReturn(true);
        assertEquals(200,controller.send(input,mock(HttpServletRequest.class)).getStatusCode().value());
        verify(service).sendCardToWebhook("新增便签","示例","blue","https://open.feishu.cn/open-apis/bot/v2/hook/test-id");
    }
}
