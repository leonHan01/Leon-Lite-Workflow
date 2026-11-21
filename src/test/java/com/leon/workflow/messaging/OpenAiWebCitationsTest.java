package com.leon.workflow.messaging;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiWebCitationsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Map<String,Object> citation(String url,String title,int start,int end){
        return Map.of("type","url_citation","url",url,"title",title,"start_index",start,"end_index",end);
    }
    @Test void preservesChineseEmojiAndClaimTextWithClickableInlineCitations(){
        var citations=new OpenAiWebCitations();
        String text="😀 最新版本已发布。下一句。";
        int end="😀 最新版本已发布。".codePointCount(0,"😀 最新版本已发布。".length());
        String rendered=citations.render(json.valueToTree(Map.of("text",text,"annotations",List.of(citation("https://example.test/release","官方发布",2,end)))));
        assertEquals("😀 最新版本已发布。 [来源1](https://example.test/release)下一句。",rendered);
        assertTrue(citations.sources().contains("[1. 官方发布](https://example.test/release)"));
    }
    @Test void deduplicatesSourcesAcrossPartsAndRemovesInternalProviderMarkers(){
        var citations=new OpenAiWebCitations();String marker="\uE200cite\uE202turn0search0\uE201";
        String text="资料。"+marker;
        var annotation=citation("https://example.test/doc","来源",3,text.length());
        String rendered=citations.render(json.valueToTree(Map.of("text",text,"annotations",List.of(annotation,annotation))));
        assertEquals("资料。 [来源1](https://example.test/doc)",rendered);
        assertEquals("补充 [来源1](https://example.test/doc)",citations.render(json.valueToTree(Map.of("text","补充","annotations",List.of(annotation)))));
        assertEquals(1,citations.sources().lines().filter(line->line.startsWith("- ")).count());
    }
    @Test void invalidOffsetsKeepTheWholeAnswerAndStillExposeSources(){
        var citations=new OpenAiWebCitations();
        String rendered=citations.render(json.valueToTree(Map.of("text","完整回答","annotations",List.of(citation("https://example.test/a","来源",-1,999)))));
        assertEquals("完整回答 [来源1](https://example.test/a)",rendered);
    }
    @Test void escapesUntrustedTitlesAndParenthesesWithoutCreatingExtraLinks(){
        var citations=new OpenAiWebCitations();
        String rendered=citations.render(json.valueToTree(Map.of("text","回答","annotations",List.of(citation("https://example.test/a(b)?x=1&y=2","[恶意](javascript:alert(1))\n<img src=x>",0,2)))));
        assertTrue(rendered.contains("https://example.test/a%28b%29?x=1&y=2"));
        assertTrue(citations.sources().contains("\\[恶意\\]\\(javascript:alert\\(1\\)\\) &lt;img src=x&gt;"));
        assertFalse(citations.sources().contains("\n<img"));
    }
    @Test void rejectsExecutableCredentialedAndMalformedUrls(){
        for(String url:List.of("javascript:alert(1)","file:///etc/passwd","data:text/html,evil","https://user:secret@example.test/","https://example.test/\nfoo","https://example.test/a b","https:///missing-host")){
            var citations=new OpenAiWebCitations();
            assertEquals("回答",citations.render(json.valueToTree(Map.of("text","回答","annotations",List.of(citation(url,"bad",0,2))))));
            assertTrue(citations.isEmpty());assertEquals("",citations.sources());
        }
    }
}
