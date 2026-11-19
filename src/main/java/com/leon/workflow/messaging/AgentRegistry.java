package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import com.leon.workflow.automation.*;
import com.leon.workflow.service.AppSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DuplicateKeyException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Service
public class AgentRegistry {
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final AgentAvatarService avatars;
    private final WorkflowModels models;
    private final WorkflowCredentials credentials;
    private final AppSettingService settings;
    public AgentRegistry(JdbcTemplate jdbc, @Value("${leon.workflows.credential-key:}") String configured, AgentAvatarService avatars, WorkflowModels models, WorkflowCredentials credentials, AppSettingService settings) {
        this.jdbc = jdbc; this.avatars=avatars; this.models=models; this.credentials=credentials; this.settings=settings;
        try { key = configured.isBlank() ? null : Base64.getDecoder().decode(configured.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Agent 凭据加密配置无效"); }
        if (key != null && key.length != 32) throw new IllegalStateException("Agent 凭据加密密钥必须为 32 字节");
    }
    public record Agent(String id, String name, String handle, String provider, String model, String systemPrompt,
                        boolean enabled, boolean hasApiKey, long version, String avatarId, String modelId, String description) {
        public Agent(String id,String name,String handle,String provider,String model,String systemPrompt,boolean enabled,boolean hasApiKey,long version,String avatarId,String modelId){this(id,name,handle,provider,model,systemPrompt,enabled,hasApiKey,version,avatarId,modelId,"");}
        public Agent(String id,String name,String handle,String provider,String model,String systemPrompt,boolean enabled,boolean hasApiKey,long version,String avatarId){this(id,name,handle,provider,model,systemPrompt,enabled,hasApiKey,version,avatarId,null);}
        public Agent(String id,String name,String handle,String provider,String model,String systemPrompt,boolean enabled,boolean hasApiKey,long version){this(id,name,handle,provider,model,systemPrompt,enabled,hasApiKey,version,AgentAvatarService.DEFAULT_ID);}
    }
    public record Summary(String id, String name, String handle, String provider, String model, boolean enabled, String avatarId, String description) {}
    public record Edit(String name, String handle, String provider, String model, String systemPrompt,
                       boolean enabled, String apiKey, Long version, String avatarId, String modelId, String description) {
        public Edit(String name,String handle,String provider,String model,String systemPrompt,boolean enabled,String apiKey,Long version,String avatarId,String modelId){this(name,handle,provider,model,systemPrompt,enabled,apiKey,version,avatarId,modelId,null);}
        public Edit(String name,String handle,String provider,String model,String systemPrompt,boolean enabled,String apiKey,Long version,String avatarId){this(name,handle,provider,model,systemPrompt,enabled,apiKey,version,avatarId,null);}
        public Edit(String name,String handle,String provider,String model,String systemPrompt,boolean enabled,String apiKey,Long version){this(name,handle,provider,model,systemPrompt,enabled,apiKey,version,null);}
    }
    @PostConstruct void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_agent (
              id VARCHAR(36) PRIMARY KEY, name VARCHAR(60) NOT NULL, handle VARCHAR(32) NOT NULL UNIQUE,
              provider VARCHAR(16) NOT NULL, model VARCHAR(100) NOT NULL, system_prompt VARCHAR(16000) NOT NULL,
              enabled BOOLEAN NOT NULL, encrypted_key VARCHAR(16000) NOT NULL, version BIGINT NOT NULL DEFAULT 1)
            """);
        jdbc.execute("ALTER TABLE chat_agent ADD COLUMN IF NOT EXISTS avatar_id VARCHAR(80) DEFAULT 'alfred' NOT NULL");
        jdbc.execute("ALTER TABLE chat_agent ADD COLUMN IF NOT EXISTS model_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE chat_agent ADD COLUMN IF NOT EXISTS description VARCHAR(2000) DEFAULT '' NOT NULL");
    }
    public boolean keyStorageEnabled() { return key != null; }
    static final class RuntimeConfig {
        final Agent agent; final String key;
        RuntimeConfig(Agent agent,String key) { this.agent=agent; this.key=key; }
    }
    synchronized RuntimeConfig runtime(String id) {
        var agent=get(id);
        if(agent.modelId()==null) return new RuntimeConfig(agent,apiKey(id));
        var profile=models.resolve(Map.of("modelId",agent.modelId()));
        var resolved=new Agent(agent.id(),agent.name(),agent.handle(),(String)profile.get("provider"),(String)profile.get("model"),agent.systemPrompt(),agent.enabled(),true,agent.version(),agent.avatarId(),agent.modelId(),agent.description());
        return new RuntimeConfig(resolved,profileKey(profile));
    }
    private String profileKey(Map<String,Object> profile) {
        String value=profile.get("provider").equals("openai")?credentials.openai((String)profile.get("credentialId"),new WorkflowPrivacy()):settings.getValue(AppSettingService.DEEPSEEK_API_KEY);
        if(value.isBlank()) throw failure(HttpStatus.BAD_REQUEST,"请先在模型配置或高级设置中保存 API Key");
        return value;
    }
    public List<Agent> list() {
        var profiles=new HashMap<String,Map<String,Object>>();
        for(var profile:models.list()) profiles.put((String)profile.get("id"),profile);
        return jdbc.query("SELECT * FROM chat_agent ORDER BY name,id", (rs,n) -> {
            String modelId=rs.getString("model_id"); var profile=profiles.get(modelId);
            return new Agent(rs.getString("id"),rs.getString("name"),rs.getString("handle"),profile==null?rs.getString("provider"):(String)profile.get("provider"),profile==null?rs.getString("model"):(String)profile.get("model"),rs.getString("system_prompt"),rs.getBoolean("enabled"),true,rs.getLong("version"),rs.getString("avatar_id"),modelId,rs.getString("description"));
        });
    }
    public List<Summary> summaries() { return list().stream().map(a -> new Summary(a.id(),a.name(),a.handle(),a.provider(),a.model(),a.enabled(),a.avatarId(),a.description())).toList(); }
    public Agent get(String id) { return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(() -> failure(HttpStatus.NOT_FOUND,"Agent 不存在")); }
    public synchronized Agent save(String id, Edit edit) {
        if (key == null) throw failure(HttpStatus.BAD_REQUEST,"请先配置服务器 LEON_WORKFLOW_CREDENTIAL_KEY，启用加密凭据存储");
        if (edit == null) throw failure(HttpStatus.BAD_REQUEST,"Agent 配置不能为空");
        String name = text(edit.name()).strip(), handle = text(edit.handle()).strip().toLowerCase(Locale.ROOT);
        Agent previous = id == null ? null : get(id);
        String description=edit.description()==null?(previous==null?"":previous.description()):edit.description().strip();
        if(description.length()>2000) throw failure(HttpStatus.BAD_REQUEST,"Agent 介绍不能超过 2000 字符");
        String modelId=text(edit.modelId()).strip();
        var profile=modelId.isEmpty()?null:models.resolve(Map.of("modelId",modelId));
        String provider=profile==null?(edit.provider()==null&&previous!=null?previous.provider():text(edit.provider())):(String)profile.get("provider");
        String model = profile==null?(edit.model()==null&&previous!=null?previous.model():text(edit.model()).strip()):(String)profile.get("model"), prompt = text(edit.systemPrompt());
        if (name.isBlank() || name.length()>60 || name.codePoints().anyMatch(Character::isISOControl)) throw failure(HttpStatus.BAD_REQUEST,"名称应为 1–60 字");
        if (!handle.matches("[a-z][a-z0-9_-]{1,31}")) throw failure(HttpStatus.BAD_REQUEST,"@标识应为 2–32 位小写字母、数字、下划线或短横线，并以字母开头");
        if (!Set.of("openai","deepseek").contains(provider)) throw failure(HttpStatus.BAD_REQUEST,"请选择 OpenAI 或 DeepSeek");
        if (!model.matches("[A-Za-z0-9_.:-]{1,100}") || prompt.length()>16000) throw failure(HttpStatus.BAD_REQUEST,"模型名称无效或提示词超过 16000 字符");
        if (previous == null && list().size()>=64) throw failure(HttpStatus.BAD_REQUEST,"最多配置 64 个 Agent");
        String avatarId=edit.avatarId()==null?(previous==null?AgentAvatarService.DEFAULT_ID:previous.avatarId()):edit.avatarId();
        if(!avatars.exists(avatarId))throw failure(HttpStatus.BAD_REQUEST,"请选择头像库中的头像或先上传图片");
        String agentId = previous == null ? UUID.randomUUID().toString() : id;
        String apiKey = text(edit.apiKey()).strip();
        if(profile!=null&&!apiKey.isEmpty()) throw failure(HttpStatus.BAD_REQUEST,"已保存的模型使用统一凭据，请勿另填 API Key");
        if (profile==null && apiKey.isEmpty() && (previous == null || !previous.provider().equals(provider) || previous.modelId()!=null)) throw failure(HttpStatus.BAD_REQUEST,"新建 Agent 或切换服务商时需要填写 API Key");
        if (apiKey.length()>8192 || apiKey.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) throw failure(HttpStatus.BAD_REQUEST,"API Key 格式无效");
        if (previous != null && !Objects.equals(edit.version(),previous.version())) throw failure(HttpStatus.CONFLICT,"配置已变更，请刷新后重试");
        String encrypted = profile!=null ? "" : apiKey.isEmpty() ? jdbc.queryForObject("SELECT encrypted_key FROM chat_agent WHERE id=?",String.class,id) : encrypt(agentId,apiKey);
        try {
            if (previous == null) jdbc.update("INSERT INTO chat_agent(id,name,handle,provider,model,system_prompt,enabled,encrypted_key,avatar_id,model_id,description,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,1)",agentId,name,handle,provider,model,prompt,edit.enabled(),encrypted,avatarId,modelId.isEmpty()?null:modelId,description);
            else if (jdbc.update("UPDATE chat_agent SET name=?,handle=?,provider=?,model=?,system_prompt=?,enabled=?,encrypted_key=?,avatar_id=?,model_id=?,description=?,version=version+1 WHERE id=? AND version=?",name,handle,provider,model,prompt,edit.enabled(),encrypted,avatarId,modelId.isEmpty()?null:modelId,description,agentId,previous.version()) != 1) throw failure(HttpStatus.CONFLICT,"配置已变更，请刷新后重试");
        } catch (DuplicateKeyException e) { throw failure(HttpStatus.BAD_REQUEST,"@标识已被其他 Agent 使用"); }
        return get(agentId);
    }
    String apiKey(String id) {
        var agent=get(id);
        if(agent.modelId()!=null) {
            var profile=models.resolve(Map.of("modelId",agent.modelId()));
            return profileKey(profile);
        }
        if (key == null) throw failure(HttpStatus.BAD_REQUEST,"Agent 加密存储未启用");
        try {
            String[] parts = jdbc.queryForObject("SELECT encrypted_key FROM chat_agent WHERE id=?",String.class,id).split(":",2);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD(("leon-agent:"+id).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        } catch (Exception e) { throw failure(HttpStatus.BAD_REQUEST,"无法读取 Agent 凭据，请检查加密配置"); }
    }
    private String encrypt(String id,String value) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(("leon-agent:"+id).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法保存 Agent 凭据"); }
    }
    static String text(String value) { return value == null ? "" : value; }
    static ResponseStatusException failure(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
