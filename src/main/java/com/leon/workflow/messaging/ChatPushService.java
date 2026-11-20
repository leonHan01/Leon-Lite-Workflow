package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Persistent delivery cursors survive restarts; permissions and revocable sessions are rechecked before sending. */
@Service
public class ChatPushService {
    public record Event(long id,String room,String title,String text) {}
    public record Page(List<Event> events,long nextAfter,boolean hasMore) {}
    public record Registration(String deviceId,String platform,String provider,String token,String environment,boolean enabled,boolean preview,String activeRoom) {}
    private final JdbcTemplate jdbc;private final DeviceMessageStore store;private final ChatAccess access;private final ChatPushGateway gateway;private final boolean enabled;private final String legacy;
    public ChatPushService(JdbcTemplate jdbc,DeviceMessageStore store,ChatAccess access,ChatUserRegistry users,ChatPushGateway gateway,@Value("${leon.push.enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.store=store;this.access=access;this.gateway=gateway;this.enabled=enabled;legacy=users.getOrCreate("Leon").id();
    }
    @PostConstruct void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_push_device(user_id VARCHAR(36) NOT NULL,device_id VARCHAR(36) NOT NULL,platform VARCHAR(16) NOT NULL,provider VARCHAR(16) NOT NULL,token VARCHAR(512) NOT NULL,environment VARCHAR(16) NOT NULL,enabled BOOLEAN NOT NULL,preview BOOLEAN NOT NULL,session_hash VARCHAR(64) NOT NULL,last_id BIGINT NOT NULL,active_room VARCHAR(80) NOT NULL,active_until BIGINT NOT NULL,failures INT DEFAULT 0 NOT NULL,retry_at BIGINT DEFAULT 0 NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(user_id,device_id))");
    }
    public Map<String,Boolean> status(){return Map.of("enabled",enabled,"apns",enabled&&gateway.available("apns"),"vivo",enabled&&gateway.available("vivo"));}
    public void register(ChatUserRegistry.User user,Registration value,HttpServletRequest request){
        if(value==null)throw DeviceMessageStore.bad("设备信息不能为空");String device=DeviceMessageStore.uuid(value.deviceId());
        if(!Set.of("ios","macos","android").contains(value.platform())||!Set.of("apns","vivo","local").contains(value.provider()))throw DeviceMessageStore.bad("通知渠道无效");
        String token=value.token()==null?"":value.token(),environment=value.environment()==null?"development":value.environment();
        if(!Set.of("development","production").contains(environment)||token.length()>512||value.enabled()&&!value.provider().equals("local")&&!token.matches(value.provider().equals("apns")?"[a-fA-F0-9]{64,256}":"[A-Za-z0-9_-]{10,512}"))throw DeviceMessageStore.bad("推送设备标识无效");
        String room=value.activeRoom()==null?"":value.activeRoom();if(!room.isEmpty())access.require(user,room,false);
        String auth=request.getHeader("Authorization"),hash="";
        if(auth!=null&&auth.startsWith("Bearer chat_"))hash=ChatDocumentService.hash(auth.substring(7).getBytes(StandardCharsets.UTF_8));
        synchronized(store){
            var old=jdbc.queryForList("SELECT last_id,enabled,failures,retry_at FROM chat_push_device WHERE user_id=? AND device_id=?",user.id(),device);
            long after=old.isEmpty()||!Boolean.TRUE.equals(old.getFirst().get("enabled"))?latest():((Number)old.getFirst().get("last_id")).longValue(),now=System.currentTimeMillis();
            // A token must have only one account owner. A later login on the same installation rebinds it.
            if(!token.isEmpty())jdbc.update("DELETE FROM chat_push_device WHERE provider=? AND token=? AND user_id<>?",value.provider(),token,user.id());
            jdbc.update("MERGE INTO chat_push_device(user_id,device_id,platform,provider,token,environment,enabled,preview,session_hash,last_id,active_room,active_until,failures,retry_at,updated_at) KEY(user_id,device_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",user.id(),device,value.platform(),value.provider(),token,environment,value.enabled(),value.preview(),hash,after,room,room.isEmpty()?0:now+45000,old.isEmpty()?0:old.getFirst().get("failures"),old.isEmpty()?0:old.getFirst().get("retry_at"),now);
        }
    }
    public void remove(ChatUserRegistry.User user,String device){jdbc.update("DELETE FROM chat_push_device WHERE user_id=? AND device_id=?",user.id(),DeviceMessageStore.uuid(device));}
    private long latest(){return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM device_message",Long.class);}
    public Page inbox(ChatUserRegistry.User user,String device,Long after,boolean preview){
        DeviceMessageStore.uuid(device);if(after!=null&&after<0)throw DeviceMessageStore.bad("通知分页参数无效");
        synchronized(store){
            if(after==null)return new Page(List.of(),latest(),false);
            var rows=jdbc.query("SELECT m.id,m.conversation_id,m.sender,m.body,c.name,m.document_ids,m.image_ids FROM device_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.id>? AND m.device_id<>? AND (m.message_role='assistant' OR COALESCE(m.user_id,?)<>?) AND (COALESCE(c.owner_id,?)=? OR EXISTS(SELECT 1 FROM chat_conversation_member p WHERE p.conversation_id=c.id AND p.user_id=?)) ORDER BY m.id LIMIT 51",(rs,n)->{
                String body=rs.getString(4);if(body.isBlank())body=!rs.getString(6).isBlank()?"[文件]":"[图片]";
                return new Event(rs.getLong(1),rs.getString(2),preview?cut(rs.getString(3)+" · "+rs.getString(5),60):"Leon",preview?cut(body,110):"收到一条新消息");
            },after,device,legacy,user.id(),legacy,user.id(),user.id());
            boolean more=rows.size()>50;var events=List.copyOf(rows.subList(0,Math.min(50,rows.size())));
            return new Page(events,more?events.getLast().id():latest(),more);
        }
    }
    @Scheduled(fixedDelayString="${leon.push.poll-ms:5000}",initialDelayString="${leon.push.initial-delay-ms:15000}")
    public synchronized void dispatch(){
        if(!enabled)return;long now=System.currentTimeMillis();
        var devices=jdbc.queryForList("SELECT * FROM chat_push_device WHERE enabled=TRUE AND provider<>'local' AND retry_at<=? ORDER BY updated_at LIMIT 100",now);
        for(var device:devices){
            String userId=(String)device.get("user_id"),id=(String)device.get("device_id"),provider=(String)device.get("provider");
            if(!gateway.available(provider))continue;
            String session=(String)device.get("session_hash");
            if(!session.isEmpty()&&jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_session WHERE token_hash=? AND user_id=? AND expires_at>?",Integer.class,session,userId,now)==0){remove(new ChatUserRegistry.User(userId,""),id);continue;}
            var users=jdbc.query("SELECT id,username FROM chat_user WHERE id=?",(rs,n)->new ChatUserRegistry.User(rs.getString(1),rs.getString(2)),userId);if(users.isEmpty())continue;
            var page=inbox(users.getFirst(),id,((Number)device.get("last_id")).longValue(),Boolean.TRUE.equals(device.get("preview")));boolean failed=false;
            for(var event:page.events()){
                // Recheck both the session and token owner for every event, including during a multi-message delivery batch.
                int current=jdbc.queryForObject("SELECT COUNT(*) FROM chat_push_device d WHERE d.user_id=? AND d.device_id=? AND d.enabled=TRUE AND d.provider=? AND d.token=? AND d.session_hash=? AND (d.session_hash='' OR EXISTS(SELECT 1 FROM chat_user_session s WHERE s.token_hash=d.session_hash AND s.user_id=d.user_id AND s.expires_at>?))",Integer.class,userId,id,provider,device.get("token"),session,System.currentTimeMillis());
                if(current!=1){failed=true;break;}
                if(!access.canRead(users.getFirst(),event.room()))continue;
                // Foreground presentation is handled by native clients; presence leases must never drop a reply just after backgrounding.
                var result=gateway.send(provider,(String)device.get("token"),(String)device.get("environment"),id,event,userId);
                if(result==ChatPushGateway.Result.INVALID){jdbc.update("UPDATE chat_push_device SET enabled=FALSE WHERE user_id=? AND device_id=?",userId,id);failed=true;break;}
                if(result==ChatPushGateway.Result.RETRY){int count=((Number)device.get("failures")).intValue()+1;jdbc.update("UPDATE chat_push_device SET failures=?,retry_at=? WHERE user_id=? AND device_id=?",count,now+Math.min(3600000,5000L*(1L<<Math.min(count,9))),userId,id);failed=true;break;}
                jdbc.update("UPDATE chat_push_device SET last_id=GREATEST(last_id,?) WHERE user_id=? AND device_id=?",event.id(),userId,id);
            }
            if(!failed)jdbc.update("UPDATE chat_push_device SET last_id=GREATEST(last_id,?),failures=0,retry_at=0,updated_at=? WHERE user_id=? AND device_id=?",page.nextAfter(),now,userId,id);
        }
    }
    private static String cut(String value,int limit){return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit))+"…";}
}
