package com.leon.workflow.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.leon.workflow.support.HttpRequestBody.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YoudaoTranslationServiceTest {
    @Mock HttpClient client;
    @Mock HttpResponse<String> response;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC);
    private YoudaoTranslationService translator;

    @BeforeEach void setUp() {
        translator = new YoudaoTranslationService("test-key", "test-secret", new ObjectMapper(), client, clock, () -> "test-salt");
    }

    @Test void returnsPrimaryTranslationsBeforeValidDictionaryEntries() throws Exception {
        reply(200, """
                {"translation":["你好",null,""],"web":[
                  {"key":"hello","value":["您好","喂"]},
                  {"key":"broken"},{"value":["missing key"]},null]}
                """);
        assertEquals(List.of(new YoudaoTranslationService.Translation("你好", null),
                new YoudaoTranslationService.Translation("您好", "hello"),
                new YoudaoTranslationService.Translation("喂", "hello")), translator.translate("hello"));
    }

    @ParameterizedTest
    @CsvSource({"中文&,中文&,zh-CHS,en", "abcdefghijklmnopqrstuvwxyz,abcdefghij26qrstuvwxyz,en,zh-CHS"})
    void preservesLanguageSelectionAndV3Signature(String text, String signInput, String from, String to) throws Exception {
        reply(200, "{}");
        translator.translate(text);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        Map<String, String> form = Arrays.stream(read(request).split("&"))
                .map(pair -> pair.split("=", 2)).collect(Collectors.toMap(pair -> pair[0],
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals("POST", request.method());
        assertEquals("https://openapi.youdao.com/api", request.uri().toString());
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(text, form.get("q"));
        assertEquals(from, form.get("from"));
        assertEquals(to, form.get("to"));
        assertEquals("v3", form.get("signType"));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("test-key" + signInput + "test-salt1700000000test-secret").getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, form.get("sign"));
    }

    @Test void missingConfigurationOrInputDoesNotSendRequests() {
        assertTrue(translator.translate(" ").isEmpty());
        assertTrue(translator.translate(null).isEmpty());
        var unconfigured = new YoudaoTranslationService("", "", new ObjectMapper(), client, clock, () -> "salt");
        assertFalse(unconfigured.configured());
        assertTrue(unconfigured.translate("hello").isEmpty());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"500,{}", "200,not-json", "200,null"})
    void failuresReturnNoResults(int status, String body) throws Exception {
        reply(status, body);
        assertTrue(translator.translate("hello").isEmpty());
    }

    @Test void restoresInterruptionWithoutSendingFurtherRequests() throws Exception {
        doThrow(new InterruptedException()).when(client).send(any(HttpRequest.class), any());
        try {
            assertTrue(translator.translate("hello").isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private void reply(int status, String body) throws Exception {
        when(response.statusCode()).thenReturn(status);
        lenient().when(response.body()).thenReturn(body);
        doReturn(response).when(client).send(any(HttpRequest.class), any());
    }
}
