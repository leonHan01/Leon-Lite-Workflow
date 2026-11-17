package com.leon.workflow.automation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

/** Shared model profiles contain metadata and credential references, never API keys. */
@Service
public class WorkflowModels {
    private final JdbcTemplate jdbc;
    private final WorkflowCredentials credentials;
    public WorkflowModels(JdbcTemplate jdbc, WorkflowCredentials credentials) { this.jdbc=jdbc; this.credentials=credentials; }
    public List<Map<String,Object>> list() {
        return jdbc.query("SELECT id,name,provider,model,credential_id,enabled FROM automation_model ORDER BY created_at,id", (rs,i) -> {
            var row=new LinkedHashMap<String,Object>();
            for (String key:List.of("id","name","provider","model")) row.put(key,rs.getString(key));
            row.put("credentialId",Objects.requireNonNullElse(rs.getString("credential_id"),"")); row.put("enabled",rs.getBoolean("enabled")); return row;
        });
    }
    public Map<String,Object> save(String id, Map<String,Object> body) {
        String name=required(body.get("name"),"模型显示名称需要 1–100 个字符",100);
        // Reject inline secrets and custom endpoints using the same rules as node configuration.
        WorkflowAiModel.validate(body);
        if (body.containsKey("modelId")) throw new IllegalArgumentException("模型配置不能引用另一个模型");
        String provider=WorkflowAiModel.provider(body), model=WorkflowAiModel.model(body), credentialId=text(body.get("credentialId"));
        if (provider.equals("openai") && !"openai".equals(credentials.get(credentialId).get("kind")))
            throw new IllegalArgumentException("请选择 OpenAI API Key 凭据");
        if (body.containsKey("enabled") && !(body.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("模型启用状态无效");
        boolean enabled=!Boolean.FALSE.equals(body.get("enabled"));
        if (id==null) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM automation_model",Integer.class)>=100) throw new IllegalArgumentException("最多配置 100 个模型");
            id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO automation_model(id,name,provider,model,credential_id,enabled,created_at) VALUES(?,?,?,?,?,?,?)",id,name,provider,model,credentialId,enabled,System.currentTimeMillis());
        } else if (jdbc.update("UPDATE automation_model SET name=?,provider=?,model=?,credential_id=?,enabled=? WHERE id=?",name,provider,model,credentialId,enabled,id)!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模型配置不存在，请刷新后重试");
        return get(id);
    }
    private Map<String,Object> get(String id) {
        return list().stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow(() -> new IllegalArgumentException("引用的模型配置不存在，请重新选择可用模型"));
    }
    Map<String,Object> resolve(Map<String,Object> config) {
        if (!config.containsKey("modelId")) return config;
        var profile=get(text(config.get("modelId")));
        if (!Boolean.TRUE.equals(profile.get("enabled"))) throw new IllegalArgumentException("所选模型已停用，请在模型配置中启用或重新选择");
        var resolved=new LinkedHashMap<>(config); resolved.remove("modelId");
        resolved.put("provider",profile.get("provider")); resolved.put("model",profile.get("model"));
        if ("openai".equals(profile.get("provider"))) resolved.put("credentialId",profile.get("credentialId"));
        return resolved;
    }
}
