package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Authenticated, conversation-bound attachments; no external URL fetching. */
@Service
public class ChatImageService {
    public static final int MAX_UPLOAD=5*1024*1024, MAX_STORED=1024*1024, MAX_PER_MESSAGE=4;
    private final JdbcTemplate jdbc;
    private final DeviceMessageStore store;
    public ChatImageService(JdbcTemplate jdbc,DeviceMessageStore store){this.jdbc=jdbc;this.store=store;}
    @PostConstruct void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS chat_message_image (id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(80) NOT NULL,source_hash VARCHAR(64) NOT NULL,mime_type VARCHAR(40) NOT NULL,width INT NOT NULL,height INT NOT NULL,byte_count INT NOT NULL,image_data BLOB NOT NULL,created_at BIGINT NOT NULL)");}

    public synchronized DeviceMessageStore.Image upload(String id,String room,byte[] input) {
        id=DeviceMessageStore.uuid(id);store.conversation(room);
        if(input.length==0||input.length>MAX_UPLOAD)throw bad("图片不能为空且不能超过 5 MB");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            var existing=jdbc.queryForList("SELECT conversation_id,source_hash FROM chat_message_image WHERE id=?",id);
            if(!existing.isEmpty()){
                var old=existing.getFirst();
                if(!room.equals(old.get("conversation_id"))||!hash.equals(old.get("source_hash")))throw DeviceMessageStore.failure(HttpStatus.CONFLICT,"相同图片标识不能上传不同图片或会话");
                return metadata(id);
            }
            BufferedImage decoded;
            try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(input))){
                var readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext())throw bad("无法读取图片，请选择 PNG、JPG、GIF 或 WebP 并由客户端转换");
                var reader=readers.next();
                try {
                    reader.setInput(stream,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1||height<1||width>8192||height>8192||(long)width*height>36_000_000)throw bad("图片尺寸过大，请缩小后发送");
                    decoded=reader.read(0);
                } finally {reader.dispose();}
            }
            int max=1600;BufferedImage normalized;byte[] bytes;
            do {
                double scale=Math.min(1.0,(double)max/Math.max(decoded.getWidth(),decoded.getHeight()));
                normalized=new BufferedImage(Math.max(1,(int)Math.round(decoded.getWidth()*scale)),Math.max(1,(int)Math.round(decoded.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
                var graphics=normalized.createGraphics();
                try {graphics.setColor(Color.WHITE);graphics.fillRect(0,0,normalized.getWidth(),normalized.getHeight());graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(decoded,0,0,normalized.getWidth(),normalized.getHeight(),null);}finally{graphics.dispose();}
                var output=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("jpeg").next();
                try(var target=ImageIO.createImageOutputStream(output)){
                    writer.setOutput(target);var parameters=writer.getDefaultWriteParam();parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);parameters.setCompressionQuality(.85f);writer.write(null,new IIOImage(normalized,null,null),parameters);
                }finally{writer.dispose();}
                bytes=output.toByteArray();max=max*3/4;
            }while(bytes.length>MAX_STORED&&max>=256);
            if(bytes.length>MAX_STORED)throw bad("图片压缩后仍过大，请缩小后发送");
            jdbc.update("INSERT INTO chat_message_image(id,conversation_id,source_hash,mime_type,width,height,byte_count,image_data,created_at) VALUES(?,?,?,'image/jpeg',?,?,?,?,?)",id,room,hash,normalized.getWidth(),normalized.getHeight(),bytes.length,bytes,System.currentTimeMillis());
            return metadata(id);
        }catch(org.springframework.web.server.ResponseStatusException known){throw known;}
        catch(Exception invalid){throw bad("图片损坏或格式不受支持，请重新选择图片");}
    }
    public DeviceMessageStore.Image metadata(String id){
        var rows=jdbc.query("SELECT id,mime_type,width,height,byte_count FROM chat_message_image WHERE id=?",(rs,n)->new DeviceMessageStore.Image(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5)),DeviceMessageStore.uuid(id));
        if(rows.isEmpty())throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"图片不存在，请重新添加图片");return rows.getFirst();
    }
    public byte[] bytes(String id){
        var rows=jdbc.queryForList("SELECT image_data FROM chat_message_image WHERE id=?",byte[].class,DeviceMessageStore.uuid(id));
        if(rows.isEmpty())throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"图片不存在，请重新添加图片");return rows.getFirst();
    }
    static Map<String,String> message(String role,String text,List<DeviceMessageStore.Image> images){
        if(images==null||images.isEmpty())return Map.of("role",role,"content",text);
        return Map.of("role",role,"content",text.isBlank()?"请分析这张图片。":text,"imageIds",String.join(",",images.stream().map(DeviceMessageStore.Image::id).toList()));
    }
    /** Keep the newest eight images in model context. Stored history always retains all images. */
    public List<Map<String,Object>> modelMessages(List<Map<String,String>> messages,boolean openai) throws AgentModelClient.Failure {
        var result=new LinkedList<Map<String,Object>>();int remaining=8;
        for(int index=messages.size()-1;index>=0;index--){
            var message=messages.get(index);String ids=message.getOrDefault("imageIds","");
            String text=message.getOrDefault("content","");Object content=text;
            if(!ids.isBlank()){
                if(!openai)throw new AgentModelClient.Failure("当前模型不支持图片，请选择支持视觉输入的 OpenAI 模型");
                var parts=new ArrayList<Map<String,Object>>();parts.add(Map.of("type","text","text",text));
                for(String id:ids.split(",")){
                    if(remaining<=0){parts.add(Map.of("type","text","text","[较早的图片未重复附带；图片仍保存在聊天记录中]"));continue;}
                    var image=metadata(id);parts.add(Map.of("type","image_url","image_url",Map.of("url","data:"+image.mimeType()+";base64,"+Base64.getEncoder().encodeToString(bytes(id)),"detail","auto")));remaining--;
                }
                content=parts;
            }
            result.addFirst(Map.of("role",message.get("role"),"content",content));
        }
        return List.copyOf(result);
    }
    private static org.springframework.web.server.ResponseStatusException bad(String message){return DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,message);}
}
