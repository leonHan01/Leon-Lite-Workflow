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
    // A separator belongs to an identifier only when it joins two ASCII components.
    private static final Pattern CODE=Pattern.compile("[a-z0-9]+(?:[-_./][a-z0-9]+)*",Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_LABEL=Pattern.compile("(?:编号|订单号|合同号|单号|序列号|代码|代号|\\b(?:id|code|sku|ref))\\s*[:：#]?\\s*$",Pattern.CASE_INSENSITIVE);
    private static String identifierPattern(String question){
        var codes=new LinkedHashSet<String>();
        var matcher=CODE.matcher(question==null?"":question);
        while(matcher.find() && codes.size()<24){
            String code=matcher.group();
            boolean labelled=CODE_LABEL.matcher(question).region(Math.max(0,matcher.start()-20),matcher.start()).find();
            if(code.length()>=2 && (labelled||code.chars().anyMatch(Character::isDigit)))
                codes.add(Pattern.quote(code.toLowerCase(Locale.ROOT)));
        }
        if(codes.isEmpty())return "";
        return "(?<![a-z0-9])(?<![a-z0-9][-_./])(?:"
            +String.join("|",codes)+")(?![a-z0-9]|[-_./][a-z0-9])";
    }
    private record Hit(boolean exact,int lexical) implements Comparable<Hit>{
        public int compareTo(Hit other){
            int priority=Boolean.compare(exact,other.exact);
            return priority!=0?priority:Integer.compare(lexical,other.lexical);
        }
    }
    List<Long> find(DeviceMessageStore.Work work,long after,Set<Long> excluded,String question,ConversationFactMemory.Recall recall){
        var terms=terms(question);
        String pattern=identifierPattern(question);
        if(terms.isEmpty()&&pattern.isEmpty())return List.of();
        var args=new ArrayList<Object>();
        String exact="FALSE";
        if(!pattern.isEmpty()){exact="REGEXP_LIKE(LOWER(body),?)";args.add(pattern);}
        String scope="conversation_id=? AND COALESCE(reply_to,id)>? AND COALESCE(reply_to,id)<? AND id<?";
        args.addAll(List.of(work.conversationId(),after,work.messageId(),work.messageId()));
        if(!excluded.isEmpty()){
            scope+=" AND COALESCE(reply_to,id) NOT IN ("+String.join(",",Collections.nCopies(excluded.size(),"?"))+")";
            args.addAll(excluded);
        }
        var filters=new ArrayList<String>();filters.add("exact_hit");
        for(String term:terms){filters.add("LOWER(body) LIKE ? ESCAPE '!'");args.add("%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        // Apply exact priority in SQL before the candidate limit, and keep it when ranking turns.
        String sql="SELECT anchor,body,exact_hit FROM (SELECT COALESCE(reply_to,id) anchor,id,body,"+exact+
            " AS exact_hit FROM device_message WHERE "+scope+") WHERE "+String.join(" OR ",filters)+
            " ORDER BY exact_hit DESC,id DESC LIMIT 256";
        var scores=new HashMap<Long,Hit>();
        jdbc.query(sql,row->{
            Hit hit=new Hit(row.getBoolean("exact_hit"),score(row.getString("body"),terms));
            for(long anchor:recall.eligibleAnchors(row.getLong("anchor"))){
                if(!excluded.contains(anchor))scores.merge(anchor,hit,(a,b)->a.compareTo(b)>=0?a:b);
            }
        },args.toArray());
        return scores.entrySet().stream().sorted(Map.Entry.<Long,Hit>comparingByValue().reversed()
            .thenComparing(Map.Entry.<Long,Hit>comparingByKey().reversed())).limit(8).map(Map.Entry::getKey).toList();
    }
}
