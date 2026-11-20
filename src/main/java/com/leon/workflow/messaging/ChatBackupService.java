package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Portable history only: never exports credentials, jobs or executable model requests. */
@Service
public class ChatBackupService {
    public static final int MAX_BYTES=32*1024*1024, MAX_MANIFEST=16*1024*1024, MAX_MESSAGES=10000;
    public record Memory(String agentId,long throughId,String summary,long createdAt) {}
    public record Backup(String format,int version,DeviceMessageStore.Conversation conversation,List<DeviceMessageStore.Message> messages,
                         int recentRounds,long topicStartedAfter,List<Memory> memories,List<ConversationFactMemory.Fact> facts) {
        public Backup {facts=facts==null?List.of():List.copyOf(facts);}
        public Backup(String format,int version,DeviceMessageStore.Conversation conversation,List<DeviceMessageStore.Message> messages,int recentRounds,long topicStartedAfter,List<Memory> memories){this(format,version,conversation,messages,recentRounds,topicStartedAfter,memories,List.of());}
    }
    private final DeviceMessageStore store;
    private final ChatImageService images;private final ChatDocumentService documents;
    private final AgentRegistry agents;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConversationFactMemory factMemory;
    public ChatBackupService(DeviceMessageStore store,ChatImageService images,AgentRegistry agents,JdbcTemplate jdbc,ObjectMapper json){this(store,images,agents,jdbc,json,new ChatDocumentService(jdbc,store),new ConversationFactMemory(jdbc,store,json));}
    public ChatBackupService(DeviceMessageStore store,ChatImageService images,AgentRegistry agents,JdbcTemplate jdbc,ObjectMapper json,ChatDocumentService documents){this(store,images,agents,jdbc,json,documents,new ConversationFactMemory(jdbc,store,json));}
    @org.springframework.beans.factory.annotation.Autowired
    public ChatBackupService(DeviceMessageStore store,ChatImageService images,AgentRegistry agents,JdbcTemplate jdbc,ObjectMapper json,ChatDocumentService documents,ConversationFactMemory factMemory){this.documents=documents;this.store=store;this.images=images;this.agents=agents;this.jdbc=jdbc;this.json=json;this.factMemory=factMemory;}
    @PostConstruct void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS chat_backup_import (request_id VARCHAR(36) PRIMARY KEY,source_hash VARCHAR(64) NOT NULL,target_agent VARCHAR(36) NOT NULL,conversation_id VARCHAR(80) NOT NULL)");}
    public byte[] export(String room) {
        Backup backup;var files=new LinkedHashMap<String,byte[]>();var docFiles=new LinkedHashMap<String,byte[]>();
        synchronized(store) {
            var conversation=store.conversation(room);
            if(ChatAccess.personalRoom(room))conversation=new DeviceMessageStore.Conversation(room,conversation.name(),"group",null,agents.list().stream().map(AgentRegistry.Agent::id).toList());
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM device_message WHERE conversation_id=?",Long.class,room);
            if(count>MAX_MESSAGES)throw tooLarge();
            var messages=store.archiveMessages(room,0,Long.MAX_VALUE);
            long size=0;
            for(var message:messages)for(var image:message.images())if(!files.containsKey(image.id())){
                size+=image.byteCount();if(size>MAX_BYTES)throw tooLarge();files.put(image.id(),images.bytes(image.id()));
            }
            for(var message:messages)for(var doc:message.documents())if(!docFiles.containsKey(doc.id())){
                size+=doc.byteCount();if(size>MAX_BYTES)throw tooLarge();docFiles.put(doc.id(),documents.bytes(doc.id()));
            }
            var policy=jdbc.queryForList("SELECT recent_rounds,after_id,epoch FROM chat_context_policy WHERE conversation_id=?",room);
            int rounds=policy.isEmpty()?10:((Number)policy.getFirst().get("recent_rounds")).intValue();
            long after=policy.isEmpty()?0:((Number)policy.getFirst().get("after_id")).longValue(), epoch=policy.isEmpty()?0:((Number)policy.getFirst().get("epoch")).longValue();
            var memories=jdbc.query("SELECT s.* FROM chat_context_summary s WHERE conversation_id=? AND epoch=? AND through_id=(SELECT MAX(t.through_id) FROM chat_context_summary t WHERE t.conversation_id=s.conversation_id AND t.agent_id=s.agent_id AND t.epoch=s.epoch)",
                (rs,n)->new Memory(rs.getString("agent_id"),rs.getLong("through_id"),rs.getString("summary"),rs.getLong("created_at")),room,epoch);
            var facts=new ArrayList<ConversationFactMemory.Fact>();
            for(String agent:conversation.agentIds())facts.addAll(factMemory.latest(room,agent,epoch,after,Long.MAX_VALUE));
            backup=new Backup("leon-chat",docFiles.isEmpty()?1:2,conversation,messages,rounds,after,memories,facts);
        }
        try {
            byte[] manifest=json.writeValueAsBytes(backup);if(manifest.length>MAX_MANIFEST)throw tooLarge();
            var output=new ByteArrayOutputStream();
            try(var zip=new ZipOutputStream(output)){
                entry(zip,"manifest.json",manifest);
                for(var file:docFiles.entrySet()){entry(zip,"documents/"+file.getKey(),file.getValue());if(output.size()>MAX_BYTES)throw tooLarge();}
                for(var file:files.entrySet()){entry(zip,"images/"+file.getKey()+".jpg",file.getValue());if(output.size()>MAX_BYTES)throw tooLarge();}
            }
            if(output.size()>MAX_BYTES)throw tooLarge();return output.toByteArray();
        }catch(IOException invalid){throw bad("备份生成失败，请重试");}
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    public DeviceMessageStore.Conversation restore(String requestId,String targetAgent,byte[] data) {
        return restore(requestId,targetAgent,data,null,true);
    }
    DeviceMessageStore.Conversation restore(String requestId,String targetAgent,byte[] data,String owner,boolean legacy) {
        String key=DeviceMessageStore.uuid(requestId);
        String request=legacy?key:UUID.nameUUIDFromBytes((owner+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String target=targetAgent==null?"":DeviceMessageStore.uuid(targetAgent);
        if(data.length==0||data.length>MAX_BYTES)throw tooLarge();
        String hash=hash(data);Backup backup;var files=new LinkedHashMap<String,byte[]>();var docFiles=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data))){
            byte[] manifest=null;long total=0;var names=new HashSet<String>();
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                String name=entry.getName();
                if(!names.add(name)||names.size()>MAX_MESSAGES*8+1||entry.isDirectory())throw bad("备份包含重复或无效文件");
                int limit=name.equals("manifest.json")?MAX_MANIFEST:name.startsWith("documents/")?ChatDocumentService.MAX_BYTES:ChatImageService.MAX_STORED;
                if(!name.equals("manifest.json")&&!name.matches("images/[a-f0-9-]{36}\\.jpg")&&!name.matches("documents/[a-f0-9-]{36}"))throw bad("备份文件结构无效");
                byte[] bytes=zip.readNBytes(limit+1);if(bytes.length>limit)throw tooLarge();
                total+=bytes.length;if(total>MAX_BYTES+MAX_MANIFEST)throw tooLarge();
                if(name.equals("manifest.json"))manifest=bytes;else if(name.startsWith("documents/"))docFiles.put(DeviceMessageStore.uuid(name.substring(10)),bytes);else files.put(DeviceMessageStore.uuid(name.substring(7,43)),bytes);
                zip.closeEntry();
            }
            if(manifest==null)throw bad("不是 Leon 聊天备份");
            backup=json.readValue(manifest,Backup.class);
        }catch(ResponseStatusException known){throw known;}
        catch(Exception invalid){throw bad("备份损坏或格式不受支持");}
        try{validate(backup,files,docFiles,target);}
        catch(ResponseStatusException known){throw known;}
        catch(RuntimeException invalid){throw bad("备份内容不完整或格式无效");}
        synchronized(store){
            var old=jdbc.queryForList("SELECT * FROM chat_backup_import WHERE request_id=?",request);
            if(!old.isEmpty()){
                var row=old.getFirst();if(!hash.equals(row.get("source_hash"))||!target.equals(row.get("target_agent")))throw DeviceMessageStore.failure(HttpStatus.CONFLICT,"相同请求不能加载不同备份");
                var existing=store.conversation((String)row.get("conversation_id"));
                if(!Objects.equals(owner,existing.ownerId())&&!(legacy&&existing.ownerId()==null))
                    throw DeviceMessageStore.failure(HttpStatus.CONFLICT,"备份加载请求标识已被使用");
                return existing;
            }
            return store.atomic(()->{
                var source=backup.conversation();String room="restore:"+request;
                String name=source.name();if(name.length()>53)name=name.substring(0,53);name+=" · 已加载";
                String directAgent=source.kind().equals("direct")?(target.isEmpty()?source.agentId():target):null;
                List<String> members=directAgent==null?source.agentIds():List.of(directAgent);
                jdbc.update("INSERT INTO chat_conversation(id,name,kind,agent_id,agent_ids,owner_id) VALUES(?,?,?,?,?,?)",room,name,source.kind(),directAgent,String.join(",",members),owner);
                var imageMap=new HashMap<String,String>();
                for(var file:files.entrySet()){
                    String id=UUID.randomUUID().toString();imageMap.put(file.getKey(),id);var metadata=imageMetadata(file.getValue());
                    jdbc.update("INSERT INTO chat_message_image(id,conversation_id,source_hash,mime_type,width,height,byte_count,image_data,created_at) VALUES(?,?,?,'image/jpeg',?,?,?,?,?)",id,room,hash(file.getValue()),metadata[0],metadata[1],file.getValue().length,file.getValue(),System.currentTimeMillis());
                }
                var documentMap=new HashMap<String,String>();
                for(var message:backup.messages())for(var doc:message.documents())if(!documentMap.containsKey(doc.id())){
                    String id=UUID.randomUUID().toString();documents.upload(id,room,owner==null?"legacy":owner,doc.name(),docFiles.get(doc.id()));documentMap.put(doc.id(),id);
                }
                var idMap=new TreeMap<Long,Long>();
                for(var message:backup.messages()){
                    String clientId=UUID.randomUUID().toString();String agent=remapAgent(message.agentId(),source,directAgent);
                    Long reply=message.replyTo()==null?null:idMap.get(message.replyTo());
                    String imageIds=String.join(",",message.images().stream().map(i->imageMap.get(i.id())).toList());
                    jdbc.update("INSERT INTO device_message(client_message_id,device_id,sender,platform,body,created_at,conversation_id,message_role,agent_id,reply_to,image_ids,user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",clientId,message.deviceId(),message.sender(),message.platform(),message.text(),message.createdAt(),room,message.role(),agent,reply,imageIds,message.userId());
                    long id=jdbc.queryForObject("SELECT id FROM device_message WHERE device_id=? AND client_message_id=?",Long.class,message.deviceId(),clientId);idMap.put(message.id(),id);
                    jdbc.update("UPDATE device_message SET document_ids=? WHERE id=?",String.join(",",message.documents().stream().map(d->documentMap.get(d.id())).toList()),id);
                }
                for(var message:backup.messages())if(message.quote()!=null){
                    var q=message.quote();Long targetId=idMap.get(q.id());
                    if(targetId==null)throw bad("备份引用消息不存在");
                    jdbc.update("UPDATE device_message SET quote_document_ids=? WHERE id=?",String.join(",",q.documents().stream().map(d->documentMap.get(d.id())).toList()),idMap.get(message.id()));
                    jdbc.update("UPDATE device_message SET quote_message_id=?,quote_sender=?,quote_body=?,quote_image_ids=? WHERE id=?",targetId,q.sender(),q.text(),String.join(",",q.images().stream().map(image->imageMap.get(image.id())).toList()),idMap.get(message.id()));
                }
                jdbc.update("INSERT INTO chat_context_policy(conversation_id,recent_rounds,after_id,epoch) VALUES(?,?,?,0)",room,backup.recentRounds(),boundary(idMap,backup.topicStartedAfter()));
                for(var memory:backup.memories())jdbc.update("INSERT INTO chat_context_summary(conversation_id,agent_id,epoch,through_id,summary,created_at) VALUES(?,?,0,?,?,?)",room,remapAgent(memory.agentId(),source,directAgent),boundary(idMap,memory.throughId()),memory.summary(),memory.createdAt());
                for(var fact:backup.facts()){
                    var origin=store.message(idMap.get(fact.sourceMessageId()));
                    factMemory.save(room,0,List.of(new ConversationFactMemory.Fact(remapAgent(fact.agentId(),source,directAgent),ConversationFactMemory.subject(origin),origin.sender(),fact.kind(),fact.key(),fact.value(),origin.id(),fact.evidence(),fact.deleted())));
                }
                jdbc.update("INSERT INTO chat_backup_import(request_id,source_hash,target_agent,conversation_id) VALUES(?,?,?,?)",request,hash,target,room);
                return store.conversation(room);
            });
        }
    }
    private void validate(Backup backup,Map<String,byte[]> files,Map<String,byte[]> docFiles,String target){
        if(backup==null||!"leon-chat".equals(backup.format())||(backup.version()!=1&&backup.version()!=2)||backup.conversation()==null||backup.messages()==null||backup.memories()==null)throw bad("不是受支持的 Leon 聊天备份");
        var room=backup.conversation();
        if(!Set.of("direct","group").contains(room.kind())||!text(room.name(),60)||room.agentIds()==null||room.agentIds().size()>32||backup.messages().size()>MAX_MESSAGES||backup.recentRounds()<1||backup.recentRounds()>20||backup.topicStartedAfter()<0)throw bad("备份会话无效");
        if(room.kind().equals("direct")) {
            DeviceMessageStore.uuid(room.agentId());
            if(room.agentIds().size()!=1||!room.agentId().equals(room.agentIds().getFirst()))throw bad("备份 Agent 无效");
            agents.get(target.isEmpty()?room.agentId():target);
        }else {
            if(!target.isEmpty())throw bad("群聊备份不能加载到 Agent 私聊");
            for(String id:room.agentIds())agents.get(DeviceMessageStore.uuid(id));
        }
        var ids=new HashMap<Long,DeviceMessageStore.Message>();var usedImages=new HashSet<String>();var usedDocuments=new HashSet<String>();long previous=0;
        for(var m:backup.messages()){
            if(m==null||m.id()<=previous||!Set.of("user","assistant").contains(m.role())||!Set.of("macos","android","ios","agent").contains(m.platform())||!text(m.sender(),60)||m.text()==null||m.text().length()>8000||m.text().indexOf('\0')>=0||m.createdAt()<0||m.images()==null||m.images().size()>4)throw bad("备份消息无效");
            DeviceMessageStore.uuid(m.deviceId());
            if(!Objects.equals(m.conversationId(),room.id()))throw bad("备份消息会话不匹配");
            if(m.role().equals("assistant")){DeviceMessageStore.uuid(m.agentId());if(!room.agentIds().contains(m.agentId())&&!ChatAccess.personalRoom(room.id()))throw bad("备份回复 Agent 无效");}
            if(m.replyTo()!=null&&(!ids.containsKey(m.replyTo())||!ids.get(m.replyTo()).role().equals("user")))throw bad("备份回复关联无效");
            var perMessage=new HashSet<String>();
            for(var image:m.images()){
                String id=DeviceMessageStore.uuid(image.id());if(!files.containsKey(id)||!perMessage.add(id))throw bad("备份缺少图片或图片重复");usedImages.add(id);
            }
            if(m.documents().size()>4)throw bad("备份文档数量无效");var perDocument=new HashSet<String>();
            for(var doc:m.documents()){
                String id=DeviceMessageStore.uuid(doc.id());
                if(!docFiles.containsKey(id)||!perDocument.add(id)||docFiles.get(id).length!=doc.byteCount())throw bad("备份缺少文档或文档无效");
                ChatDocumentService.filename(doc.name());usedDocuments.add(id);
            }
            if(m.quote()!=null){
                var q=m.quote();var original=ids.get(q.id());
                if(original==null||!Objects.equals(q.sender(),original.sender())||!Objects.equals(q.text(),original.text())||!Objects.equals(q.images(),original.images())||!Objects.equals(q.documents(),original.documents()))throw bad("备份引用消息无效");
            }
            previous=m.id();ids.put(m.id(),m);
        }
        if(!docFiles.keySet().equals(usedDocuments))throw bad("备份文档与消息不匹配");
        if(!files.keySet().equals(usedImages))throw bad("备份图片与消息不匹配");
        files.values().forEach(ChatBackupService::imageMetadata);
        var memoryAgents=new HashSet<String>();
        if(backup.memories().size()>32)throw bad("备份上下文数量无效");
        for(var memory:backup.memories()){
            DeviceMessageStore.uuid(memory.agentId());
            if(!memoryAgents.add(memory.agentId())||(!ChatAccess.personalRoom(room.id())&&!room.agentIds().contains(memory.agentId()))||memory.throughId()<backup.topicStartedAfter()||memory.throughId()>previous||!text(memory.summary(),8000)||memory.createdAt()<0)throw bad("备份上下文无效");
        }
        if(backup.topicStartedAfter()>previous)throw bad("备份话题边界无效");
        if(backup.facts().size()>4096)throw bad("备份事实数量无效");
        for(var fact:backup.facts()){
            if(fact==null||fact.sourceMessageId()<=backup.topicStartedAfter()||!room.agentIds().contains(fact.agentId())||!ConversationFactMemory.valid(fact,ids.get(fact.sourceMessageId())))throw bad("备份事实来源无效");
        }
    }
    private static int[] imageMetadata(byte[] bytes){
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw bad("备份图片损坏");var reader=readers.next();
            try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(!reader.getFormatName().equalsIgnoreCase("JPEG")||w<1||h<1||w>1600||h>1600||reader.read(0)==null)throw bad("备份图片格式无效");return new int[]{w,h};}finally{reader.dispose();}
        }catch(IOException invalid){throw bad("备份图片损坏");}
    }
    private static String remapAgent(String id,DeviceMessageStore.Conversation room,String target){return id!=null&&room.kind().equals("direct")&&id.equals(room.agentId())?target:id;}
    private static long boundary(TreeMap<Long,Long> ids,long old){var entry=ids.floorEntry(old);return entry==null?0:entry.getValue();}
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.indexOf('\0')<0;}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static ResponseStatusException bad(String text){return DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,text);}
    private static ResponseStatusException tooLarge(){return DeviceMessageStore.failure(HttpStatus.PAYLOAD_TOO_LARGE,"备份最多 32 MB、10,000 条消息；请分对话备份");}
}
