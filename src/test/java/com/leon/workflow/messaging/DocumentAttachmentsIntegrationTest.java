package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.*;
import java.nio.charset.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=HelloServiceApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:document-attachments;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.workflows.scheduling-enabled=false","leon.agents.dispatch-enabled=false","leon.chat.auto-title-enabled=false",
    "leon.auth.required=true","leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class DocumentAttachmentsIntegrationTest {
    @Autowired WebApplicationContext context;@Autowired ObjectMapper json;@Autowired DeviceMessageStore store;
    @Autowired ChatDocumentService documents;@Autowired ChatBackupService backups;
    MockMvc mvc;
    record User(String id,String token){String room(){return "home:"+id;}}
    @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).build();}
    User user()throws Exception{
        var bytes=mvc.perform(post("/api/conversations/account/login").header("Authorization","Bearer integration-test-token").contentType("application/json").content(json.writeValueAsString(Map.of("username","Docs"+UUID.randomUUID().toString().substring(0,8))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var result=json.readTree(bytes);return new User(result.path("user").path("id").asText(),"Bearer "+result.path("sessionToken").asText());
    }
    String upload(User user,String name,byte[] data)throws Exception{
        String id=UUID.randomUUID().toString();mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",id).param("conversationId",user.room()).param("name",name).contentType("application/octet-stream").content(data)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));return id;
    }
    Map<String,Object> body(User user,List<String> docs){var body=new HashMap<String,Object>();body.put("clientMessageId",UUID.randomUUID().toString());body.put("deviceId",UUID.randomUUID().toString());body.put("sender","client");body.put("platform","ios");body.put("conversationId",user.room());body.put("text","");body.put("documentIds",docs);return body;}
    long send(User user,Map<String,Object> body)throws Exception{return json.readTree(mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asLong();}
    @Test void extractsAllTextFormatsAndChineseEncodings(){
        for(String ext:List.of("txt","md","markdown","csv"))assertTrue(ChatDocumentService.extract(ext,"月份,营收\n十一月,123".getBytes(StandardCharsets.UTF_8)).contains("营收"));
        assertEquals("中文文档",ChatDocumentService.extract("txt","中文文档".getBytes(Charset.forName("GB18030"))));
        assertEquals("中文文档",ChatDocumentService.extract("txt","中文文档".getBytes(StandardCharsets.UTF_16)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ChatDocumentService.extract("txt",new byte[]{0,1,2}));
    }
    @Test void extractsPdfDocxAndBothExcelFormatsWithoutEvaluatingFormulas()throws Exception{
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);try(var canvas=new PDPageContentStream(pdf,page)){canvas.beginText();canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);canvas.newLineAtOffset(40,700);canvas.showText("Profit 2026: 1234");canvas.endText();}pdf.save(out);assertTrue(ChatDocumentService.extract("pdf",out.toByteArray()).contains("Profit 2026"));
        }
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("季度营收报告");doc.write(out);assertTrue(ChatDocumentService.extract("docx",out.toByteArray()).contains("季度营收报告"));}
        for(boolean modern:List.of(false,true))try(var book=modern?new XSSFWorkbook():new HSSFWorkbook();var out=new ByteArrayOutputStream()){
            var row=book.createSheet("营业收入").createRow(0);row.createCell(0).setCellValue("十一月");row.createCell(1).setCellValue(1234);row.createCell(2).setCellFormula("1+1");book.write(out);
            String text=ChatDocumentService.extract(modern?"xlsx":"xls",out.toByteArray());assertTrue(text.contains("营业收入"));assertTrue(text.contains("十一月\t1234"));
        }
    }
    @Test void extractsLegacyWordFixture()throws Exception{try(var input=getClass().getResourceAsStream("/documents/sample.doc")){assertNotNull(input);assertFalse(ChatDocumentService.extract("doc",input.readAllBytes()).isBlank());}}
    @Test void rejectsUnsupportedCorruptEncryptedAndScannedFiles()throws Exception{
        var user=user();
        for(String name:List.of("run.exe","bad.pdf","bad.doc","bad.xlsx"))mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",UUID.randomUUID().toString()).param("conversationId",user.room()).param("name",name).content("not a document")).andExpect(status().isBadRequest());
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.save(out);assertTrue(ChatDocumentService.extract("pdf",out.toByteArray()).isBlank());
            mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",UUID.randomUUID().toString()).param("conversationId",user.room()).param("name","scan.pdf").content(out.toByteArray())).andExpect(status().isBadRequest());}
        mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",UUID.randomUUID().toString()).param("conversationId",user.room()).param("name","big.txt").content(new byte[ChatDocumentService.MAX_BYTES+1])).andExpect(status().isPayloadTooLarge());
    }
    @Test void uploadsAreIdempotentMessagesContainMetadataAndModelReceivesText()throws Exception{
        var user=user();String id=upload(user,"销售.csv","商品,营收\n茶,600".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",id).param("conversationId",user.room()).param("name","销售.csv").content("商品,营收\n茶,600".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        mvc.perform(post("/api/device-messages/documents").header("Authorization",user.token()).param("id",id).param("conversationId",user.room()).param("name","销售.csv").content("different")).andExpect(status().isConflict());
        var body=body(user,List.of(id));long message=send(user,body);assertEquals(message,send(user,body));
        var saved=store.message(message);assertEquals("销售.csv",saved.documents().getFirst().name());assertTrue(store.documentContext(saved,12000).contains("茶,600"));
        assertFalse(json.writeValueAsString(saved).contains("茶,600"));
        body.put("documentIds",List.of());body.put("text","changed");mvc.perform(post("/api/device-messages").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
    }
    @Test void authorizationAndCrossConversationReferencesAreEnforced()throws Exception{
        var owner=user();var outsider=user();String id=upload(owner,"private.txt","私有内容".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/device-messages/documents/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/device-messages/documents/"+id).header("Authorization",outsider.token())).andExpect(status().isNotFound());
        mvc.perform(post("/api/device-messages").header("Authorization",outsider.token()).contentType("application/json").content(json.writeValueAsString(body(outsider,List.of(id))))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/device-messages/documents/"+id).header("Authorization",owner.token())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes("私有内容".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void favoritesKeepIndependentDocumentsAndBackupRestoresQuotedReferences()throws Exception{
        var user=user();var outsider=user();String doc=upload(user,"report.md","# 利润\n本月 100".getBytes(StandardCharsets.UTF_8));long original=send(user,body(user,List.of(doc)));
        var quote=body(user,List.of());quote.put("text","分析这份文档");quote.put("quoteMessageId",original);long quoted=send(user,quote);
        assertEquals(doc,store.message(quoted).quote().documents().getFirst().id());assertTrue(store.documentContext(store.message(quoted),12000).contains("本月 100"));
        var exported=backups.export(user.room());var room=backups.restore(UUID.randomUUID().toString(),null,exported,user.id(),false);
        var restored=store.archiveMessages(room.id(),0,Long.MAX_VALUE);String newDoc=restored.getFirst().documents().getFirst().id();assertNotEquals(doc,newDoc);assertEquals(newDoc,restored.getLast().quote().documents().getFirst().id());assertTrue(store.documentContext(restored.getLast(),12000).contains("本月 100"));
        long favorite=json.readTree(mvc.perform(post("/api/device-messages/"+original+"/favorite").header("Authorization",user.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
        var preview=json.readTree(mvc.perform(get("/api/conversations/context/clear-preview").header("Authorization",user.token()).param("conversationId",user.room())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(post("/api/conversations/context/clear").header("Authorization",user.token()).contentType("application/json").content(json.writeValueAsString(Map.of("conversationId",user.room(),"requestId",UUID.randomUUID().toString(),"confirm",true,"expectedLatestMessageId",preview.path("latestMessageId").asLong(),"expectedHistoryRevision",preview.path("historyRevision").asLong())))).andExpect(status().isOk());
        mvc.perform(get("/api/device-messages/documents/"+doc).header("Authorization",user.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/favorites/"+favorite+"/documents/"+doc).header("Authorization",user.token())).andExpect(status().isOk()).andExpect(content().bytes("# 利润\n本月 100".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/device-messages/favorites/"+favorite+"/documents/"+doc).header("Authorization",outsider.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/device-messages/favorites").header("Authorization",user.token()).param("q","report.md")).andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(1));
    }
    @Test void multipleDocumentsEachReceiveSomeModelContext()throws Exception{
        var user=user();String first=upload(user,"first.txt",("第一份"+"甲".repeat(20000)).getBytes(StandardCharsets.UTF_8));String second=upload(user,"second.txt",("第二份"+"乙".repeat(20000)).getBytes(StandardCharsets.UTF_8));
        var saved=store.message(send(user,body(user,List.of(first,second))));String text=store.documentContext(saved,4000);assertTrue(text.contains("第一份"));assertTrue(text.contains("第二份"));assertTrue(text.length()<4500);
    }
    @Test void longTextHasExplicitExtractionAndContextLimits()throws Exception{
        var user=user();String doc=upload(user,"long.txt","a".repeat(70000).getBytes(StandardCharsets.UTF_8));long message=send(user,body(user,List.of(doc)));var saved=store.message(message);
        assertTrue(saved.documents().getFirst().truncated());assertEquals(60000,saved.documents().getFirst().characterCount());String text=store.documentContext(saved,2000);assertTrue(AgentContextManager.estimate(text)<=2000);assertTrue(text.contains("仅提供部分内容"));
    }
}
