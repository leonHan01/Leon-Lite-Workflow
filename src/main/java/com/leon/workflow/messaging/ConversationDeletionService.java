package com.leon.workflow.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Permanently removes a room and its stored content in one transaction. */
@Service
public class ConversationDeletionService {
    public record Result(String conversationId, long deletedMessages) {}
    private final DeviceMessageStore store;
    private final AgentContextManager contexts;
    private final ChatAccess access;
    private final JdbcTemplate jdbc;

    public ConversationDeletionService(DeviceMessageStore store, AgentContextManager contexts,
            ChatAccess access, JdbcTemplate jdbc) {
        this.store = store; this.contexts = contexts; this.access = access; this.jdbc = jdbc;
    }

    public AgentContextManager.ClearPreview preview(ChatUserRegistry.User user, String room) {
        synchronized (store) {
            access.require(user, room, true);
            if (ChatAccess.personalRoom(room))
                throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST, "设备群聊请使用清空聊天记录");
            return contexts.clearPreview(room);
        }
    }

    public Result delete(ChatUserRegistry.User user, AgentContextController.Clear input) {
        String request = DeviceMessageStore.uuid(input.requestId()), room = input.conversationId();
        if (!input.confirm() || input.expectedLatestMessageId() < 0 || input.expectedHistoryRevision() < 0)
            throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST, "请先确认要永久删除此会话");
        synchronized (store) {
            var previous = jdbc.queryForList("SELECT * FROM chat_deleted_conversation WHERE id=?", room);
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (!user.id().equals(row.get("owner_id")) || !request.equals(row.get("request_id")))
                    throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
                return new Result(room, ((Number) row.get("deleted_messages")).longValue());
            }
            preview(user, room);
            return store.atomic(() -> {
                // Include favorites retained by an earlier clear, whose original messages no longer exist.
                var favorites = jdbc.queryForList("SELECT id FROM chat_favorite WHERE conversation_id=?", Long.class, room);
                for (long favorite : favorites) {
                    jdbc.update("DELETE FROM chat_favorite_image WHERE favorite_id=?", favorite);
                    jdbc.update("DELETE FROM chat_favorite_document WHERE favorite_id=?", favorite);
                    jdbc.update("DELETE FROM chat_favorite WHERE id=?", favorite);
                }
                // Deletion has its own idempotency marker; never reuse a previous /clear request.
                var cleared = contexts.clear(room, java.util.UUID.randomUUID().toString(), input.expectedLatestMessageId(), input.expectedHistoryRevision(), true);
                jdbc.update("DELETE FROM chat_context_policy WHERE conversation_id=?", room);
                jdbc.update("DELETE FROM chat_conversation_member WHERE conversation_id=?", room);
                // Retain only content-free request markers so delayed creation retries cannot resurrect a room.
                jdbc.update("UPDATE chat_conversation_branch SET initial_name='',source_conversation_id='',source_message_id=0 WHERE conversation_id=?", room);
                jdbc.update("DELETE FROM chat_conversation_branch WHERE source_conversation_id=?", room);
                jdbc.update("UPDATE chat_backup_import SET source_hash='',target_agent='' WHERE conversation_id=?", room);
                jdbc.update("INSERT INTO chat_deleted_conversation(id,owner_id,request_id,deleted_messages) VALUES(?,?,?,?)",
                    room, user.id(), request, cleared.clearedMessages());
                jdbc.update("DELETE FROM chat_conversation WHERE id=?", room);
                return new Result(room, cleared.clearedMessages());
            });
        }
    }
}
