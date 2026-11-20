package com.leon.workflow.automation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        return jdbc.query("""
                SELECT m.id,m.name,m.provider,m.model,m.credential_id,m.enabled,
                  COALESCE(u.input_tokens,0) input_tokens, COALESCE(u.output_tokens,0) output_tokens,
                  COALESCE(u.total_tokens,0) total_tokens, COALESCE(u.reported_calls,0) reported_calls,
                  COALESCE(u.unreported_calls,0) unreported_calls, u.first_recorded_at,u.last_recorded_at
                FROM automation_model m LEFT JOIN automation_model_usage u ON u.provider=m.provider AND u.model=m.model
                ORDER BY m.created_at,m.id
                """, (rs,i) -> {
            var row=new LinkedHashMap<String,Object>();
            for (String key:List.of("id","name","provider","model")) row.put(key,rs.getString(key));
            row.put("credentialId",Objects.requireNonNullElse(rs.getString("credential_id"),"")); row.put("enabled",rs.getBoolean("enabled"));
            var usage=new LinkedHashMap<String,Object>();
            usage.put("inputTokens",rs.getLong("input_tokens")); usage.put("outputTokens",rs.getLong("output_tokens")); usage.put("totalTokens",rs.getLong("total_tokens"));
            usage.put("reportedCalls",rs.getLong("reported_calls")); usage.put("unreportedCalls",rs.getLong("unreported_calls"));
            for (String field:List.of("first_recorded_at","last_recorded_at")) usage.put(field.equals("first_recorded_at")?"firstRecordedAt":"lastRecordedAt",
                    rs.getObject(field)==null?null:Instant.ofEpochMilli(rs.getLong(field)).toString());
            row.put("usage",usage); return row;
        });
    }
    /** One row per provider/model, including legacy models no longer present in the catalog. No credentials. */
    public List<Map<String,Object>> usageSummaries() {
        return usageRows("SELECT * FROM automation_model_usage ORDER BY provider,model");
    }
    public List<Map<String,Object>> userUsageSummaries(String userId){
        return usageRows("SELECT * FROM chat_user_model_usage WHERE user_id=? ORDER BY provider,model",userId);
    }
    private List<Map<String,Object>> usageRows(String sql,Object... args){
        return jdbc.query(sql, (rs,i) -> {
            var row=new LinkedHashMap<String,Object>();
            row.put("provider",rs.getString("provider")); row.put("model",rs.getString("model"));
            row.put("inputTokens",rs.getLong("input_tokens")); row.put("outputTokens",rs.getLong("output_tokens"));
            row.put("totalTokens",rs.getLong("total_tokens")); row.put("reportedCalls",rs.getLong("reported_calls"));
            row.put("unreportedCalls",rs.getLong("unreported_calls"));
            row.put("firstRecordedAt",Instant.ofEpochMilli(rs.getLong("first_recorded_at")).toString());
            row.put("lastRecordedAt",Instant.ofEpochMilli(rs.getLong("last_recorded_at")).toString());
            return row;
        },args);
    }
    /** Account each HTTP response before content validation; cached/reasoning details are already in the totals. */
    public void recordUsage(String provider,String model,Object rawUsage){
        recordUsage(provider,model,rawUsage,null);
    }
    /** Image tool usage is tracked separately; absent usage stays unreported rather than estimated. */
    @Transactional
    public void recordOpenAiResponseUsage(String model,Map<?,?> response,String imageModel,String userId){
        recordUsage("openai",model,response.get("usage"),userId);
        if(imageModel==null||imageModel.isBlank())return;
        if(response.get("output") instanceof List<?> output)for(Object value:output){
            if(value instanceof Map<?,?> item&&"image_generation_call".equals(item.get("type")))
                recordUsage("openai",imageModel,item.get("usage"),userId);
        }
    }
    @Transactional
    public void recordUsage(String provider,String model,Object rawUsage,String userId){
        if (!Set.of("deepseek","openai").contains(provider) || !model.matches("[A-Za-z0-9_.:-]{1,100}")) throw new IllegalArgumentException("模型用量标识无效");
        var usage=rawUsage instanceof Map<?,?> map?map:Map.of();
        Long input=tokenCount(usage.get(usage.containsKey("prompt_tokens")?"prompt_tokens":"input_tokens")), output=tokenCount(usage.get(usage.containsKey("completion_tokens")?"completion_tokens":"output_tokens"));
        Long total=tokenCount(usage.get("total_tokens"));
        boolean reported=input!=null && output!=null;
        if (reported) {
            try { long sum=Math.addExact(input,output); if (!usage.containsKey("total_tokens")) total=sum; reported=total!=null && total==sum; }
            catch (ArithmeticException invalid) { reported=false; }
        }
        long in=reported?input:0, out=reported?output:0, all=reported?total:0, calls=reported?1:0, missing=reported?0:1, now=System.currentTimeMillis();
        addUsage(null,provider,model,in,out,all,calls,missing,now);
        if(userId!=null)addUsage(userId,provider,model,in,out,all,calls,missing,now);
    }
    private void addUsage(String userId,String provider,String model,long input,long output,long total,long calls,long missing,long now){
        String table=userId==null?"automation_model_usage":"chat_user_model_usage";
        String scope=userId==null?"":" AND user_id=?";
        var update=new ArrayList<Object>(List.of(input,output,total,calls,missing,now,now,provider,model));
        if(userId!=null)update.add(userId);
        String increment="""
                UPDATE %s SET input_tokens=input_tokens+?,output_tokens=output_tokens+?,total_tokens=total_tokens+?,
                    reported_calls=reported_calls+?,unreported_calls=unreported_calls+?,
                    first_recorded_at=LEAST(first_recorded_at,?),last_recorded_at=GREATEST(last_recorded_at,?)
                WHERE provider=? AND model=?%s
                """.formatted(table,scope);
        if(jdbc.update(increment,update.toArray())!=0)return;
        var insert=new ArrayList<Object>(List.of(provider,model,input,output,total,calls,missing,now,now));
        if(userId!=null)insert.add(userId);
        try{
            jdbc.update("INSERT INTO "+table+"(provider,model,input_tokens,output_tokens,total_tokens,reported_calls,unreported_calls,first_recorded_at,last_recorded_at"
                +(userId==null?") VALUES(?,?,?,?,?,?,?,?,?)":",user_id) VALUES(?,?,?,?,?,?,?,?,?,?)"),insert.toArray());
        }catch(DuplicateKeyException concurrent){
            jdbc.update(increment,update.toArray());
        }
    }
    private static Long tokenCount(Object value) {
        if (!(value instanceof Number number)) return null;
        try { long count=new BigDecimal(number.toString()).longValueExact(); return count<0?null:count; }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
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
    public Map<String,Object> resolve(Map<String,Object> config) {
        if (!config.containsKey("modelId")) return config;
        var profile=get(text(config.get("modelId")));
        if (!Boolean.TRUE.equals(profile.get("enabled"))) throw new IllegalArgumentException("所选模型已停用，请在模型配置中启用或重新选择");
        var resolved=new LinkedHashMap<>(config); resolved.remove("modelId");
        resolved.put("provider",profile.get("provider")); resolved.put("model",profile.get("model"));
        if ("openai".equals(profile.get("provider"))) resolved.put("credentialId",profile.get("credentialId"));
        return resolved;
    }
}
