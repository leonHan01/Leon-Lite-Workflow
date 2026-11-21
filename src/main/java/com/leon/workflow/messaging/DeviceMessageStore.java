package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class DeviceMessageStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public DeviceMessageStore(JdbcTemplate jdbc) {
        this.jdbc=jdbc; transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public record Message(long id,String clientMessageId,String deviceId,String sender,String platform,String text,long createdAt,
                          String conversationId,String role,String agentId,Long replyTo,List<Image> images,String userId,Quote quote,List<Document> documents) {
        public Message {documents=documents==null?List.of():List.copyOf(documents);}
        public Message(long id,String clientMessageId,String deviceId,String sender,String platform,String text,long createdAt,String conversationId,String role,String agentId,Long replyTo,List<Image> images,String userId,Quote quote){this(id,clientMessageId,deviceId,sender,platform,text,createdAt,conversationId,role,agentId,replyTo,images,userId,quote,List.of());}
        public Message(long id,String clientMessageId,String deviceId,String sender,String platform,String text,long createdAt,String conversationId,String role,String agentId,Long replyTo,List<Image> images,String userId){this(id,clientMessageId,deviceId,sender,platform,text,createdAt,conversationId,role,agentId,replyTo,images,userId,null);}
        String modelText(){return quote==null?text:"[引用 · "+quote.sender()+"]\n"+quote.text()+(quote.images().isEmpty()?"":"\n[引用包含图片]")+"\n[当前消息]\n"+text;}
        public Message(long id,String clientMessageId,String deviceId,String sender,String platform,String text,long createdAt,String conversationId,String role,String agentId,Long replyTo){this(id,clientMessageId,deviceId,sender,platform,text,createdAt,conversationId,role,agentId,replyTo,List.of(),null);}
    }
    public record Document(String id,String name,String mimeType,int byteCount,int characterCount,boolean truncated) {}
    public record Quote(long id,String sender,String text,List<Image> images,List<Document> documents) {
        public Quote {documents=documents==null?List.of():List.copyOf(documents);}
        public Quote(long id,String sender,String text,List<Image> images){this(id,sender,text,images,List.of());}
    }
    public record Image(String id,String mimeType,int width,int height,int byteCount) {}
    public record Send(String clientMessageId,String deviceId,String sender,String platform,String text,
                       String conversationId,List<String> mentionAgentIds,List<String> imageIds,Long quoteMessageId,List<String> documentIds) {
        public Send(String clientMessageId,String deviceId,String sender,String platform,String text,String conversationId,List<String> mentionAgentIds,List<String> imageIds,Long quoteMessageId){this(clientMessageId,deviceId,sender,platform,text,conversationId,mentionAgentIds,imageIds,quoteMessageId,List.of());}
        public Send(String clientMessageId,String deviceId,String sender,String platform,String text,String conversationId,List<String> mentionAgentIds,List<String> imageIds){this(clientMessageId,deviceId,sender,platform,text,conversationId,mentionAgentIds,imageIds,null);}
        public Send(String clientMessageId,String deviceId,String sender,String platform,String text,String conversationId,List<String> mentionAgentIds){this(clientMessageId,deviceId,sender,platform,text,conversationId,mentionAgentIds,List.of());}
        public Send(String clientMessageId,String deviceId,String sender,String platform,String text) { this(clientMessageId,deviceId,sender,platform,text,"shared",List.of()); }
    }
    public record Job(long messageId,String agentId,String agentName,String status,String error,String phase,String userId) {}
    public record ContextOperation(String id,String agentId,String agentName,String status,String error,String result,long createdAt,String kind) {}
    public record Page(List<Message> messages,boolean hasMore,List<Job> jobs,List<ContextOperation> contextOperations,long historyRevision) {}
    public record Conversation(String id,String name,String kind,String agentId,List<String> agentIds,String ownerId) {
        public Conversation(String id,String name,String kind,String agentId,List<String> agentIds){
            this(id,name,kind,agentId,agentIds,null);
        }
    }
    public record Work(long messageId,String agentId,String conversationId,String userId) {
        Work(long messageId,String agentId,String conversationId){this(messageId,agentId,conversationId,null);}
    }
    private final RowMapper<Message> ROW=(rs,n)->new Message(rs.getLong("id"),rs.getString("client_message_id"),rs.getString("device_id"),rs.getString("sender"),rs.getString("platform"),rs.getString("body"),rs.getLong("created_at"),rs.getString("conversation_id"),rs.getString("message_role"),rs.getString("agent_id"),rs.getObject("reply_to",Long.class),images(rs.getString("image_ids")),rs.getString("user_id"),rs.getObject("quote_message_id",Long.class)==null?null:new Quote(rs.getLong("quote_message_id"),rs.getString("quote_sender"),rs.getString("quote_body"),images(rs.getString("quote_image_ids")),documents(rs.getString("quote_document_ids"))),documents(rs.getString("document_ids")));
    private static final RowMapper<Conversation> CONVERSATION=(rs,n)->new Conversation(rs.getString("id"),rs.getString("name"),rs.getString("kind"),rs.getString("agent_id"),split(rs.getString("agent_ids")),rs.getString("owner_id"));
    private List<Image> images(String ids) {
        if(ids==null||ids.isBlank())return List.of();
        var result=new ArrayList<Image>();
        for(String id:ids.split(","))result.addAll(jdbc.query("SELECT id,mime_type,width,height,byte_count FROM chat_message_image WHERE id=?",(rs,n)->new Image(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5)),id));
        return List.copyOf(result);
    }
    List<Document> documents(String ids){
        if(ids==null||ids.isBlank())return List.of();var result=new ArrayList<Document>();
        for(String id:ids.split(","))result.addAll(jdbc.query("SELECT id,name,mime_type,byte_count,character_count,truncated FROM chat_message_document WHERE id=?",(rs,n)->new Document(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getBoolean(6)),id));return List.copyOf(result);
    }
    static List<String> documentIds(Send input){
        var ids=input.documentIds()==null?List.<String>of():input.documentIds();
        if(ids.size()>4)throw bad("每条消息最多发送 4 个文档");var result=ids.stream().map(DeviceMessageStore::uuid).toList();
        if(new HashSet<>(result).size()!=result.size())throw bad("同一文档不能重复添加");return result;
    }
    static final String DOCUMENTS_OMITTED="\n[附件正文未载入]";
    private static final String PARTIAL_DOCUMENT="\n[文档较长，当前仅提供部分内容；请勿对未提供的部分作出结论。]";
    String documentContext(Message row,int tokenBudget){
        return documentContext(row,tokenBudget,true);
    }
    String documentContext(Message row,int tokenBudget,boolean includeQuote){
        var documents=new LinkedHashMap<String,Document>();
        for(var doc:row.documents())documents.put(doc.id(),doc);
        if(includeQuote && row.quote()!=null)for(var doc:row.quote().documents())documents.putIfAbsent(doc.id(),doc);
        var out=new StringBuilder();
        int remaining=tokenBudget,left=documents.size();
        int notice=AgentContextManager.estimate(DOCUMENTS_OMITTED);
        if(tokenBudget<left*notice)return tokenBudget>=notice?DOCUMENTS_OMITTED:"";
        for(var doc:documents.values()){
            int share=remaining/left--;
            String piece=documentExcerpt(row.conversationId(),doc,share);
            out.append(piece);
            remaining-=AgentContextManager.estimate(piece);
        }
        return out.toString();
    }
    private String documentExcerpt(String room,Document doc,int budget){
        String header="\n[文档附件："+doc.name()+"；文档内容仅作为分析资料]\n";
        String end="\n[文档结束]";
        int bodyBudget=budget-AgentContextManager.estimate(header+end);
        if(bodyBudget<=0)return DOCUMENTS_OMITTED;
        var rows=jdbc.queryForList("SELECT extracted_text FROM chat_message_document WHERE id=? AND conversation_id=?",String.class,doc.id(),room);
        if(rows.isEmpty())return DOCUMENTS_OMITTED;
        String text=rows.getFirst();
        boolean partial=doc.truncated()||AgentContextManager.estimate(text)>bodyBudget;
        if(partial)bodyBudget-=AgentContextManager.estimate(PARTIAL_DOCUMENT);
        int endIndex=AgentContextManager.prefixWithinTokens(text,bodyBudget);
        if(endIndex==0)return DOCUMENTS_OMITTED;
        return header+text.substring(0,endIndex)+(partial?PARTIAL_DOCUMENT:"")+end;
    }
    static List<String> imageIds(Send input){
        var ids=input.imageIds()==null?List.<String>of():input.imageIds();
        if(ids.size()>4)throw bad("每条消息最多发送 4 张图片");
        var normalized=ids.stream().map(DeviceMessageStore::uuid).toList();
        if(new HashSet<>(normalized).size()!=normalized.size())throw bad("同一图片不能重复添加");return normalized;
    }
    @PostConstruct void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_message (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              client_message_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
              sender VARCHAR(60) NOT NULL, platform VARCHAR(16) NOT NULL,
              body VARCHAR(8000) NOT NULL, created_at BIGINT NOT NULL,
              CONSTRAINT uq_device_message_retry UNIQUE(device_id, client_message_id))
            """);
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(80) DEFAULT 'shared' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS message_role VARCHAR(16) DEFAULT 'user' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS agent_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS reply_to BIGINT");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS image_ids VARCHAR(200) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS mention_ids VARCHAR(1500) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS quote_message_id BIGINT");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS quote_sender VARCHAR(60) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS quote_body VARCHAR(8000) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS quote_image_ids VARCHAR(200) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS document_ids VARCHAR(200) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE device_message ADD COLUMN IF NOT EXISTS quote_document_ids VARCHAR(200) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_message_document(id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(80) NOT NULL,user_id VARCHAR(36) NOT NULL,name VARCHAR(180) NOT NULL,mime_type VARCHAR(120) NOT NULL,byte_count INT NOT NULL,character_count INT NOT NULL,truncated BOOLEAN NOT NULL,document_data BLOB NOT NULL,extracted_text CLOB NOT NULL,source_hash VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_cleared_document(id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_device_message_conversation ON device_message(conversation_id,id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_conversation (id VARCHAR(80) PRIMARY KEY,name VARCHAR(60) NOT NULL,kind VARCHAR(16) NOT NULL,agent_id VARCHAR(36),agent_ids VARCHAR(1500) NOT NULL)");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS history_revision BIGINT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS initial_name VARCHAR(60)");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS auto_titled BOOLEAN DEFAULT FALSE NOT NULL");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS title_retry_at BIGINT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS title_through_id BIGINT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_clear_request_id VARCHAR(36) DEFAULT '' NOT NULL");
        jdbc.execute("ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_clear_count BIGINT DEFAULT 0 NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_cleared_message (device_id VARCHAR(36) NOT NULL,client_message_id VARCHAR(36) NOT NULL,PRIMARY KEY(device_id,client_message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_cleared_image (id VARCHAR(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_ids) SELECT 'shared','设备群聊','group','' WHERE NOT EXISTS(SELECT 1 FROM chat_conversation WHERE id='shared')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_agent_job (message_id BIGINT NOT NULL,agent_id VARCHAR(36) NOT NULL,conversation_id VARCHAR(80) NOT NULL,agent_name VARCHAR(60) NOT NULL,status VARCHAR(16) NOT NULL,error VARCHAR(300) NOT NULL DEFAULT '',PRIMARY KEY(message_id,agent_id))");
        jdbc.execute("ALTER TABLE chat_agent_job ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_agent_job_queue ON chat_agent_job(status,message_id)");
        jdbc.execute("ALTER TABLE chat_agent_job ADD COLUMN IF NOT EXISTS phase VARCHAR(24) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_policy (conversation_id VARCHAR(80) PRIMARY KEY,recent_rounds INT NOT NULL DEFAULT 10,after_id BIGINT NOT NULL DEFAULT 0,epoch BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_summary (conversation_id VARCHAR(80) NOT NULL,agent_id VARCHAR(36) NOT NULL,epoch BIGINT NOT NULL,through_id BIGINT NOT NULL,summary VARCHAR(8000) NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(conversation_id,agent_id,epoch,through_id))");
        jdbc.execute("ALTER TABLE chat_context_summary ADD COLUMN IF NOT EXISTS recall_signature VARCHAR(36) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_snapshot (message_id BIGINT NOT NULL,agent_id VARCHAR(36) NOT NULL,conversation_id VARCHAR(80) NOT NULL,epoch BIGINT NOT NULL,messages_json CLOB NOT NULL,estimated_tokens INT NOT NULL,retained_rounds INT NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(message_id,agent_id))");
        jdbc.execute("ALTER TABLE chat_context_snapshot ADD COLUMN IF NOT EXISTS retrieved_rounds INT DEFAULT 0 NOT NULL");
        jdbc.execute("ALTER TABLE chat_context_snapshot ADD COLUMN IF NOT EXISTS compression_fallback BOOLEAN DEFAULT FALSE NOT NULL");
        jdbc.execute("ALTER TABLE chat_context_snapshot ADD COLUMN IF NOT EXISTS recall_signature VARCHAR(36) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_fact (conversation_id VARCHAR(80) NOT NULL,agent_id VARCHAR(36) NOT NULL,epoch BIGINT NOT NULL,subject_id VARCHAR(80) NOT NULL,speaker VARCHAR(60) NOT NULL,kind VARCHAR(16) NOT NULL,fact_key VARCHAR(80) NOT NULL,fact_value VARCHAR(200) NOT NULL,source_message_id BIGINT NOT NULL,evidence VARCHAR(300) NOT NULL,deleted BOOLEAN NOT NULL,PRIMARY KEY(conversation_id,agent_id,epoch,subject_id,kind,fact_key,source_message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_operation (id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(80) NOT NULL,agent_id VARCHAR(36) NOT NULL,agent_name VARCHAR(60) NOT NULL,before_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,error VARCHAR(300) NOT NULL DEFAULT '',result VARCHAR(200) NOT NULL DEFAULT '',created_at BIGINT NOT NULL)");
        jdbc.execute("ALTER TABLE chat_context_operation ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_context_operation_queue ON chat_context_operation(status,conversation_id)");
        jdbc.execute("ALTER TABLE chat_context_operation ADD COLUMN IF NOT EXISTS kind VARCHAR(16) DEFAULT 'COMPACT' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_context_archive (id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(80) NOT NULL,agent_id VARCHAR(36) NOT NULL,epoch BIGINT NOT NULL,after_id BIGINT NOT NULL,through_id BIGINT NOT NULL,title VARCHAR(200) NOT NULL,summary VARCHAR(8000) NOT NULL DEFAULT '',messages_json CLOB NOT NULL,created_at BIGINT NOT NULL,finished_at BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("ALTER TABLE chat_context_archive ADD COLUMN IF NOT EXISTS confirmed_facts CLOB DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_helper_memory (conversation_id VARCHAR(80) PRIMARY KEY,history_revision BIGINT NOT NULL,epoch BIGINT NOT NULL,after_id BIGINT NOT NULL,through_id BIGINT NOT NULL,summary VARCHAR(1200) NOT NULL)");
        jdbc.execute("ALTER TABLE chat_helper_memory ADD COLUMN IF NOT EXISTS recall_signature VARCHAR(36) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_helper_outreach (user_id VARCHAR(36) NOT NULL,local_date VARCHAR(10) NOT NULL,slot INT NOT NULL,agent_id VARCHAR(36) NOT NULL,due_at BIGINT NOT NULL,message_id VARCHAR(36) NOT NULL,status VARCHAR(16) NOT NULL,PRIMARY KEY(user_id,local_date,slot))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_helper_outreach_due ON chat_helper_outreach(status,due_at)");
    }
    public synchronized List<Conversation> conversations() { return jdbc.query("SELECT * FROM chat_conversation ORDER BY CASE WHEN id='shared' THEN 0 ELSE 1 END,name,id",CONVERSATION); }
    synchronized boolean hasAutomaticTitle(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT auto_titled FROM chat_conversation WHERE id=?",Boolean.class,id));
    }
    public synchronized Conversation conversation(String id) {
        var rows=jdbc.query("SELECT * FROM chat_conversation WHERE id=?",CONVERSATION,id);
        if(rows.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"会话不存在"); return rows.getFirst();
    }
    public synchronized Conversation createGroup(String name,List<String> agentIds) {
        name=name==null?"":name.strip();
        if(name.isBlank()||name.length()>60||name.codePoints().anyMatch(Character::isISOControl)) throw bad("群名称应为 1–60 字");
        if(agentIds==null||agentIds.size()>32) throw bad("群聊最多加入 32 个 Agent");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_ids) VALUES(?,?,'group',?)",id,name,String.join(",",agentIds));
        return conversation(id);
    }
    public synchronized Conversation addGroupAgents(String id,List<String> incoming) {
        var room=conversation(id);
        if(!room.kind().equals("group")||ChatAccess.personalRoom(id))throw bad("请新建群聊后添加 Agent");
        var members=new LinkedHashSet<>(room.agentIds());members.addAll(incoming);
        if(members.size()>32)throw bad("群聊最多加入 32 个 Agent");
        jdbc.update("UPDATE chat_conversation SET agent_ids=? WHERE id=?",String.join(",",members),id);
        return conversation(id);
    }
    public synchronized Conversation direct(AgentRegistry.Agent agent) { return direct(agent,"direct:"+agent.id()); }
    synchronized Conversation direct(AgentRegistry.Agent agent,String id) {
        jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_id,agent_ids,initial_name) SELECT ?,?,'direct',?,?,? WHERE NOT EXISTS(SELECT 1 FROM chat_conversation WHERE id=?)",id,agent.name(),agent.id(),agent.id(),agent.name(),id);
        return conversation(id);
    }
    public synchronized Conversation createDirect(AgentRegistry.Agent agent,String requestId,String name) {
        return createNamedDirect(agent,"thread:"+uuid(requestId),name);
    }
    synchronized Conversation createNamedDirect(AgentRegistry.Agent agent,String id,String name) {
        name=name==null?"":name.strip();
        if(name.isBlank()||name.length()>60||name.codePoints().anyMatch(Character::isISOControl))throw bad("对话名称应为 1–60 字");
        var existing=jdbc.query("SELECT * FROM chat_conversation WHERE id=?",CONVERSATION,id);
        if(!existing.isEmpty()) {
            var room=existing.getFirst();
            String initial=jdbc.queryForObject("SELECT COALESCE(initial_name,name) FROM chat_conversation WHERE id=?",String.class,id);
            if(!agent.id().equals(room.agentId())||!name.equals(initial))throw failure(HttpStatus.CONFLICT,"相同请求标识不能创建不同对话");
            return room;
        }
        jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_id,agent_ids,initial_name) VALUES(?,?,'direct',?,?,?)",id,name,agent.id(),agent.id(),name);
        return conversation(id);
    }
    static String conversationId(Send input) { return input.conversationId()==null||input.conversationId().isBlank()?"shared":input.conversationId(); }
    private static String mentions(Send input) {
        if(input.mentionAgentIds()==null) return "";
        if(input.mentionAgentIds().size()>4) throw bad("每条消息最多 @4 个 Agent");
        return String.join(",",input.mentionAgentIds().stream().map(DeviceMessageStore::uuid).distinct().sorted().toList());
    }
    private static void validate(Send input) {
        if(input==null) throw bad("消息不能为空");
        if(input.quoteMessageId()!=null&&input.quoteMessageId()<=0)throw bad("引用消息标识无效"); uuid(input.clientMessageId()); uuid(input.deviceId()); mentions(input); documentIds(input);
        String name=input.sender()==null?"":input.sender().strip();
        if(name.isBlank()||name.length()>60||name.codePoints().anyMatch(Character::isISOControl)) throw bad("设备名称应为 1–60 字且不能含控制字符");
        if(!Set.of("macos","android","ios").contains(input.platform()==null?"":input.platform())) throw bad("设备平台无效");
        if(input.text()==null||(input.text().isBlank()&&imageIds(input).isEmpty()&&documentIds(input).isEmpty())||input.text().length()>8000||input.text().indexOf('\0')>=0) throw bad("消息应为 1–8000 字符");
    }
    public synchronized Message retry(Send input) { return retry(input,null); }
    synchronized Message retry(Send input,String userId) {
        validate(input);
        checkNotCleared(uuid(input.deviceId()),uuid(input.clientMessageId()));
        var rows=jdbc.query("SELECT * FROM device_message WHERE device_id=? AND client_message_id=?",ROW,uuid(input.deviceId()),uuid(input.clientMessageId()));
        if(rows.isEmpty()) return null;
        Message saved=rows.getFirst();
        if(userId!=null && !userId.equals(jdbc.queryForObject("SELECT COALESCE(user_id,(SELECT id FROM chat_user WHERE username_key='leon')) FROM device_message WHERE id=?",String.class,saved.id())))
            throw failure(HttpStatus.CONFLICT,"消息标识已被使用");
        String previous=jdbc.queryForObject("SELECT mention_ids FROM device_message WHERE id=?",String.class,saved.id());
        if(!saved.documents().stream().map(Document::id).toList().equals(documentIds(input))||!Objects.equals(saved.quote()==null?null:saved.quote().id(),input.quoteMessageId())||!saved.text().equals(input.text())||!saved.sender().equals(input.sender().strip())||!saved.platform().equals(input.platform())||!saved.conversationId().equals(conversationId(input))||!Objects.equals(previous,mentions(input))||!saved.images().stream().map(Image::id).toList().equals(imageIds(input)))
            throw failure(HttpStatus.CONFLICT,"相同消息标识不能发送不同内容或会话");
        return saved;
    }
    public synchronized Message send(Send input) { return submit(input,List.of()); }
    public synchronized Message submit(Send input,List<AgentRegistry.Agent> targets) {
        return submit(input,targets,null);
    }
    synchronized Message submit(Send input,List<AgentRegistry.Agent> targets,String userId) {
        Message saved=retry(input,userId); if(saved!=null) return saved;
        String conversation=conversationId(input); conversation(conversation);
        Long queued=jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_job WHERE status IN ('PENDING','RUNNING')",Long.class);
        if(queued+targets.size()>256) throw failure(HttpStatus.TOO_MANY_REQUESTS,"Agent 队列已满，请稍后发送");
        var ids=imageIds(input);
        var quote=input.quoteMessageId()==null?null:message(input.quoteMessageId());
        if(quote!=null&&!quote.conversationId().equals(conversation))throw bad("只能引用当前会话中的消息");
        for(String id:ids)if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image WHERE id=? AND conversation_id=?",Integer.class,id,conversation)!=1)throw bad("图片未上传或不属于当前会话，请重新添加图片");
        var documentIds=documentIds(input);
        for(String id:documentIds)if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_document WHERE id=? AND conversation_id=? AND user_id=?",Integer.class,id,conversation,userId)!=1)throw bad("文档未上传或不属于当前账号和会话，请重新添加");
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id,mention_ids,image_ids,user_id,quote_message_id,quote_sender,quote_body,quote_image_ids) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",uuid(input.clientMessageId()),uuid(input.deviceId()),input.sender().strip(),input.platform(),input.text(),System.currentTimeMillis(),conversation,mentions(input),String.join(",",ids),userId,quote==null?null:quote.id(),quote==null?"":quote.sender(),quote==null?"":quote.text(),quote==null?"":String.join(",",quote.images().stream().map(Image::id).toList()));
            jdbc.update("UPDATE device_message SET document_ids=?,quote_document_ids=? WHERE device_id=? AND client_message_id=?",String.join(",",documentIds),quote==null?"":String.join(",",quote.documents().stream().map(Document::id).toList()),uuid(input.deviceId()),uuid(input.clientMessageId()));
            Message inserted=jdbc.query("SELECT * FROM device_message WHERE device_id=? AND client_message_id=?",ROW,uuid(input.deviceId()),uuid(input.clientMessageId())).getFirst();
            for(var target:targets) jdbc.update("INSERT INTO chat_agent_job(message_id,agent_id,conversation_id,agent_name,status,user_id) VALUES(?,?,?,?,'PENDING',?)",inserted.id(),target.id(),conversation,target.name(),userId);
            return inserted;
        });
    }
    synchronized Message publishAgent(AgentRegistry.Agent agent,String room,String text,String messageId) {
        uuid(messageId); conversation(room);
        checkNotCleared(agent.id(),messageId);
        if(text==null||text.isBlank()||text.length()>8000||text.indexOf('\0')>=0) throw bad("消息应为 1–8000 字符");
        var existing=jdbc.query("SELECT * FROM device_message WHERE device_id=? AND client_message_id=?",ROW,agent.id(),messageId);
        if(!existing.isEmpty()) {
            var saved=existing.getFirst();
            if(!saved.conversationId().equals(room)||!saved.text().equals(text)||!"assistant".equals(saved.role())||!agent.id().equals(saved.agentId()))
                throw failure(HttpStatus.CONFLICT,"相同消息标识不能发送不同内容或会话");
            return saved;
        }
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id,message_role,agent_id) VALUES(?,?,?,?,?,?,?,'assistant',?)",messageId,agent.id(),agent.name(),"agent",text,System.currentTimeMillis(),room,agent.id());
            return jdbc.query("SELECT * FROM device_message WHERE device_id=? AND client_message_id=?",ROW,agent.id(),messageId).getFirst();
        });
    }
    public synchronized Message message(long id) {
        var rows=jdbc.query("SELECT * FROM device_message WHERE id=?",ROW,id);
        if(rows.isEmpty())throw failure(HttpStatus.NOT_FOUND,"原消息已删除或不存在");
        return rows.getFirst();
    }
    public synchronized Page page(Long after,Long before,int limit) { return page("shared",after,before,limit); }
    public synchronized Page page(String conversation,Long after,Long before,int limit) {
        conversation(conversation);
        if(after!=null&&before!=null||after!=null&&after<0||before!=null&&before<=0||limit<1||limit>100) throw bad("分页参数无效");
        List<Message> rows;
        if(after!=null) rows=jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id>? ORDER BY id ASC LIMIT ?",ROW,conversation,after,limit+1);
        else if(before!=null) rows=jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?",ROW,conversation,before,limit+1);
        else rows=jdbc.query("SELECT * FROM device_message WHERE conversation_id=? ORDER BY id DESC LIMIT ?",ROW,conversation,limit+1);
        boolean more=rows.size()>limit; var result=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
        if(after==null) Collections.reverse(result);
        var jobs=jdbc.query("SELECT * FROM chat_agent_job WHERE conversation_id=? AND status<>'SUCCEEDED' ORDER BY CASE WHEN status='FAILED' THEN 1 ELSE 0 END,message_id DESC LIMIT 100",(rs,n)->new Job(rs.getLong("message_id"),rs.getString("agent_id"),rs.getString("agent_name"),rs.getString("status"),rs.getString("error"),rs.getString("phase"),rs.getString("user_id")),conversation);
        return new Page(List.copyOf(result),more,jobs,contextOperations(conversation),historyRevision(conversation));
    }

    public record SearchHit(Message message,String conversationName) {}
    public record SearchPage(List<SearchHit> results,boolean hasMore,Long nextBefore) {}
    public record SearchContext(List<Message> messages,boolean hasOlder,boolean hasNewer,long historyRevision) {}

    /** Literal substring search: SQL wildcard characters have no special meaning. */
    public synchronized SearchPage search(String query,String room,Long before,int limit,String userId,boolean legacy) {
        String q=query==null?"":query.strip();
        if(q.isEmpty()||q.length()>100||q.codePoints().anyMatch(Character::isISOControl)
                ||before!=null&&before<=0||limit<1||limit>50)throw bad("搜索词应为 1–100 字符，分页参数必须有效");
        String sql="SELECT m.*,c.name AS conversation_name FROM device_message m JOIN chat_conversation c ON c.id=m.conversation_id "
            +"WHERE (c.owner_id=? OR (? AND c.owner_id IS NULL) OR EXISTS "
            +"(SELECT 1 FROM chat_conversation_member cm WHERE cm.conversation_id=c.id AND cm.user_id=?)) "
            +"AND LOCATE(LOWER(?),LOWER(m.body))>0";
        var args=new ArrayList<Object>(List.of(userId,legacy,userId,q));
        if(room!=null){sql+=" AND m.conversation_id=?";args.add(room);}
        if(before!=null){sql+=" AND m.id<?";args.add(before);}
        sql+=" ORDER BY m.id DESC LIMIT ?";args.add(limit+1);
        var rows=jdbc.query(sql,(rs,n)->new SearchHit(ROW.mapRow(rs,n),rs.getString("conversation_name")),args.toArray());
        boolean more=rows.size()>limit;
        var result=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new SearchPage(result,more,more?result.getLast().message().id():null);
    }

    public synchronized SearchContext searchContext(String room,long anchor,String direction,int limit) {
        if(anchor<=0||limit<1||limit>50||!Set.of("around","before","after").contains(direction))throw bad("消息定位参数无效");
        var target=jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id=?",ROW,room,anchor);
        if(target.isEmpty())throw failure(HttpStatus.NOT_FOUND,"原消息已删除或不属于当前会话");
        var result=new ArrayList<Message>();
        if(!direction.equals("after")){
            result.addAll(jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?",ROW,room,anchor,limit));
            Collections.reverse(result);
        }
        if(direction.equals("around"))result.add(target.getFirst());
        if(!direction.equals("before"))result.addAll(jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?",ROW,room,anchor,limit));
        long first=result.isEmpty()?anchor:result.getFirst().id(),last=result.isEmpty()?anchor:result.getLast().id();
        boolean older=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM device_message WHERE conversation_id=? AND id<?)",Boolean.class,room,first));
        boolean newer=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM device_message WHERE conversation_id=? AND id>?)",Boolean.class,room,last));
        return new SearchContext(List.copyOf(result),older,newer,historyRevision(room));
    }

    synchronized long historyRevision(String room) { return jdbc.queryForObject("SELECT history_revision FROM chat_conversation WHERE id=?",Long.class,room); }
    private void checkNotCleared(String device,String message) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_cleared_message WHERE device_id=? AND client_message_id=?",Integer.class,device,message)>0)
            throw failure(HttpStatus.CONFLICT,"此消息已随会话清空，不能重试旧消息；请重新发送");
    }
    synchronized List<ContextOperation> contextOperations(String room) {
        return jdbc.query("SELECT o.* FROM chat_context_operation o WHERE o.conversation_id=? AND NOT EXISTS (SELECT 1 FROM chat_context_operation newer WHERE newer.conversation_id=o.conversation_id AND newer.agent_id=o.agent_id AND (newer.created_at>o.created_at OR (newer.created_at=o.created_at AND newer.id>o.id))) ORDER BY o.created_at DESC,o.id DESC LIMIT 32",DeviceMessageStore::contextOperation,room);
    }
    static ContextOperation contextOperation(java.sql.ResultSet rs,int index) throws java.sql.SQLException {
        return new ContextOperation(rs.getString("id"),rs.getString("agent_id"),rs.getString("agent_name"),rs.getString("status"),rs.getString("error"),rs.getString("result"),rs.getLong("created_at"),rs.getString("kind"));
    }
    synchronized List<Message> archiveMessages(String room,long after,long through) {
        return jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND id>? AND id<=? ORDER BY id",ROW,room,after,through);
    }
    <T> T atomic(java.util.function.Supplier<T> action) { return transaction.execute(status->action.get()); }
    private static final String VISIBLE_MESSAGES="((message_role='user' AND id<=?) OR (message_role='assistant' AND (reply_to<? OR (reply_to IS NULL AND id<?))))";
    /** Anchors keep user/assistant pairs together even when later questions were queued first. */
    synchronized List<Long> contextAnchors(Work work,long after,long before,int limit,boolean newest) {
        return contextAnchors(work,after,before,limit,newest,false);
    }
    private List<Long> contextAnchors(Work work,long after,long before,int limit,boolean newest,boolean frozen){
        String sql="SELECT DISTINCT COALESCE(reply_to,id) turn_id FROM device_message WHERE conversation_id=? AND COALESCE(reply_to,id)>? AND COALESCE(reply_to,id)<? AND "+VISIBLE_MESSAGES;
        var args=new ArrayList<Object>(List.of(work.conversationId(),after,before,work.messageId(),work.messageId(),work.messageId()));
        if(frozen){sql+=" AND id<?";args.add(work.messageId());}
        sql+=" ORDER BY turn_id "+(newest?"DESC":"ASC")+" LIMIT ?";args.add(limit);
        return jdbc.queryForList(sql,Long.class,args.toArray());
    }
    record TurnPage(List<List<Message>> turns,boolean hasMore) {}
    /** Look ahead by one anchor id, then fetch just this page's message bodies. */
    synchronized TurnPage contextPage(Work work,long after,long before,int limit,boolean newest,boolean frozen){
        var anchors=contextAnchors(work,after,before,limit+1,newest,frozen);
        if(anchors.isEmpty())return new TurnPage(List.of(),false);
        long last=anchors.get(Math.min(limit,anchors.size())-1);
        var args=new ArrayList<Object>(List.of(work.conversationId(),Math.min(anchors.getFirst(),last),Math.max(anchors.getFirst(),last),work.messageId(),work.messageId(),work.messageId()));
        String sql="SELECT *,COALESCE(reply_to,id) anchor FROM device_message WHERE conversation_id=? AND COALESCE(reply_to,id) BETWEEN ? AND ? AND "+VISIBLE_MESSAGES;
        if(frozen){sql+=" AND id<?";args.add(work.messageId());}
        var groups=new LinkedHashMap<Long,List<Message>>();
        jdbc.query(sql+" ORDER BY anchor "+(newest?"DESC":"ASC")+",CASE WHEN message_role='user' THEN 0 ELSE 1 END,id",r->{
            groups.computeIfAbsent(r.getLong("anchor"),ignored->new ArrayList<>()).add(ROW.mapRow(r,0));
        },args.toArray());
        return new TurnPage(List.copyOf(groups.values()),anchors.size()>limit);
    }
    synchronized List<Message> contextTurn(Work work,long anchor) {
        return jdbc.query("SELECT * FROM device_message WHERE conversation_id=? AND COALESCE(reply_to,id)=? AND "+VISIBLE_MESSAGES+" ORDER BY CASE WHEN message_role='user' THEN 0 ELSE 1 END,id",
                ROW,work.conversationId(),anchor,work.messageId(),work.messageId(),work.messageId());
    }
    synchronized void phase(Work work,String phase) { jdbc.update("UPDATE chat_agent_job SET phase=? WHERE message_id=? AND agent_id=? AND status='RUNNING'",phase,work.messageId(),work.agentId()); }
    public synchronized void recoverInterrupted() { jdbc.update("UPDATE chat_agent_job SET status='FAILED',error='服务重启中断了回复，请确认后手动重试' WHERE status='RUNNING'"); }
    public synchronized Work claim() {
        var rows=jdbc.query("""
            SELECT j.* FROM chat_agent_job j WHERE j.status='PENDING' AND NOT EXISTS (
              SELECT 1 FROM chat_context_operation c WHERE c.conversation_id=j.conversation_id AND c.status IN ('PENDING','RUNNING')) AND NOT EXISTS (
              SELECT 1 FROM chat_agent_job p WHERE p.conversation_id=j.conversation_id AND p.status IN ('PENDING','RUNNING')
              AND (p.message_id<j.message_id OR p.message_id=j.message_id AND p.agent_id<j.agent_id OR p.status='RUNNING'))
            ORDER BY j.message_id,j.agent_id LIMIT 1
            """,(rs,n)->new Work(rs.getLong("message_id"),rs.getString("agent_id"),rs.getString("conversation_id"),rs.getString("user_id")));
        if(rows.isEmpty()) return null; Work work=rows.getFirst();
        if(jdbc.update("UPDATE chat_agent_job SET status='RUNNING',error='',phase='PREPARING' WHERE message_id=? AND agent_id=? AND status='PENDING'",work.messageId(),work.agentId())!=1) return null;
        return work;
    }
    public synchronized void complete(Work work,AgentRegistry.Agent agent,String text) {
        complete(work,agent,text,List.of());
    }
    synchronized boolean running(Work work){
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_job WHERE message_id=? AND agent_id=? AND status='RUNNING'",Integer.class,work.messageId(),work.agentId())==1;
    }
    synchronized void complete(Work work,AgentRegistry.Agent agent,String text,List<String> imageIds) {
        transaction.executeWithoutResult(status->{
            if(!running(work)) return;
            if(imageIds.size()>ChatImageService.MAX_PER_MESSAGE)throw bad("返回图片过多");
            for(String id:imageIds)if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_image WHERE id=? AND conversation_id=?",Integer.class,id,work.conversationId())!=1)throw bad("回复图片不属于当前会话");
            jdbc.update("INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id,message_role,agent_id,reply_to,image_ids,user_id) VALUES(?,?,?,?,?,?,?,'assistant',?,?,?,?)",UUID.randomUUID().toString(),agent.id(),agent.name(),"agent",text,System.currentTimeMillis(),work.conversationId(),agent.id(),work.messageId(),String.join(",",imageIds),work.userId());
            jdbc.update("UPDATE chat_agent_job SET status='SUCCEEDED',error='' WHERE message_id=? AND agent_id=?",work.messageId(),work.agentId());
        });
    }
    public synchronized void fail(Work work,String error) { jdbc.update("UPDATE chat_agent_job SET status='FAILED',error=? WHERE message_id=? AND agent_id=? AND status='RUNNING'",error,work.messageId(),work.agentId()); }
    public synchronized void retryJob(long messageId,String agentId) {
        var rooms=jdbc.queryForList("SELECT conversation_id FROM chat_agent_job WHERE message_id=? AND agent_id=?",String.class,messageId,agentId);
        if(!rooms.isEmpty() && jdbc.queryForObject("SELECT COUNT(*) FROM chat_context_policy WHERE conversation_id=? AND after_id>=?",Integer.class,rooms.getFirst(),messageId)>0)
            throw failure(HttpStatus.CONFLICT,"此问题属于上一话题，请在当前话题重新发送问题");
        Long queued=jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_job WHERE status IN ('PENDING','RUNNING')",Long.class);
        if(queued>=256) throw failure(HttpStatus.TOO_MANY_REQUESTS,"Agent 队列已满，请稍后重试");
        if(jdbc.update("UPDATE chat_agent_job SET status='PENDING',error='',phase='' WHERE message_id=? AND agent_id=? AND status='FAILED'",messageId,agentId)!=1) throw failure(HttpStatus.CONFLICT,"此回复未失败或已在重试");
    }
    private static List<String> split(String value) { return value==null||value.isBlank()?List.of():List.of(value.split(",")); }
    static String uuid(String value) {
        if(value==null||!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw bad("消息或设备标识必须为 UUID");
        return value.toLowerCase(Locale.ROOT);
    }
    static ResponseStatusException bad(String message) { return failure(HttpStatus.BAD_REQUEST,message); }
    static ResponseStatusException failure(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
