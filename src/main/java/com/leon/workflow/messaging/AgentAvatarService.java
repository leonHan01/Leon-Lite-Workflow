package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

@Service
public class AgentAvatarService {
    public static final String DEFAULT_ID="alfred";
    private static final int MAX_BYTES=5*1024*1024, MAX_DIMENSION=4096, SIZE=512;
    private final JdbcTemplate jdbc;
    public AgentAvatarService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Avatar(String id,String name,boolean builtin,String url) {}
    private record Preset(String id,String name) {}
    private static final List<Preset> PRESETS=List.of(
        new Preset("spongebob-round","海绵宝宝 · 圆形"),new Preset("spongebob","海绵宝宝"),new Preset("squidward","章鱼哥"),
        new Preset("abigail","Abigail"),new Preset("alfred-gray","Alfred · 灰色"),new Preset("alfred","Alfred"),
        new Preset("andy","Andy"),new Preset("arthur","Arthur"),
        new Preset("avatar02","Avatar 02"),new Preset("avatar04","Avatar 04"),new Preset("avatar05","Avatar 05"),
        new Preset("avatar06","Avatar 06"),new Preset("avatar07","Avatar 07"),new Preset("avatar08","Avatar 08"),
        new Preset("avatar12","Avatar 12"),new Preset("avatar13","Avatar 13"),new Preset("avatar14","Avatar 14"),
        new Preset("avatar15","Avatar 15"),new Preset("avatar19","Avatar 19"),new Preset("avatar20","Avatar 20"),
        new Preset("edward","Edward"),new Preset("frank","Frank"),new Preset("harry","Harry"),new Preset("henry","Henry"),
        new Preset("jackson","Jackson"),new Preset("joshua","Joshua"),new Preset("olivia","Olivia"),new Preset("paul","Paul"),
        new Preset("thomas","Thomas"),new Preset("walter","Walter"));
    @PostConstruct void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_agent_avatar(id VARCHAR(80) PRIMARY KEY,name VARCHAR(100) NOT NULL,png BLOB NOT NULL,created_at BIGINT NOT NULL)");
    }
    private static Avatar preset(Preset p) { return new Avatar(p.id(),p.name(),true,"/api/agent-avatars/"+p.id()); }
    public List<Avatar> catalog() {
        var result=new ArrayList<>(PRESETS.stream().map(AgentAvatarService::preset).toList());
        result.addAll(jdbc.query("SELECT id,name FROM chat_agent_avatar ORDER BY created_at DESC,id",(rs,n)->new Avatar(rs.getString(1),rs.getString(2),false,"/api/agent-avatars/"+rs.getString(1))));
        return result;
    }
    public boolean exists(String id) {
        if(id==null||!id.matches("[a-z0-9-]{1,80}"))return false;
        return PRESETS.stream().anyMatch(p->p.id().equals(id)) || jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_avatar WHERE id=?",Integer.class,id)>0;
    }
    public byte[] image(String id) {
        if(!exists(id))throw AgentRegistry.failure(HttpStatus.NOT_FOUND,"头像不存在");
        if(PRESETS.stream().anyMatch(p->p.id().equals(id))) {
            try(var input=new ClassPathResource("agent-avatars/"+id+".png").getInputStream()) { return input.readAllBytes(); }
            catch(IOException e){throw AgentRegistry.failure(HttpStatus.NOT_FOUND,"预设头像资源不可用");}
        }
        return jdbc.queryForObject("SELECT png FROM chat_agent_avatar WHERE id=?",byte[].class,id);
    }
    public synchronized Avatar upload(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>MAX_BYTES)throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"请选择不超过 5 MB 的 PNG 或 JPG 图片");
        byte[] png;
        try(var input=file.getInputStream();var bounded=new ByteArrayInputStream(input.readNBytes(MAX_BYTES+1))) {
            if(bounded.available()>MAX_BYTES)throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"图片不能超过 5 MB");
            try(var stream=new MemoryCacheImageInputStream(bounded)) {
                var readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext())throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"无法识别图片，请上传 PNG 或 JPG");
                var reader=readers.next();
                try {
                    String format=reader.getFormatName();
                    if(!format.equalsIgnoreCase("PNG")&&!format.equalsIgnoreCase("JPEG"))throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"只支持 PNG 或 JPG 图片");
                    reader.setInput(stream,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1||height<1||width>MAX_DIMENSION||height>MAX_DIMENSION)throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"图片宽高均不能超过 4096 像素");
                    BufferedImage original=reader.read(0);var normalized=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics=normalized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        double scale=(double)SIZE/Math.max(width,height);int w=Math.max(1,(int)Math.round(width*scale)),h=Math.max(1,(int)Math.round(height*scale));
                        graphics.drawImage(original,(SIZE-w)/2,(SIZE-h)/2,w,h,null);
                    } finally { graphics.dispose();original.flush(); }
                    var output=new ByteArrayOutputStream();ImageIO.write(normalized,"png",output);normalized.flush();png=output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch(org.springframework.web.server.ResponseStatusException e){throw e;}
        catch(Exception e){throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"图片损坏或无法读取，请重新选择 PNG 或 JPG");}
        try {
            String id="upload-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
            var previous=jdbc.query("SELECT name FROM chat_agent_avatar WHERE id=?",(rs,n)->rs.getString(1),id);
            if(!previous.isEmpty())return new Avatar(id,previous.getFirst(),false,"/api/agent-avatars/"+id);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_agent_avatar",Integer.class)>=1000)throw AgentRegistry.failure(HttpStatus.BAD_REQUEST,"自定义头像库已达到 1000 张上限");
            String name=Optional.ofNullable(file.getOriginalFilename()).orElse("自定义头像").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
            if(name.isBlank())name="自定义头像";if(name.length()>100)name=name.substring(0,100);
            jdbc.update("INSERT INTO chat_agent_avatar(id,name,png,created_at) VALUES(?,?,?,?)",id,name,png,System.currentTimeMillis());
            return new Avatar(id,name,false,"/api/agent-avatars/"+id);
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("无法生成头像标识");}
    }
}
