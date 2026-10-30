package com.leon.workflow.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.leon.workflow.support.HttpRequestBody.read;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuBotServiceTest {
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpResponse<String> httpResponse;

    private FeishuBotService feishuBotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        feishuBotService = new FeishuBotService(appSettingService, objectMapper, httpClient);
    }

    @Test
    void skipWhenWebhookMissing() throws Exception {
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("");

        assertFalse(feishuBotService.sendCard("新增待办", "内容", "orange"));
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void sendCardPostsInteractiveHeader() throws Exception {
        stubSuccessWebhook("");

        assertTrue(feishuBotService.sendCard("新增待办", "写单元测试", "orange"));

        JsonNode body = readBody(captureRequest());
        assertEquals("interactive", body.path("msg_type").asText());
        assertEquals("新增待办", body.path("card").path("header").path("title").path("content").asText());
        assertEquals("orange", body.path("card").path("header").path("template").asText());
        assertEquals("写单元测试", body.path("card").path("body").path("elements").path(0).path("content").asText());
        assertEquals("POST", captureRequest().method());
    }

    @Test
    void sendCardSignsWhenSecretPresent() throws Exception {
        stubSuccessWebhook("demo");

        assertTrue(feishuBotService.sendCard("完成待办", "完成", "green"));

        JsonNode body = readBody(captureRequest());
        assertTrue(body.path("timestamp").asText().matches("\\d+"));
        assertFalse(body.path("sign").asText().isBlank());
    }

    @Test
    void sendCardReturnsFalseOnHttpError() throws Exception {
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/test");
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_SECRET)).thenReturn("");
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("{\"msg\":\"down\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertFalse(feishuBotService.sendCard("待办统计", "x", "blue"));
    }

    @Test
    void sendCardReturnsFalseOnApiError() throws Exception {
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/test");
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_SECRET)).thenReturn("");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"code\":19021,\"msg\":\"sign match fail\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertFalse(feishuBotService.sendCard("待办统计", "x", "blue"));
    }

    private void stubSuccessWebhook(String secret) throws Exception {
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/test");
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_SECRET)).thenReturn(secret);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"code\":0,\"msg\":\"success\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());
    }

    private HttpRequest captureRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        return captor.getValue();
    }

    private JsonNode readBody(HttpRequest request) throws Exception {
        return objectMapper.readTree(read(request));
    }

    @Test
    void interruptionPreservesCancellationSignal() throws Exception {
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_URL)).thenReturn("https://example.com/webhook");
        when(appSettingService.getValue(AppSettingService.FEISHU_WEBHOOK_SECRET)).thenReturn("");
        org.mockito.Mockito.doThrow(new InterruptedException()).when(httpClient).send(any(HttpRequest.class), any());
        try {
            assertFalse(feishuBotService.sendCard("title", "content", "blue"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
