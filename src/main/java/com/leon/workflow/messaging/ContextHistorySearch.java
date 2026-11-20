package com.leon.workflow.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded lexical retrieval; all candidates are scoped to the room, topic and original question boundary. */
@Service
public class ContextHistorySearch {
    private final JdbcTemplate jdbc;
    private static final Pattern WORDS=Pattern.compile("[a-zA-Z0-9_]{2,}|[\\p{IsHan}]+");
    private static final Set<String> STOP=Set.of("之前","上次","什么","那个","这个","请问","帮我","我们","时候","记得","一下","说的","次说","question","the","that","what","please","with","this");
    public ContextHistorySearch(JdbcTemplate jdbc){this.jdbc=jdbc;}
    static List<String> terms(String text){
        var terms=new LinkedHashSet<String>();var matcher=WORDS.matcher(text==null?"":text.toLowerCase(Locale.ROOT));
        while(matcher.find()){
            String word=matcher.group();
            if(word.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)){
                if(word.length()<=6&&word.length()>1&&!STOP.contains(word))terms.add(word);
                for(int i=0;i+1<word.length()&&terms.size()<24;i++){String pair=word.substring(i,i+2);if(!STOP.contains(pair))terms.add(pair);}
            }else if(!STOP.contains(word))terms.add(word);
            if(terms.size()>=24)break;
        }
        return terms.stream().limit(24).toList();
    }
    static int score(String text,List<String> terms){String lower=text.toLowerCase(Locale.ROOT);return terms.stream().filter(lower::contains).mapToInt(s->s.length()*s.length()).sum();}
    List<Long> find(DeviceMessageStore.Work work,long after,Set<Long> excluded,String question){
        var terms=terms(question);if(terms.isEmpty())return List.of();
        var args=new ArrayList<Object>(List.of(work.conversationId(),after,work.messageId(),work.messageId()));
        var filters=new ArrayList<String>();
        for(String term:terms){filters.add("LOWER(body) LIKE ? ESCAPE '!'");args.add("%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        var rows=jdbc.query("SELECT COALESCE(reply_to,id) AS anchor,body FROM device_message WHERE conversation_id=? AND COALESCE(reply_to,id)>? AND COALESCE(reply_to,id)<? AND id<? AND ("+String.join(" OR ",filters)+") ORDER BY id DESC LIMIT 256",
            (r,n)->Map.entry(r.getLong("anchor"),score(r.getString("body"),terms)),args.toArray());
        var scores=new HashMap<Long,Integer>();
        for(var row:rows)if(!excluded.contains(row.getKey()))scores.merge(row.getKey(),row.getValue(),Math::max);
        return scores.entrySet().stream().sorted(Map.Entry.<Long,Integer>comparingByValue().reversed().thenComparing(Map.Entry.<Long,Integer>comparingByKey().reversed())).limit(8).map(Map.Entry::getKey).toList();
    }
}
