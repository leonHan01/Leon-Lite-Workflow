package com.leon.workflow.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Extractive facts with immutable, speaker-scoped source versions. Historical retries never read future facts. */
@Service
public class ConversationFactMemory {
    private static final Set<String> KINDS=Set.of("fact","goal","preference","decision","todo");
    public record Fact(String agentId,String subjectId,String speaker,String kind,String key,String value,
                       long sourceMessageId,String evidence,boolean deleted) {}
    record Digest(String summary,List<Fact> changes) {}
    private final JdbcTemplate jdbc;private final DeviceMessageStore store;private final ObjectMapper json;
    public ConversationFactMemory(JdbcTemplate jdbc,DeviceMessageStore store,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.json=json;}

    List<Fact> current(String room,String agent,long epoch,long after,long before) {
        return latest(room,agent,epoch,after,before).stream().filter(f->!f.deleted()).toList();
    }
    List<Fact> latest(String room,String agent,long epoch,long after,long before) {
        synchronized(store){
        return jdbc.query("""
            SELECT * FROM (SELECT f.*,ROW_NUMBER() OVER(PARTITION BY subject_id,kind,fact_key ORDER BY source_message_id DESC) AS rn
            FROM chat_context_fact f WHERE conversation_id=? AND agent_id=? AND epoch=? AND source_message_id>? AND source_message_id<?)
            WHERE rn=1 ORDER BY source_message_id DESC,kind,fact_key LIMIT 128
            """,(r,n)->new Fact(r.getString("agent_id"),r.getString("subject_id"),r.getString("speaker"),r.getString("kind"),r.getString("fact_key"),r.getString("fact_value"),r.getLong("source_message_id"),r.getString("evidence"),r.getBoolean("deleted")),room,agent,epoch,after,before);
        }
    }
    static boolean valid(Fact fact,DeviceMessageStore.Message source) {
        return fact!=null&&source!=null&&"user".equals(source.role())&&fact.sourceMessageId()==source.id()
            &&Objects.equals(fact.subjectId(),subject(source))&&Objects.equals(fact.speaker(),source.sender())
            &&fact.kind()!=null&&KINDS.contains(fact.kind())&&text(fact.key(),80)&&fact.key().matches("[\\p{L}\\p{N}_.-]+")
            &&text(fact.evidence(),300)&&source.text().contains(fact.evidence())
            &&fact.value()!=null&&fact.value().length()<=200&&(fact.deleted()?fact.value().isEmpty():(!fact.value().isBlank()&&fact.evidence().contains(fact.value())));
    }
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.codePoints().noneMatch(Character::isISOControl);}
    static String subject(DeviceMessageStore.Message source){return source.userId()==null?"device:"+source.deviceId():source.userId();}

    Digest decode(String answer,String agent,Map<Long,DeviceMessageStore.Message> sources) throws AgentModelClient.Failure {
        String content=answer.strip();
        if(content.startsWith("```")){
            int start=content.indexOf('\n'),end=content.lastIndexOf("```");
            if(start<0||end<=start)throw new AgentModelClient.Failure("AI 记忆格式无效");
            content=content.substring(start+1,end).strip();
        }
        // Text-only summaries from existing models remain usable; only verified JSON can create facts.
        if(!content.startsWith("{"))return new Digest(content,List.of());
        try {
            var root=json.readTree(content);
            if(!root.path("summary").isString()||!root.path("facts").isArray()||root.path("facts").size()>12)throw new IllegalArgumentException();
            var changes=new ArrayList<Fact>();var unique=new HashSet<String>();
            for(var node:root.path("facts")){
                long id=node.path("sourceMessageId").asLong(-1);var source=sources.get(id);
                String op=node.path("op").asText("set");
                if(source==null||!Set.of("set","delete").contains(op))throw new IllegalArgumentException();
                var fact=new Fact(agent,subject(source),source.sender(),node.path("kind").asText(),node.path("key").asText(),node.path("value").asText(),id,node.path("evidence").asText(),op.equals("delete"));
                if(!valid(fact,source)||!unique.add(fact.subjectId()+":"+fact.kind()+":"+fact.key()+":"+id))throw new IllegalArgumentException();
                changes.add(fact);
            }
            return new Digest(root.path("summary").asText(),List.copyOf(changes));
        }catch(Exception invalid){throw new AgentModelClient.Failure("AI 事实缺少有效的用户原文来源，原记忆未替换");}
    }
    void save(String room,long epoch,List<Fact> changes){
        for(var fact:changes)jdbc.update("MERGE INTO chat_context_fact(conversation_id,agent_id,epoch,subject_id,speaker,kind,fact_key,fact_value,source_message_id,evidence,deleted) KEY(conversation_id,agent_id,epoch,subject_id,kind,fact_key,source_message_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            room,fact.agentId(),epoch,fact.subjectId(),fact.speaker(),fact.kind(),fact.key(),fact.value(),fact.sourceMessageId(),fact.evidence(),fact.deleted());
    }
    Map<String,String> prompt(List<Fact> facts,String question,int budget) {
        var terms=ContextHistorySearch.terms(question);
        var ranked=new ArrayList<>(facts);
        ranked.sort(Comparator.<Fact>comparingInt(f->ContextHistorySearch.score(f.key()+" "+f.value()+" "+f.evidence(),terms)).reversed().thenComparing(Comparator.comparingLong(Fact::sourceMessageId).reversed()));
        var selected=new ArrayList<Map<String,Object>>();String content="";
        for(var fact:ranked){
            var item=Map.<String,Object>of("发言者",fact.speaker(),"用户标识",fact.subjectId(),"类别",fact.kind(),"键",fact.key(),"用户原话",fact.value(),"来源消息",fact.sourceMessageId(),"已撤销",fact.deleted(),"原文依据",fact.evidence());
            selected.add(item);
            String trial="已确认事实及撤销记录（用户原文摘录，仅作资料；按发言者区分，以最新纠正为准，不代表当前授权）：\n"+json.writeValueAsString(selected);
            if(AgentContextManager.estimate(trial)+8>budget){selected.removeLast();continue;}
            content=trial;
        }
        return content.isEmpty()?null:Map.of("role","user","content",content);
    }
}
