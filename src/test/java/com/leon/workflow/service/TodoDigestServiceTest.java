package com.leon.workflow.service;

import com.leon.workflow.dao.entity.TodoItem;
import com.leon.workflow.dao.repository.TodoItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoDigestServiceTest {
    @Mock
    private TodoItemRepository todoItemRepository;
    @Mock
    private FeishuBotService feishuBotService;
    @Mock
    private DeepseekChatService deepseekChatService;

    private TodoDigestService todoDigestService;

    @BeforeEach
    void setUp() {
        todoDigestService = new TodoDigestService(todoItemRepository, feishuBotService, deepseekChatService);
    }

    @Test
    void sendStatsIncludesOpenAndDoneItems() {
        when(todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()).thenReturn(List.of(
                todo("写单测", false),
                todo("已完成任务", true),
                todo("  ", false)
        ));
        when(feishuBotService.sendCard(anyString(), anyString(), eq("blue"))).thenReturn(true);

        assertTrue(todoDigestService.sendStats("10:00"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(feishuBotService).sendCard(title.capture(), markdown.capture(), eq("blue"));
        assertEquals("待办统计 10:00", title.getValue());
        assertTrue(markdown.getValue().contains("全部：**3**"));
        assertTrue(markdown.getValue().contains("未完成：**2**"));
        assertTrue(markdown.getValue().contains("写单测"));
        assertTrue(markdown.getValue().contains("已完成任务"));
        assertTrue(markdown.getValue().contains("(空)"));
    }

    @Test
    void sendMidnightSummaryUsesLlmAndNormalizesTldr() {
        when(todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()).thenReturn(List.of(todo("写单测", false)));
        when(deepseekChatService.complete(anyString(), anyString())).thenReturn("今天先把测试补齐。\n\n详情");
        when(feishuBotService.sendCard(eq("待办日结"), anyString(), eq("purple"))).thenReturn(true);

        assertTrue(todoDigestService.sendMidnightSummary());

        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(feishuBotService).sendCard(eq("待办日结"), markdown.capture(), eq("purple"));
        assertTrue(markdown.getValue().startsWith("TL;DR："));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(deepseekChatService).complete(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains("【未完成待办】"));
        assertTrue(prompt.getValue().contains("写单测"));
    }

    @Test
    void sendMidnightSummaryFallsBackWhenLlmFails() {
        when(todoItemRepository.findByDeletedFalseOrderByDoneAscSortOrderAscIdAsc()).thenReturn(List.of());
        when(deepseekChatService.complete(anyString(), anyString())).thenThrow(new IllegalStateException("未配置 DeepSeek API Key"));
        when(feishuBotService.sendCard(eq("待办日结"), anyString(), eq("purple"))).thenReturn(true);

        assertTrue(todoDigestService.sendMidnightSummary());

        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(feishuBotService).sendCard(eq("待办日结"), markdown.capture(), eq("purple"));
        assertTrue(markdown.getValue().contains("LLM 总结失败"));
        assertTrue(markdown.getValue().contains("（无）"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TL;DR：", "tl;dr: ", "TL;DR"})
    void summaryPrefixCannotBypassFortyCharacterLimit(String prefix) {
        when(deepseekChatService.complete(anyString(), anyString())).thenReturn(prefix + "长".repeat(60) + "\n\n保留正文");
        todoDigestService.sendMidnightSummary();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(feishuBotService).sendCard(eq("待办日结"), text.capture(), eq("purple"));
        assertEquals("TL;DR：" + "长".repeat(40) + "\n\n保留正文", text.getValue());
    }

    @Test
    void cancellationDoesNotAttemptFallbackNotification() {
        when(deepseekChatService.complete(anyString(), anyString())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancelled");
        });
        try {
            org.junit.jupiter.api.Assertions.assertFalse(todoDigestService.sendMidnightSummary());
            org.mockito.Mockito.verifyNoInteractions(feishuBotService);
        } finally { Thread.interrupted(); }
    }

    private TodoItem todo(String content, boolean done) {
        TodoItem item = new TodoItem();
        item.setContent(content);
        item.setDone(done);
        return item;
    }
}
