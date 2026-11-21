package com.leon.workflow.messaging;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Converts provider citation annotations to ordinary Markdown, persisted with the reply. */
final class OpenAiWebCitations {
    private static final int MAX_SOURCES=24;
    private static final Pattern MARKER=Pattern.compile("\uE200cite\uE202[^\uE201]*\uE201");
    private final Map<String,Source> sources=new LinkedHashMap<>();
    private record Source(int number,String title,String url) {
        String link(){return "[来源"+number+"]("+url+")";}
    }
    private record Citation(int position,Source source) {}

    String render(JsonNode part) {
        String raw=part.path("text").asText("");
        var citations=new ArrayList<Citation>();
        int count=raw.codePointCount(0,raw.length());
        for(var annotation:part.path("annotations")){
            if(!"url_citation".equals(annotation.path("type").asText()))continue;
            String url=safeUrl(annotation.path("url").asText(""));
            if(url==null)continue;
            Source source=sources.get(url);
            if(source==null){
                if(sources.size()>=MAX_SOURCES)throw new IllegalArgumentException("联网搜索来源过多，请缩小问题范围后重试");
                String title=annotation.path("title").asText("").strip();
                source=new Source(sources.size()+1,title.isBlank()?URI.create(url).getHost():title,url);
                sources.put(url,source);
            }
            int start=annotation.path("start_index").asInt(-1),end=annotation.path("end_index").asInt(-1);
            // Indices refer to characters in this content part, before any Markdown is inserted.
            int position=start>=0&&end>=start&&end<=count?raw.offsetByCodePoints(0,end):raw.length();
            // Never split a provider marker, even when an annotation uses a different index convention.
            var markers=MARKER.matcher(raw);
            while(markers.find())if(position>markers.start()&&position<markers.end()){position=markers.end();break;}
            var citation=new Citation(position,source);
            if(!citations.contains(citation))citations.add(citation);
        }
        citations.sort(Comparator.comparingInt(Citation::position));
        var text=new StringBuilder();int cursor=0;
        for(var citation:citations){
            text.append(clean(raw.substring(cursor,citation.position()))).append(' ').append(citation.source().link());
            cursor=citation.position();
        }
        return text.append(clean(raw.substring(cursor))).toString();
    }

    boolean isEmpty(){return sources.isEmpty();}

    String sources(){
        if(sources.isEmpty())return "";
        var text=new StringBuilder("\n\n来源：\n");
        for(var source:sources.values())text.append("\n- [").append(source.number()).append(". ")
                .append(label(source.title())).append("](").append(source.url()).append(")");
        return text.toString();
    }

    private static String clean(String text){return MARKER.matcher(text).replaceAll("");}

    private static String safeUrl(String value){
        if(value.length()>2048||value.codePoints().anyMatch(Character::isISOControl))return null;
        try{
            var uri=URI.create(value);
            String scheme=uri.getScheme();
            if(scheme==null||!Set.of("http","https").contains(scheme.toLowerCase(Locale.ROOT))
                    ||uri.getHost()==null||uri.getRawUserInfo()!=null)return null;
            return uri.toASCIIString().replace("(","%28").replace(")","%29");
        }catch(IllegalArgumentException invalid){return null;}
    }

    private static String label(String title){
        var text=new StringBuilder();
        title.codePoints().limit(160).forEach(c->{
            if(Character.isWhitespace(c)||Character.isISOControl(c))text.append(' ');
            else if(c=='<')text.append("&lt;");
            else if(c=='>')text.append("&gt;");
            else if(c=='&')text.append("&amp;");
            else {if("\\`*_[]()!".indexOf(c)>=0)text.append('\\');text.appendCodePoint(c);}
        });
        return text.toString();
    }
}
