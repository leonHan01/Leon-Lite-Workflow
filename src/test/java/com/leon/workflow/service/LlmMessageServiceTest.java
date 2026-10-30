package com.leon.workflow.service;

import com.leon.workflow.dao.entity.LlmMessage;
import com.leon.workflow.dao.entity.LlmSession;
import com.leon.workflow.dao.repository.LlmMessageRepository;
import com.leon.workflow.dao.repository.LlmSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmMessageServiceTest {
    @Mock
    private LlmMessageRepository llmMessageRepository;
    @Mock
    private LlmSessionRepository llmSessionRepository;

    @InjectMocks
    private LlmMessageService llmMessageService;

    @Test
    void createSessionDefaultsTitle() {
        when(llmSessionRepository.save(any())).thenAnswer(invocation -> {
            LlmSession session = invocation.getArgument(0);
            session.setId(1L);
            return session;
        });

        Map<String, Object> created = llmMessageService.createSession(Map.of());

        assertEquals("新对话", created.get("title"));
        assertEquals(0, created.get("messageCount"));
    }

    @Test
    void addUserMessageAutoTitlesNewSession() {
        LlmSession session = new LlmSession();
        session.setId(2L);
        session.setTitle("新对话");
        session.setDeleted(false);
        when(llmSessionRepository.findById(2L)).thenReturn(Optional.of(session));
        when(llmMessageRepository.save(any())).thenAnswer(invocation -> {
            LlmMessage message = invocation.getArgument(0);
            message.setId(11L);
            return message;
        });

        Map<String, Object> saved = llmMessageService.addMessage(2L, Map.of(
                "role", "user",
                "content", "帮我分析一下今天的工作安排是否合理"
        ));

        assertEquals("帮我分析一下今天的工作安排是否合理", session.getTitle());
        assertEquals("user", saved.get("role"));
        verify(llmSessionRepository).save(session);
    }

    @Test
    void addMessageRejectsInvalidRole() {
        LlmSession session = new LlmSession();
        session.setId(2L);
        session.setDeleted(false);
        when(llmSessionRepository.findById(2L)).thenReturn(Optional.of(session));

        assertThrows(IllegalArgumentException.class, () -> llmMessageService.addMessage(2L, Map.of("role", "system", "content", "hi")));
    }

    @Test
    void deleteSessionSoftDeletesMessages() {
        LlmSession session = new LlmSession();
        session.setId(3L);
        session.setDeleted(false);
        LlmMessage message = new LlmMessage();
        message.setId(1L);
        message.setDeleted(false);
        when(llmSessionRepository.findById(3L)).thenReturn(Optional.of(session));
        when(llmMessageRepository.findBySessionIdAndDeletedFalseOrderByIdAsc(3L)).thenReturn(new ArrayList<>(List.of(message)));

        llmMessageService.deleteSession(3L);

        assertTrue(session.getDeleted());
        assertTrue(message.getDeleted());
    }

    @Test
    void migrateOrphansCreatesHistorySession() {
        LlmMessage orphan = new LlmMessage();
        orphan.setId(8L);
        orphan.setSessionId(null);
        when(llmMessageRepository.findBySessionIdIsNullAndDeletedFalseOrderByIdAsc()).thenReturn(List.of(orphan));
        when(llmSessionRepository.save(any())).thenAnswer(invocation -> {
            LlmSession session = invocation.getArgument(0);
            session.setId(99L);
            return session;
        });

        llmMessageService.migrateOrphans();

        assertEquals(99L, orphan.getSessionId());
        verify(llmMessageRepository).save(orphan);
    }

    @Test
    void getSessionIncludesMessages() {
        LlmSession session = new LlmSession();
        session.setId(4L);
        session.setTitle("工作分析");
        session.setDeleted(false);
        LlmMessage message = new LlmMessage();
        message.setId(1L);
        message.setSessionId(4L);
        message.setRole("assistant");
        message.setContent("TL;DR：先做测试");
        when(llmSessionRepository.findById(4L)).thenReturn(Optional.of(session));
        when(llmMessageRepository.findBySessionIdAndDeletedFalseOrderByIdAsc(4L)).thenReturn(List.of(message));

        Map<String, Object> result = llmMessageService.getSession(4L);

        assertEquals("工作分析", result.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, messages.size());
        assertEquals("TL;DR：先做测试", messages.get(0).get("content"));
        verify(llmMessageRepository).findBySessionIdAndDeletedFalseOrderByIdAsc(4L);
    }

    @Test
    void listSessionsMigratesOrphansThenReturns() {
        when(llmMessageRepository.findBySessionIdIsNullAndDeletedFalseOrderByIdAsc()).thenReturn(List.of());
        LlmSession session = new LlmSession();
        session.setId(1L);
        session.setTitle("历史对话");
        session.setDeleted(false);
        when(llmSessionRepository.findByDeletedFalseOrderByUpdateTimeDescIdDesc()).thenReturn(List.of(session));

        List<Map<String, Object>> list = llmMessageService.listSessions();
        assertEquals(1, list.size());
        assertEquals("历史对话", list.get(0).get("title"));
    }

    @Test
    void addMessageRejectsBlankContent() {
        LlmSession session = new LlmSession();
        session.setId(2L);
        session.setDeleted(false);
        when(llmSessionRepository.findById(2L)).thenReturn(Optional.of(session));
        assertThrows(IllegalArgumentException.class, () -> llmMessageService.addMessage(2L, Map.of("role", "user", "content", "  ")));
    }

    @Test
    void missingSessionThrows() {
        when(llmSessionRepository.findById(8L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> llmMessageService.getSession(8L));
    }

    @Test
    void createSessionUsesTitle() {
        when(llmSessionRepository.save(any())).thenAnswer(invocation -> {
            LlmSession session = invocation.getArgument(0);
            session.setId(5L);
            return session;
        });

        Map<String, Object> created = llmMessageService.createSession(Map.of("title", "工作分析"));
        assertEquals("工作分析", created.get("title"));
    }

    @Test
    void assistantMessageDoesNotRetitle() {
        LlmSession session = new LlmSession();
        session.setId(2L);
        session.setTitle("新对话");
        session.setDeleted(false);
        when(llmSessionRepository.findById(2L)).thenReturn(Optional.of(session));
        when(llmMessageRepository.save(any())).thenAnswer(invocation -> {
            LlmMessage message = invocation.getArgument(0);
            message.setId(12L);
            return message;
        });

        llmMessageService.addMessage(2L, Map.of("role", "assistant", "content", "你好"));
        assertEquals("新对话", session.getTitle());
    }

    @Test
    void firstUserMessageClipsTitleToForty() {
        LlmSession session = new LlmSession();
        session.setId(2L);
        session.setTitle("新对话");
        session.setDeleted(false);
        when(llmSessionRepository.findById(2L)).thenReturn(Optional.of(session));
        when(llmMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String content = "一".repeat(50);
        llmMessageService.addMessage(2L, Map.of("role", "user", "content", content));
        assertEquals(40, session.getTitle().length());
    }

    @Test
    void listSessionsFullIncludesMessages() {
        when(llmMessageRepository.findBySessionIdIsNullAndDeletedFalseOrderByIdAsc()).thenReturn(List.of());
        LlmSession session = new LlmSession();
        session.setId(4L);
        session.setTitle("工作分析");
        session.setDeleted(false);
        LlmMessage message = new LlmMessage();
        message.setId(1L);
        message.setSessionId(4L);
        message.setRole("user");
        message.setContent("今天做什么");
        when(llmSessionRepository.findByDeletedFalseOrderByUpdateTimeDescIdDesc()).thenReturn(List.of(session));
        when(llmMessageRepository.findBySessionIdInAndDeletedFalseOrderByIdAsc(List.of(4L))).thenReturn(List.of(message));

        List<Map<String, Object>> list = llmMessageService.listSessionsFull();
        assertEquals(1, list.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) list.get(0).get("messages");
        assertEquals("今天做什么", messages.get(0).get("content"));
    }

    @Test
    void deletedSessionIsMissing() {
        LlmSession session = new LlmSession();
        session.setId(8L);
        session.setDeleted(true);
        when(llmSessionRepository.findById(8L)).thenReturn(Optional.of(session));
        assertThrows(IllegalArgumentException.class, () -> llmMessageService.deleteSession(8L));
    }
    @Test
    void fullSessionListBatchesMessagesAndKeepsSessionOrdering() {
        LlmSession first = new LlmSession(); first.setId(2L); first.setTitle("first");
        LlmSession second = new LlmSession(); second.setId(1L); second.setTitle("second");
        LlmMessage message = new LlmMessage(); message.setId(9L); message.setSessionId(1L); message.setContent("content");
        when(llmSessionRepository.findByDeletedFalseOrderByUpdateTimeDescIdDesc()).thenReturn(List.of(first, second));
        when(llmMessageRepository.findBySessionIdInAndDeletedFalseOrderByIdAsc(List.of(2L, 1L)))
                .thenReturn(List.of(message));

        List<Map<String, Object>> result = llmMessageService.listSessionsFull();

        assertEquals(List.of(2L, 1L), result.stream().map(row -> row.get("id")).toList());
        assertEquals(0, result.getFirst().get("messageCount"));
        assertEquals(1, result.get(1).get("messageCount"));
        assertEquals(1, ((List<?>) result.get(1).get("messages")).size());
        verify(llmMessageRepository).findBySessionIdIsNullAndDeletedFalseOrderByIdAsc();
        verify(llmSessionRepository).findByDeletedFalseOrderByUpdateTimeDescIdDesc();
        verify(llmMessageRepository).findBySessionIdInAndDeletedFalseOrderByIdAsc(List.of(2L, 1L));
        org.mockito.Mockito.verifyNoMoreInteractions(llmSessionRepository, llmMessageRepository);
    }
}
