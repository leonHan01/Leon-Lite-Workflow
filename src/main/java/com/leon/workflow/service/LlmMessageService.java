package com.leon.workflow.service;

import com.leon.workflow.dao.entity.LlmMessage;
import com.leon.workflow.dao.entity.LlmSession;
import com.leon.workflow.dao.repository.LlmMessageRepository;
import com.leon.workflow.dao.repository.LlmSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.leon.workflow.service.Payloads.clip;
import static com.leon.workflow.service.Payloads.stringValue;

@Service
public class LlmMessageService {
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private final LlmMessageRepository llmMessageRepository;
    private final LlmSessionRepository llmSessionRepository;

    public LlmMessageService(LlmMessageRepository llmMessageRepository,
                             LlmSessionRepository llmSessionRepository) {
        this.llmMessageRepository = llmMessageRepository;
        this.llmSessionRepository = llmSessionRepository;
    }

    @Transactional
    public List<Map<String, Object>> listSessions() {
        migrateOrphans();
        List<LlmSession> sessions = llmSessionRepository.findByDeletedFalseOrderByUpdateTimeDescIdDesc();
        if (sessions.isEmpty()) return List.of();
        Map<Long, Integer> counts = llmMessageRepository.countMessagesBySessionIds(sessions.stream().map(LlmSession::getId).toList())
                .stream().collect(Collectors.toMap(LlmMessageRepository.SessionMessageCount::getSessionId,
                        LlmMessageRepository.SessionMessageCount::getMessageCount));
        return sessions.stream().map(session -> toSessionSummary(session, counts.getOrDefault(session.getId(), 0))).toList();
    }

    @Transactional
    public List<Map<String, Object>> listSessionsFull() {
        migrateOrphans();
        List<LlmSession> sessions = llmSessionRepository.findByDeletedFalseOrderByUpdateTimeDescIdDesc();
        if (sessions.isEmpty()) return List.of();
        Map<Long, List<LlmMessage>> messages = llmMessageRepository
                .findBySessionIdInAndDeletedFalseOrderByIdAsc(sessions.stream().map(LlmSession::getId).toList())
                .stream().collect(Collectors.groupingBy(LlmMessage::getSessionId));
        return sessions.stream().map(session -> toSessionDetail(session, messages.getOrDefault(session.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSession(Long id) {
        LlmSession session = requireSession(id);
        return toSessionDetail(session, llmMessageRepository.findBySessionIdAndDeletedFalseOrderByIdAsc(id));
    }

    private Map<String, Object> toSessionDetail(LlmSession session, List<LlmMessage> messages) {
        Map<String, Object> result = toSessionSummary(session, messages.size());
        result.put("messages", messages.stream().map(this::toMessageResponse).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> createSession(Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        LlmSession session = new LlmSession();
        String title = stringValue(request.get("title"));
        session.setTitle(title.isBlank() ? "新对话" : clip(title, 200));
        session.setDeleted(false);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        return toSessionSummary(llmSessionRepository.save(session), 0);
    }

    @Transactional
    public Map<String, Object> addMessage(Long sessionId, Map<String, Object> request) {
        LlmSession session = requireSession(sessionId);
        String role = stringValue(request.get("role"));
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("角色无效");
        }
        String content = stringValue(request.get("content"));
        if (content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        LlmMessage message = new LlmMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoningContent(stringValue(request.get("reasoningContent")));
        message.setDeleted(false);
        message.setCreateTime(now);
        LlmMessage saved = llmMessageRepository.save(message);
        if ("user".equals(role) && ("新对话".equals(session.getTitle()) || session.getTitle().isBlank())) {
            session.setTitle(clip(content, 40));
        }
        session.setUpdateTime(now);
        llmSessionRepository.save(session);
        return toMessageResponse(saved);
    }

    @Transactional
    public void deleteSession(Long id) {
        LlmSession session = requireSession(id);
        LocalDateTime now = LocalDateTime.now();
        session.setDeleted(true);
        session.setUpdateTime(now);
        llmSessionRepository.save(session);
        for (LlmMessage message : llmMessageRepository.findBySessionIdAndDeletedFalseOrderByIdAsc(id)) {
            message.setDeleted(true);
            llmMessageRepository.save(message);
        }
    }

    @Transactional
    public void migrateOrphans() {
        List<LlmMessage> orphans = llmMessageRepository.findBySessionIdIsNullAndDeletedFalseOrderByIdAsc();
        if (orphans.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LlmSession session = new LlmSession();
        session.setTitle("历史对话");
        session.setDeleted(false);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        LlmSession saved = llmSessionRepository.save(session);
        for (LlmMessage message : orphans) {
            message.setSessionId(saved.getId());
            llmMessageRepository.save(message);
        }
    }

    private LlmSession requireSession(Long id) {
        return llmSessionRepository.findById(id)
                .filter(session -> !Boolean.TRUE.equals(session.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("对话不存在: " + id));
    }

    private Map<String, Object> toSessionSummary(LlmSession session, int messageCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getId());
        result.put("title", session.getTitle());
        result.put("messageCount", messageCount);
        result.put("createTime", session.getCreateTime());
        result.put("updateTime", session.getUpdateTime());
        return result;
    }

    private Map<String, Object> toMessageResponse(LlmMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("sessionId", message.getSessionId());
        result.put("role", message.getRole());
        result.put("content", message.getContent());
        result.put("reasoningContent", message.getReasoningContent());
        result.put("createTime", message.getCreateTime());
        return result;
    }
}
