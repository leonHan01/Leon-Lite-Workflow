package com.leon.workflow.messaging;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Original files and extracted text are conversation-bound; parsers never execute formulas or macros. */
@Service
public class ChatDocumentService {
    public static final int MAX_BYTES=10*1024*1024, MAX_TEXT=60000;
    private static final Set<String> EXT=Set.of("pdf","txt","doc","docx","xls","xlsx","md","markdown","csv");
    private final JdbcTemplate jdbc;private final DeviceMessageStore store;
    public ChatDocumentService(JdbcTemplate jdbc,DeviceMessageStore store){this.jdbc=jdbc;this.store=store;}
    public DeviceMessageStore.Document upload(String id,String room,String user,String name,byte[] bytes){
        id=DeviceMessageStore.uuid(id);name=filename(name);
        if(bytes.length==0||bytes.length>MAX_BYTES)throw bad("文档不能为空，且每个文件不能超过 10 MB");
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!EXT.contains(ext))throw bad("支持 PDF、TXT、DOC/DOCX、XLS/XLSX、Markdown 和 CSV");
        long revision;String hash=hash(bytes);
        synchronized(store){
            store.conversation(room);revision=store.historyRevision(room);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_cleared_document WHERE id=?",Integer.class,id)>0)throw bad("附件已随聊天清空，请重新选择");
            var old=jdbc.queryForList("SELECT conversation_id,user_id,name,source_hash FROM chat_message_document WHERE id=?",id);
            if(!old.isEmpty()){
                var row=old.getFirst();
                if(!Objects.equals(row.get("conversation_id"),room)||!Objects.equals(row.get("user_id"),user)||!row.get("name").equals(name)||!row.get("source_hash").equals(hash))throw DeviceMessageStore.failure(HttpStatus.CONFLICT,"相同附件标识不能上传不同文件");
                return store.documents(id).getFirst();
            }
        }
        String text=extract(ext,bytes);boolean truncated=text.length()>MAX_TEXT;
        if(text.isBlank())throw bad(ext.equals("pdf")?"PDF 没有可提取的文字，扫描件请先进行 OCR 或发送页面图片。":"文档没有可供 AI 分析的文字内容。");
        if(truncated)text=text.substring(0,MAX_TEXT);
        String mime=switch(ext){case "pdf"->"application/pdf";case "doc"->"application/msword";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "xls"->"application/vnd.ms-excel";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "csv"->"text/csv";case "md","markdown"->"text/markdown";default->"text/plain";};
        synchronized(store){
            if(store.historyRevision(room)!=revision)throw bad("会话已清空，请重新选择附件");
            var existing=jdbc.queryForList("SELECT source_hash,name,user_id,conversation_id FROM chat_message_document WHERE id=?",id);
            if(!existing.isEmpty()){
                var old=existing.getFirst();if(!hash.equals(old.get("source_hash"))||!name.equals(old.get("name"))||!user.equals(old.get("user_id"))||!room.equals(old.get("conversation_id")))throw bad("附件标识冲突");
                return store.documents(id).getFirst();
            }
            jdbc.update("INSERT INTO chat_message_document(id,conversation_id,user_id,name,mime_type,byte_count,character_count,truncated,document_data,extracted_text,source_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,room,user,name,mime,bytes.length,text.length(),truncated,bytes,text,hash);
            return store.documents(id).getFirst();
        }
    }
    public byte[] bytes(String id){
        var rows=jdbc.queryForList("SELECT document_data FROM chat_message_document WHERE id=?",byte[].class,DeviceMessageStore.uuid(id));
        if(rows.isEmpty())throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"附件不存在或已清空");return rows.getFirst();
    }
    public String room(String id){
        var rows=jdbc.queryForList("SELECT conversation_id FROM chat_message_document WHERE id=?",String.class,DeviceMessageStore.uuid(id));
        if(rows.isEmpty())throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"附件不存在或已清空");return rows.getFirst();
    }
    static String filename(String name){
        if(name==null)throw bad("文件名不能为空");name=name.strip();
        if(name.isEmpty()||name.length()>180||name.contains("/")||name.contains("\\")||name.codePoints().anyMatch(Character::isISOControl))throw bad("文件名无效或过长");return name;
    }
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static String extract(String ext,byte[] bytes){
        try{
            String text;
            switch(ext){
                case "pdf":
                    try(var pdf=Loader.loadPDF(bytes)){
                        if(pdf.isEncrypted()||!pdf.getCurrentAccessPermission().canExtractContent())throw bad("PDF 已加密或禁止文字提取，请使用可读取的文件。");
                        if(pdf.getNumberOfPages()>300)throw bad("PDF 超过 300 页，请拆分后发送。");
                        var out=new BoundedWriter();var stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.writeText(pdf,out);text=out.toString();
                    }break;
                case "doc":try(var doc=new HWPFDocument(new ByteArrayInputStream(bytes));var reader=new WordExtractor(doc)){text=bounded(reader.getText());}break;
                case "docx":
                    checkZip(bytes);try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes));var reader=new XWPFWordExtractor(doc)){text=bounded(reader.getText());}break;
                case "xls":case "xlsx":
                    if(ext.equals("xlsx"))checkZip(bytes);
                    try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
                        var out=new BoundedWriter();var format=new DataFormatter(Locale.CHINA);format.setUseCachedValuesForFormulaCells(true);
                        outer:for(var sheet:book){out.write("\n[工作表："+sheet.getSheetName()+"]\n");for(var row:sheet){
                            out.write("行 "+(row.getRowNum()+1)+"\t");
                            int cells=Math.max(0,row.getLastCellNum());
                            for(int c=0;c<cells;c++){if(out.full())break outer;if(c>0)out.write("\t");var cell=row.getCell(c);if(cell!=null)out.write(format.formatCellValue(cell).replace('\t',' ').replace('\n',' '));}
                            out.write("\n");if(out.full())break outer;
                        }}text=out.toString();
                    }break;
                default:text=decode(bytes);
            }
            return text.replace("\u0000","").strip();
        }catch(org.springframework.web.server.ResponseStatusException known){throw known;}
        catch(Exception e){throw bad("文档损坏、已加密或格式与扩展名不符，请检查后重试。");}
    }
    private static void checkZip(byte[] bytes)throws IOException{
        int total=0,count=0;try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                if(++count>4000)throw bad("Office 文件结构过大，请拆分后发送");
                byte[] buffer=new byte[8192];int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>50*1024*1024)throw bad("Office 文件解压后过大，请拆分后发送");}
            }
        }
    }
    private static String decode(byte[] bytes)throws CharacterCodingException{
        Charset[] encodings={StandardCharsets.UTF_8,Charset.forName("GB18030")};
        if(bytes.length>=2&&(bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe||bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff))encodings=new Charset[]{StandardCharsets.UTF_16};
        for(var encoding:encodings){try{String text=encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF","");
            if(text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw bad("文件包含二进制内容，请选择有效文档");return bounded(text);
        }catch(CharacterCodingException ignored){}}
        throw bad("无法识别文字编码，请另存为 UTF-8 后发送");
    }
    private static String bounded(String value){return value.length()>MAX_TEXT+1?value.substring(0,MAX_TEXT+1):value;}
    private static class BoundedWriter extends Writer{
        private final StringBuilder text=new StringBuilder();boolean full(){return text.length()>MAX_TEXT;}
        public void write(char[] chars,int offset,int length){int n=Math.min(length,MAX_TEXT+1-text.length());if(n>0)text.append(chars,offset,n);}
        public void flush(){}public void close(){}public String toString(){return text.toString();}
    }
    private static org.springframework.web.server.ResponseStatusException bad(String text){return DeviceMessageStore.bad(text);}
}
