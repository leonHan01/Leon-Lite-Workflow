package com.leon.workflow.ai;

import com.leon.workflow.automation.WorkflowModels;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** OpenAI-only leased queue. Requests/results are encrypted; running requests are never replayed. */
@Service
@DependsOn("workflowStore")
public class OpenAiExecutor {
    public static final int IMAGE_RESULT_LIMIT=24*1024*1024;
    private final JdbcTemplate jdbc;
    private final WorkflowModels models;
    private final TransactionTemplate transaction;
    private final byte[] key;
    public OpenAiExecutor(JdbcTemplate jdbc, WorkflowModels models, PlatformTransactionManager manager,
            @Value("${leon.workflows.credential-key:}") String configured) {
        this.jdbc=jdbc; this.models=models; transaction=new TransactionTemplate(manager);
        try { key=configured.isBlank()?null:Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("AI 执行器加密配置无效"); }
        if(key!=null && key.length!=32) throw new IllegalStateException("AI 执行器加密配置无效");
    }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_executor_mode (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL)");
        jdbc.update("INSERT INTO ai_executor_mode(id,enabled) SELECT 1,FALSE WHERE NOT EXISTS(SELECT 1 FROM ai_executor_mode WHERE id=1)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_executor_worker (
                  id VARCHAR(36) PRIMARY KEY,name VARCHAR(80) NOT NULL,token_hash VARCHAR(64) NOT NULL,
                  enabled BOOLEAN NOT NULL,ready BOOLEAN NOT NULL,last_seen BIGINT NOT NULL,created_at BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_executor_job (
                  id VARCHAR(36) PRIMARY KEY,model VARCHAR(100) NOT NULL,state VARCHAR(16) NOT NULL,
                  request_encrypted CLOB NOT NULL,result_encrypted CLOB NOT NULL DEFAULT '',
                  worker_id VARCHAR(36),lease_hash VARCHAR(64),lease_until BIGINT,
                  error_code VARCHAR(32) NOT NULL DEFAULT '',reported BOOLEAN NOT NULL DEFAULT FALSE,
                  created_at BIGINT NOT NULL,deadline_at BIGINT NOT NULL)
                """);
        jdbc.execute("ALTER TABLE ai_executor_job ADD COLUMN IF NOT EXISTS chat_user_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE ai_executor_job ADD COLUMN IF NOT EXISTS api VARCHAR(16) DEFAULT 'chat' NOT NULL");
        jdbc.execute("ALTER TABLE ai_executor_job ADD COLUMN IF NOT EXISTS tool_model VARCHAR(100) DEFAULT '' NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ai_executor_queue ON ai_executor_job(state,created_at)");
        jdbc.update("UPDATE ai_executor_worker SET ready=FALSE,last_seen=0");
        jdbc.update("UPDATE ai_executor_job SET state='FAILED',error_code='RESTART',request_encrypted='',result_encrypted='' WHERE state IN ('QUEUED','LEASED','RUNNING')");
    }
    public boolean enabled() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM ai_executor_mode WHERE id=1",Boolean.class)); }
    public Map<String,Object> status() {
        long now=System.currentTimeMillis();
        var workers=jdbc.query("SELECT id,name,enabled,ready,last_seen FROM ai_executor_worker ORDER BY created_at",(rs,n)->Map.<String,Object>of(
                "id",rs.getString("id"),"name",rs.getString("name"),"enabled",rs.getBoolean("enabled"),
                "online",rs.getBoolean("enabled")&&rs.getBoolean("ready")&&rs.getLong("last_seen")>now-20000,
                "lastSeen",rs.getLong("last_seen")==0?"":Instant.ofEpochMilli(rs.getLong("last_seen")).toString()));
        return Map.of("mode",enabled()?"mac":"direct","workers",workers,"configured",key!=null);
    }
    public void mode(boolean enabled) {
        requireKey();
        if(enabled && !online()) throw failure(HttpStatus.BAD_REQUEST,"Mac 执行器尚未就绪，请先启动并确认可连接 OpenAI");
        jdbc.update("UPDATE ai_executor_mode SET enabled=? WHERE id=1",enabled);
    }
    public Map<String,Object> register(String name) {
        requireKey(); name=required(name,"执行器名称需要 1–80 字",80);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executor_worker WHERE enabled=TRUE",Integer.class)>=8) throw failure(HttpStatus.BAD_REQUEST,"最多启用 8 个执行器");
        String id=UUID.randomUUID().toString(),token=nonce();
        jdbc.update("INSERT INTO ai_executor_worker(id,name,token_hash,enabled,ready,last_seen,created_at) VALUES(?,?,?,TRUE,FALSE,0,?)",id,name,hash(token),System.currentTimeMillis());
        return Map.of("id",id,"token",token,"name",name);
    }
    public synchronized void revoke(String id) {
        if(jdbc.update("UPDATE ai_executor_worker SET enabled=FALSE,ready=FALSE WHERE id=?",id)!=1) throw failure(HttpStatus.NOT_FOUND,"执行器不存在");
        jdbc.update("UPDATE ai_executor_job SET state='FAILED',error_code='REVOKED',request_encrypted='',result_encrypted='' WHERE worker_id=? AND state IN ('LEASED','RUNNING')",id);
    }
    public String authenticate(String authorization) {
        if(authorization==null || !authorization.startsWith("Bearer ") || authorization.length()>1024) throw failure(HttpStatus.UNAUTHORIZED,"执行器认证失败");
        String digest=hash(authorization.substring(7));
        var rows=jdbc.query("SELECT id,token_hash FROM ai_executor_worker WHERE enabled=TRUE",(rs,n)->Map.of("id",rs.getString(1),"hash",rs.getString(2)));
        for(var row:rows) if(MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),row.get("hash").getBytes(StandardCharsets.US_ASCII))) return row.get("id");
        throw failure(HttpStatus.UNAUTHORIZED,"执行器认证失败");
    }
    public void heartbeat(String worker,boolean ready) {
        if(jdbc.update("UPDATE ai_executor_worker SET last_seen=?,ready=? WHERE id=? AND enabled=TRUE",System.currentTimeMillis(),ready,worker)!=1) throw failure(HttpStatus.UNAUTHORIZED,"执行器已撤销");
    }
    private boolean online() { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_executor_worker WHERE enabled=TRUE AND ready=TRUE AND last_seen>?",Integer.class,System.currentTimeMillis()-20000)>0; }
    public Map<String,Object> execute(Map<String,Object> body,String apiKey,int timeoutMs) throws InterruptedException {
        return execute(body,apiKey,timeoutMs,null);
    }
    public Map<String,Object> execute(Map<String,Object> body,String apiKey,int timeoutMs,String userId) throws InterruptedException {
        return execute(body,apiKey,timeoutMs,userId,"chat");
    }
    public Map<String,Object> executeResponses(Map<String,Object> body,String apiKey,int timeoutMs,String userId) throws InterruptedException {
        return execute(body,apiKey,timeoutMs,userId,"responses");
    }
    private Map<String,Object> execute(Map<String,Object> body,String apiKey,int timeoutMs,String userId,String api) throws InterruptedException {
        requireKey();
        if(!enabled() || !online()) throw new IllegalArgumentException("Mac OpenAI 执行器离线，请先启动执行器后重试");
        String model=required(body.get("model"),"OpenAI 模型无效",100);
        if(!model.matches("[A-Za-z0-9_.:-]{1,100}") || Boolean.TRUE.equals(body.get("stream"))) throw new IllegalArgumentException("执行器仅支持非流式 OpenAI 模型请求");
        if(timeoutMs<100 || timeoutMs>240000 || write(body).getBytes(StandardCharsets.UTF_8).length>16_000_000) throw new IllegalArgumentException("OpenAI 任务超时或请求大小无效");
        String toolModel="";
        if(api.equals("responses")&&body.get("tools") instanceof List<?> tools)for(Object value:tools){
            if(value instanceof Map<?,?> tool&&"image_generation".equals(tool.get("type"))){
                toolModel=text(tool.get("model"));
                if(!toolModel.matches("gpt-image-[A-Za-z0-9_.-]{1,90}"))throw new IllegalArgumentException("绘图模型无效");
            }
        }
        String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis(),deadline=now+timeoutMs;
        synchronized(this) {
            expire(now);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executor_job WHERE state IN ('QUEUED','LEASED','RUNNING')",Integer.class)>=32) throw new IllegalArgumentException("Mac 执行器队列已满，请稍后重试");
            jdbc.update("INSERT INTO ai_executor_job(id,model,state,request_encrypted,created_at,deadline_at,chat_user_id,api,tool_model) VALUES(?,?,'QUEUED',?,?,?,?,?,?)",id,model,encrypt(id+":request",write(Map.of("body",body,"apiKey",apiKey,"api",api))),now,deadline,userId,api,toolModel);
        }
        try {
            while(System.currentTimeMillis()<deadline) {
                var row=jdbc.queryForMap("SELECT state,result_encrypted,error_code FROM ai_executor_job WHERE id=?",id);
                if(row.get("state").equals("SUCCEEDED")) {
                    var result=read(decrypt(id+":result",text(row.get("result_encrypted"))));
                    jdbc.update("UPDATE ai_executor_job SET result_encrypted='' WHERE id=?",id); return result;
                }
                if(Set.of("FAILED","CANCELLED","EXPIRED").contains(text(row.get("state")))) throw new IllegalArgumentException(message(text(row.get("error_code"))));
                Thread.sleep(100);
            }
            throw new IllegalArgumentException("等待 Mac 执行器超时，请确认执行器和网络后手动重试");
        } finally {
            jdbc.update("UPDATE ai_executor_job SET state='CANCELLED',error_code='CANCELLED',request_encrypted='',result_encrypted='' WHERE id=? AND state IN ('QUEUED','LEASED','RUNNING')",id);
        }
    }
    public synchronized Map<String,Object> claim(String worker) {
        long now=System.currentTimeMillis(); expire(now);
        if(!enabled()) return Map.of();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executor_worker WHERE id=? AND enabled=TRUE AND ready=TRUE AND last_seen>?",Integer.class,worker,now-20000)==0) return Map.of();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executor_job WHERE worker_id=? AND state IN ('LEASED','RUNNING')",Integer.class,worker)>=2) return Map.of();
        var rows=jdbc.queryForList("SELECT id,request_encrypted,deadline_at FROM ai_executor_job WHERE state='QUEUED' AND deadline_at>? ORDER BY created_at LIMIT 1",now);
        if(rows.isEmpty()) return Map.of(); var row=rows.getFirst(); String id=text(row.get("id")),lease=nonce();
        if(jdbc.update("UPDATE ai_executor_job SET state='LEASED',worker_id=?,lease_hash=?,lease_until=? WHERE id=? AND state='QUEUED'",worker,hash(lease),now+15000,id)!=1) return Map.of();
        var payload=read(decrypt(id+":request",text(row.get("request_encrypted"))));
        payload.put("id",id); payload.put("lease",lease); payload.put("deadlineAt",row.get("deadline_at")); return payload;
    }
    public synchronized void start(String worker,String id,String lease) {
        long now=System.currentTimeMillis();
        if(jdbc.update("UPDATE ai_executor_job SET state='RUNNING' WHERE id=? AND worker_id=? AND lease_hash=? AND state='LEASED' AND lease_until>? AND deadline_at>?",id,worker,hash(lease),now,now)!=1)
            throw failure(HttpStatus.CONFLICT,"任务租约已失效，不得调用 OpenAI");
    }
    public synchronized void complete(String worker,String id,String lease,Map<String,Object> result) {
        int size=write(result).getBytes(StandardCharsets.UTF_8).length;
        if(size>IMAGE_RESULT_LIMIT) throw failure(HttpStatus.PAYLOAD_TOO_LARGE,"执行结果过大");
        transaction.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT * FROM ai_executor_job WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"任务不存在"); var row=rows.getFirst();
            if(!worker.equals(row.get("worker_id")) || !MessageDigest.isEqual(hash(lease).getBytes(StandardCharsets.US_ASCII),text(row.get("lease_hash")).getBytes(StandardCharsets.US_ASCII))) throw failure(HttpStatus.CONFLICT,"任务租约不匹配");
            if(Boolean.TRUE.equals(row.get("reported"))) return;
            if(!"responses".equals(row.get("api"))&&size>1_040_000)throw failure(HttpStatus.PAYLOAD_TOO_LARGE,"执行结果过大");
            if(!Set.of("RUNNING","CANCELLED","EXPIRED","FAILED").contains(text(row.get("state")))) throw failure(HttpStatus.CONFLICT,"任务尚未开始");
            boolean active="RUNNING".equals(row.get("state")) && ((Number)row.get("deadline_at")).longValue()>System.currentTimeMillis();
            String code=text(result.get("errorCode"));
            if(!code.isEmpty()) {
                if(!Set.of("CONNECT_TIMEOUT","REPLY_TIMEOUT","NETWORK","TLS","TOO_LARGE","INVALID_RESPONSE").contains(code)) code="NETWORK";
                jdbc.update("UPDATE ai_executor_job SET reported=TRUE,state=?,error_code=?,request_encrypted='',result_encrypted='' WHERE id=?",active?"FAILED":row.get("state"),code,id); return;
            }
            int httpStatus=integer(result.get("status"),200,100,599); var body=object(result.get("body"));
            if(httpStatus>=200 && httpStatus<300){
                String model=text(row.get("model"));
                String user=(String)row.get("chat_user_id");
                if("responses".equals(row.get("api")))models.recordOpenAiResponseUsage(model,body,text(row.get("tool_model")),user);
                else if(user==null)models.recordUsage("openai",model,body.get("usage"));
                else models.recordUsage("openai",model,body.get("usage"),user);
            }
            // Never retain raw provider error bodies, which may echo credentials.
            if(httpStatus<200 || httpStatus>=300) body=Map.of();
            if(active) {
                String apiKey=text(read(decrypt(id+":request",text(row.get("request_encrypted")))).get("apiKey"));
                body=object(redact(body,apiKey));
            }
            jdbc.update("UPDATE ai_executor_job SET reported=TRUE,state=?,request_encrypted='',result_encrypted=? WHERE id=?",active?"SUCCEEDED":row.get("state"),active?encrypt(id+":result",write(Map.of("status",httpStatus,"body",body))):"",id);
        });
    }
    private void expire(long now) {
        jdbc.update("UPDATE ai_executor_job SET state='QUEUED',worker_id=NULL,lease_hash=NULL WHERE state='LEASED' AND lease_until<=? AND deadline_at>?",now,now);
        jdbc.update("UPDATE ai_executor_job SET state='EXPIRED',error_code='EXPIRED',request_encrypted='',result_encrypted='' WHERE state IN ('QUEUED','LEASED','RUNNING') AND deadline_at<=?",now);
        jdbc.update("DELETE FROM ai_executor_job WHERE created_at<? AND state NOT IN ('QUEUED','LEASED','RUNNING')",now-86400000);
    }
    private static Object redact(Object value,String secret) {
        if(value instanceof String s) return secret.isEmpty()?s:s.replace(secret,"[已隐藏凭据]");
        if(value instanceof Map<?,?> map) { var clean=new LinkedHashMap<String,Object>(); map.forEach((k,v)->clean.put(String.valueOf(k),redact(v,secret))); return clean; }
        if(value instanceof List<?> list) return list.stream().map(v->redact(v,secret)).toList(); return value;
    }
    public static String message(String code) { return switch(code) {
        case "CONNECT_TIMEOUT" -> "Mac 连接 OpenAI 超时，请检查 Mac 网络后手动重试";
        case "REPLY_TIMEOUT" -> "Mac 等待 OpenAI 回复超时，请稍后手动重试";
        case "TLS" -> "Mac 无法验证 OpenAI 证书，请检查本机网络与证书";
        case "TOO_LARGE" -> "OpenAI 响应过大，请缩短输入后重试";
        case "INVALID_RESPONSE" -> "OpenAI 返回无效响应，请稍后手动重试";
        case "RESTART" -> "服务重启中断了 Mac 任务，请确认后手动重试";
        case "REVOKED" -> "Mac 执行器已被撤销，请重新连接后手动重试";
        case "CANCELLED" -> "Mac 任务已取消";
        default -> "Mac OpenAI 调用未完成，请检查执行器网络后手动重试";
    }; }
    private void requireKey() { if(key==null) throw failure(HttpStatus.SERVICE_UNAVAILABLE,"请先启用加密凭据存储"); }
    private static String nonce() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("执行器认证不可用"); } }
    private String encrypt(String id,String plain) {
        try { byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce); var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(("ai-executor:"+id).getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("无法保存 AI 执行任务"); }
    }
    private String decrypt(String id,String encrypted) {
        try { String[] parts=encrypted.split(":",2);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(("ai-executor:"+id).getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8); }
        catch(Exception e) { throw new IllegalArgumentException("无法读取 AI 执行任务"); }
    }
    private static ResponseStatusException failure(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
