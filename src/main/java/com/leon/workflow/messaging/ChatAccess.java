package com.leon.workflow.messaging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@DependsOn("chatUserMigration")
public class ChatAccess {
    private final JdbcTemplate jdbc;
    private final DeviceMessageStore store;
    private final ChatUserRegistry users;
    private final ChatUserRegistry.User leon;

    public ChatAccess(JdbcTemplate jdbc,DeviceMessageStore store,ChatUserRegistry users){
        this.jdbc=jdbc;
        this.store=store;
        this.users=users;
        leon=users.getOrCreate("Leon");
    }

    ChatUserRegistry.User user(HttpServletRequest request){
        var user=ChatUserRegistry.requestUser(request);
        return user==null?leon:user;
    }
    boolean legacy(ChatUserRegistry.User user){return user.id().equals(leon.id());}
    public static boolean personalRoom(String room){return "shared".equals(room)||(room!=null&&room.startsWith("home:"));}
    String home(ChatUserRegistry.User user){return legacy(user)?"shared":"home:"+user.id();}

    void ensureHome(ChatUserRegistry.User user){
        if(legacy(user))return;
        synchronized(store){
            jdbc.update("""
                INSERT INTO chat_conversation(id,name,kind,agent_ids,owner_id)
                SELECT ?,'设备群聊','group','',? WHERE NOT EXISTS(SELECT 1 FROM chat_conversation WHERE id=?)
                """,home(user),user.id(),home(user));
        }
    }

    String room(HttpServletRequest request,String room,boolean ownerOnly){
        var user=user(request);
        String id="shared".equals(room)?home(user):room;
        require(user,id,ownerOnly);
        return id;
    }

    boolean canRead(ChatUserRegistry.User user,String id){
        return allowed(user,id,false);
    }

    private boolean allowed(ChatUserRegistry.User user,String id,boolean ownerOnly){
        String sql="SELECT COUNT(*) FROM chat_conversation c WHERE c.id=? AND (COALESCE(c.owner_id,?)=?";
        if(!ownerOnly)sql+=" OR EXISTS(SELECT 1 FROM chat_conversation_member m WHERE m.conversation_id=c.id AND m.user_id=?)";
        Object[] args=ownerOnly?new Object[]{id,leon.id(),user.id()}:new Object[]{id,leon.id(),user.id(),user.id()};
        return jdbc.queryForObject(sql+")",Integer.class,args)>0;
    }

    void require(ChatUserRegistry.User user,String room,boolean ownerOnly){
        if(!allowed(user,room,ownerOnly))throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"会话不存在或无权访问");
    }

    void setOwner(String room,ChatUserRegistry.User user){
        jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE id=?",user.id(),room);
    }

    void requireRetry(HttpServletRequest request,long messageId,String agentId){
        checkedRelated(request,jdbc.queryForList("SELECT conversation_id FROM chat_agent_job WHERE message_id=? AND agent_id=?",String.class,messageId,agentId),false);
        var owners=jdbc.query("SELECT user_id FROM chat_agent_job WHERE message_id=? AND agent_id=?",(rs,n)->rs.getString(1),messageId,agentId);
        var user=user(request);
        if(owners.isEmpty()||!user.id().equals(owners.getFirst()==null?leon.id():owners.getFirst()))
            throw DeviceMessageStore.failure(HttpStatus.FORBIDDEN,"请由发起该回复的用户重试");
    }

    void requireArchive(HttpServletRequest request,String id,boolean ownerOnly){
        checkedRelated(request,jdbc.queryForList("SELECT conversation_id FROM chat_context_archive WHERE id=?",String.class,DeviceMessageStore.uuid(id)),ownerOnly);
    }

    void requireImage(HttpServletRequest request,String id){
        checkedRelated(request,jdbc.queryForList("SELECT conversation_id FROM chat_message_image WHERE id=?",String.class,DeviceMessageStore.uuid(id)),false);
    }

    private String checkedRelated(HttpServletRequest request,List<String> rooms,boolean ownerOnly){
        if(rooms.isEmpty())throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"记录不存在或无权访问");
        require(user(request),rooms.getFirst(),ownerOnly);
        return rooms.getFirst();
    }

    List<ChatUserRegistry.User> members(HttpServletRequest request,String room){
        room=room(request,room,false);
        return jdbc.query("""
            SELECT u.id,u.username FROM chat_user u JOIN chat_conversation c ON c.id=?
            WHERE u.id=COALESCE(c.owner_id,?) OR EXISTS(
                SELECT 1 FROM chat_conversation_member m WHERE m.conversation_id=c.id AND m.user_id=u.id)
            ORDER BY u.username_key
            """,(rs,n)->new ChatUserRegistry.User(rs.getString(1),rs.getString(2)),room,leon.id());
    }

    List<ChatUserRegistry.User> invitees(List<String> usernames){
        if(usernames==null)return List.of();
        if(usernames.size()>32)throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"每次最多添加 32 位用户");
        return usernames.stream().map(users::requireUsername).distinct().toList();
    }

    void addMembers(String room,List<ChatUserRegistry.User> members){
        for(var member:members)jdbc.update("""
            INSERT INTO chat_conversation_member(conversation_id,user_id,joined_at)
            SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM chat_conversation_member WHERE conversation_id=? AND user_id=?)
            """,room,member.id(),System.currentTimeMillis(),room,member.id());
    }
}
