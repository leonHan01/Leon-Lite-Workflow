package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Persistent chat identities and revocable device sessions; the service token remains separate. */
@Service
public class ChatUserRegistry {
    public static User requestUser(HttpServletRequest request){
        return (User)request.getAttribute(User.class.getName());
    }
    public static void setRequestUser(HttpServletRequest request,User user){
        request.setAttribute(User.class.getName(),user);
    }
    private static final long SESSION_LIFETIME=180L*24*60*60*1000;
    private final JdbcTemplate jdbc;
    private final SecureRandom random=new SecureRandom();
    public record User(String id,String username) {}
    public record Login(User user,String sessionToken,long expiresAt) {}
    public ChatUserRegistry(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_user (id VARCHAR(36) PRIMARY KEY, username VARCHAR(60) NOT NULL, username_key VARCHAR(60) NOT NULL UNIQUE, created_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_user_session (id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL, FOREIGN KEY(user_id) REFERENCES chat_user(id))");
        // Stable identity for existing records; migrations can reference it without deleting old data.
        getOrCreate("Leon");
    }
    public User getOrCreate(String name){
        String username=normalize(name),key=username.toLowerCase(Locale.ROOT);
        var found=find(key);if(found!=null)return found;
        try{jdbc.update("INSERT INTO chat_user(id,username,username_key,created_at) VALUES(?,?,?,?)",UUID.randomUUID().toString(),username,key,System.currentTimeMillis());}
        catch(DuplicateKeyException concurrent){/* Another request registered the same name. */}
        return Objects.requireNonNull(find(key));
    }
    User requireUsername(String name){
        var user=find(normalize(name).toLowerCase(Locale.ROOT));
        if(user==null)throw DeviceMessageStore.failure(HttpStatus.NOT_FOUND,"用户尚未注册："+name.strip());
        return user;
    }
    public Login login(String username){
        User user=getOrCreate(username);byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token="chat_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);long now=System.currentTimeMillis(),expires=now+SESSION_LIFETIME;
        jdbc.update("INSERT INTO chat_user_session(id,user_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),user.id(),hash(token),expires,now);
        return new Login(user,token,expires);
    }
    public User authenticate(String token){
        if(token==null||!token.matches("chat_[A-Za-z0-9_-]{43}"))throw DeviceMessageStore.failure(HttpStatus.UNAUTHORIZED,"登录已过期，请重新登录");
        var rows=jdbc.query("SELECT u.id,u.username FROM chat_user u JOIN chat_user_session s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>?",(rs,n)->new User(rs.getString(1),rs.getString(2)),hash(token),System.currentTimeMillis());
        if(rows.isEmpty())throw DeviceMessageStore.failure(HttpStatus.UNAUTHORIZED,"登录已过期，请重新登录");return rows.getFirst();
    }
    public void logout(String token){if(token!=null&&token.matches("chat_[A-Za-z0-9_-]{43}"))jdbc.update("DELETE FROM chat_user_session WHERE token_hash=?",hash(token));}
    private User find(String key){var rows=jdbc.query("SELECT id,username FROM chat_user WHERE username_key=?",(rs,n)->new User(rs.getString(1),rs.getString(2)),key);return rows.isEmpty()?null:rows.getFirst();}
    private static String normalize(String value){
        String name=value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).strip();
        if(name.isBlank()||name.length()>60||name.toLowerCase(Locale.ROOT).length()>60
            ||name.contains(",")||name.codePoints().anyMatch(Character::isISOControl))
            throw DeviceMessageStore.failure(HttpStatus.BAD_REQUEST,"用户名需要 1–60 个字符，不能包含逗号或控制字符");
        return name;
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
