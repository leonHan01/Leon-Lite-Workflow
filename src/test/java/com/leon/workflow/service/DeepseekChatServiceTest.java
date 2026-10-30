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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeepseekChatServiceTest {
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpResponse<String> httpResponse;

    private DeepseekChatService deepseekChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        deepseekChatService = new DeepseekChatService(appSettingService, objectMapper, httpClient);
    }

    @Test
    void completeRequiresApiKey() {
        when(appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("");
        assertThrows(IllegalStateException.class, () -> deepseekChatService.complete("sys", "user"));
    }

    @Test
    void completeReadsAssistantContent() throws Exception {
        when(appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("sk-test");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"TL;DR：先补测试\"}}]}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        String content = deepseekChatService.complete("系统", "请总结待办");

        assertEquals("TL;DR：先补测试", content);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        assertTrue(captor.getValue().headers().firstValue("Authorization").orElse("").contains("sk-test"));
        JsonNode body = objectMapper.readTree(read(captor.getValue()));
        assertEquals("deepseek-v4-flash", body.path("model").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("系统", body.path("messages").path(0).path("content").asText());
    }

    @Test
    void completeThrowsOnEmptyContent() throws Exception {
        when(appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("sk-test");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> deepseekChatService.complete("s", "u"));
        assertTrue(error.getMessage().contains("空内容"));
    }

    @Test
    void completeThrowsOnHttpError() throws Exception {
        when(appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("sk-test");
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{\"error\":{\"message\":\"invalid key\"}}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> deepseekChatService.complete("s", "u"));
        assertTrue(error.getMessage().contains("invalid key"));
    }

    @Test
    void interruptionPreservesCancellationSignal() throws Exception {
        when(appSettingService.getValue(AppSettingService.DEEPSEEK_API_KEY)).thenReturn("test-key");
        org.mockito.Mockito.doThrow(new InterruptedException()).when(httpClient).send(any(HttpRequest.class), any());
        try {
            assertThrows(IllegalStateException.class, () -> deepseekChatService.complete("system", "user"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
