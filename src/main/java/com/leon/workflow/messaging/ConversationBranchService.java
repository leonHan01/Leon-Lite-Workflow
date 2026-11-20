package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** A private, durable prefix copy. Creating a branch never queues model calls. */
@Service
public class ConversationBranchService {
    static final int MAX_MESSAGES = 10000;
    static final long MAX_ATTACHMENT_BYTES = 128L * 1024 * 1024;
    private final DeviceMessageStore store;
    private final JdbcTemplate jdbc;
    private final AgentRegistry agents;

    public ConversationBranchService(DeviceMessageStore store, JdbcTemplate jdbc, AgentRegistry agents) {
        this.store = store;
        this.jdbc = jdbc;
        this.agents = agents;
    }

    @PostConstruct void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_conversation_branch (
                owner_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL,
                source_conversation_id VARCHAR(80) NOT NULL, source_message_id BIGINT NOT NULL,
                initial_name VARCHAR(60) NOT NULL, conversation_id VARCHAR(80) NOT NULL,
                PRIMARY KEY(owner_id,request_id))
            """);
    }

    DeviceMessageStore.Conversation create(String sourceId, long through, String requestId, String name, String owner) {
        String request = DeviceMessageStore.uuid(requestId);
        if (through <= 0) throw bad("请选择已发送成功的消息创建分支");
        String title = name == null ? "" : name.strip();
        if (title.isBlank() || title.length() > 60 || title.codePoints().anyMatch(Character::isISOControl))
            throw bad("分支名称应为 1–60 字且不能含控制字符");
        synchronized (store) {
            var source = store.conversation(sourceId);
            var previous = jdbc.queryForList("SELECT * FROM chat_conversation_branch WHERE owner_id=? AND request_id=?", owner, request);
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (!sourceId.equals(row.get("source_conversation_id")) || through != ((Number) row.get("source_message_id")).longValue()
                        || !title.equals(row.get("initial_name")))
                    throw DeviceMessageStore.failure(HttpStatus.CONFLICT, "相同请求不能创建不同分支");
                var existing = store.conversation((String) row.get("conversation_id"));
                if (!owner.equals(existing.ownerId())) throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND, "分支不存在或无权访问");
                return existing;
            }
            // Check both coordinates; a valid ID in another conversation is never a branch point.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE conversation_id=? AND id=?", Integer.class, sourceId, through) != 1)
                throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND, "原消息不存在或已清空，请刷新后重试");
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE conversation_id=? AND id<=?", Long.class, sourceId, through);
            if (count > MAX_MESSAGES) throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE, "分支最多复制 10,000 条消息，请选择更早的分支点");
            var messages = store.archiveMessages(sourceId, 0, through);
            var imageIds = new LinkedHashSet<String>();
            var documentIds = new LinkedHashSet<String>();
            for (var message : messages) {
                message.images().forEach(image -> imageIds.add(image.id()));
                message.documents().forEach(doc -> documentIds.add(doc.id()));
                if (message.quote() != null) {
                    message.quote().images().forEach(image -> imageIds.add(image.id()));
                    message.quote().documents().forEach(doc -> documentIds.add(doc.id()));
                }
            }
            long bytes = 0;
            for (String id : imageIds) bytes += jdbc.queryForObject("SELECT byte_count FROM chat_message_image WHERE id=? AND conversation_id=?", Long.class, id, sourceId);
            for (String id : documentIds) bytes += jdbc.queryForObject("SELECT byte_count FROM chat_message_document WHERE id=? AND conversation_id=?", Long.class, id, sourceId);
            if (bytes > MAX_ATTACHMENT_BYTES) throw DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE, "分支附件合计不能超过 128 MB，请选择更早的分支点");
            String room = "branch:" + UUID.randomUUID();
            String finalTitle = title;
            return store.atomic(() -> {
                var members = ChatAccess.personalRoom(sourceId) ? agents.list().stream().map(AgentRegistry.Agent::id).toList() : source.agentIds();
                jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_id,agent_ids,owner_id,initial_name) VALUES(?,?,?,?,?,?,?)",
                    room, finalTitle, source.kind(), source.agentId(), String.join(",", members), owner, finalTitle);
                var imageMap = new HashMap<String,String>();
                for (String old : imageIds) {
                    String id = UUID.randomUUID().toString(); imageMap.put(old, id);
                    jdbc.update("""
                        INSERT INTO chat_message_image(id,conversation_id,source_hash,mime_type,width,height,byte_count,image_data,created_at)
                        SELECT ?,?,source_hash,mime_type,width,height,byte_count,image_data,created_at
                        FROM chat_message_image WHERE id=? AND conversation_id=?
                        """, id, room, old, sourceId);
                }
                var documentMap = new HashMap<String,String>();
                for (String old : documentIds) {
                    String id = UUID.randomUUID().toString(); documentMap.put(old, id);
                    jdbc.update("""
                        INSERT INTO chat_message_document(id,conversation_id,user_id,name,mime_type,byte_count,character_count,truncated,document_data,extracted_text,source_hash)
                        SELECT ?,?,?,name,mime_type,byte_count,character_count,truncated,document_data,extracted_text,source_hash
                        FROM chat_message_document WHERE id=? AND conversation_id=?
                        """, id, room, owner, old, sourceId);
                }
                var ids = new TreeMap<Long,Long>();
                for (var message : messages) {
                    String client = UUID.randomUUID().toString();
                    jdbc.update("""
                        INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id,message_role,agent_id,user_id,mention_ids,image_ids,document_ids,quote_sender,quote_body,quote_image_ids,quote_document_ids)
                        SELECT ?,device_id,sender,platform,body,created_at,?,message_role,agent_id,user_id,mention_ids,?,?,quote_sender,quote_body,?,?
                        FROM device_message WHERE id=? AND conversation_id=?
                        """, client, room, mapped(message.images().stream().map(DeviceMessageStore.Image::id).toList(), imageMap),
                        mapped(message.documents().stream().map(DeviceMessageStore.Document::id).toList(), documentMap),
                        message.quote() == null ? "" : mapped(message.quote().images().stream().map(DeviceMessageStore.Image::id).toList(), imageMap),
                        message.quote() == null ? "" : mapped(message.quote().documents().stream().map(DeviceMessageStore.Document::id).toList(), documentMap), message.id(), sourceId);
                    ids.put(message.id(), jdbc.queryForObject("SELECT id FROM device_message WHERE device_id=? AND client_message_id=?", Long.class, message.deviceId(), client));
                }
                for (var message : messages) jdbc.update("UPDATE device_message SET reply_to=?,quote_message_id=? WHERE id=?",
                    message.replyTo() == null ? null : ids.get(message.replyTo()), message.quote() == null ? null : ids.get(message.quote().id()), ids.get(message.id()));
                var policy = jdbc.queryForList("SELECT recent_rounds,after_id FROM chat_context_policy WHERE conversation_id=?", sourceId);
                int rounds = policy.isEmpty() ? 10 : ((Number) policy.getFirst().get("recent_rounds")).intValue();
                long after = policy.isEmpty() ? 0 : ((Number) policy.getFirst().get("after_id")).longValue();
                // Summaries/snapshots can contain later replies. Rebuild solely from the copied prefix.
                var boundary = after <= through ? ids.floorEntry(after) : null;
                jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,?,?,0)", room, rounds, boundary == null ? 0 : boundary.getValue());
                jdbc.update("INSERT INTO chat_conversation_branch(owner_id,request_id,source_conversation_id,source_message_id,initial_name,conversation_id) VALUES(?,?,?,?,?,?)",
                    owner, request, sourceId, through, finalTitle, room);
                return store.conversation(room);
            });
        }
    }

    private static String mapped(List<String> ids, Map<String,String> mapping) {
        return String.join(",", ids.stream().map(mapping::get).toList());
    }
    private static org.springframework.web.server.ResponseStatusException bad(String message) {
        return DeviceMessageStore.failure(HttpStatus.BAD_REQUEST, message);
    }
}
