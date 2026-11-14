package com.leon.workflow.automation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;

@Service
public class WorkflowCredentials {
    private final JdbcTemplate jdbc;
    private final byte[] key;
    public WorkflowCredentials(JdbcTemplate jdbc, @Value("${leon.workflows.credential-key:}") String configured) {
        this.jdbc = jdbc;
        try { key = configured.isBlank() ? null : Base64.getDecoder().decode(configured.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("工作流凭据加密配置无效"); }
        if (key != null && key.length != 32) throw new IllegalStateException("工作流凭据加密密钥必须为 32 字节");
    }
    public boolean enabled() { return key != null; }
    public List<Map<String, Object>> list() { return jdbc.query("SELECT id,name,kind FROM automation_credential ORDER BY created_at DESC", (rs, i) -> Map.of("id", rs.getString(1), "name", rs.getString(2), "kind", rs.getString(3))); }
    public Map<String, Object> create(Map<String, Object> request) {
        requireKey();
        String name = required(request.get("name"), "凭据名称需要 1–100 个字符", 100), kind = text(request.get("kind"));
        Map<String, Object> value = object(request.get("value"));
        switch (kind) {
            case "bearer" -> required(value.get("token"), "请填写访问令牌", 8192);
            case "basic" -> { required(value.get("username"), "请填写用户名", 1000); required(value.get("password"), "请填写密码", 8192); }
            case "header" -> { required(value.get("header"), "请填写 Header 名称", 100); required(value.get("secret"), "请填写 Header 值", 8192); }
            default -> throw new IllegalArgumentException("不支持的凭据类型");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO automation_credential(id,name,kind,encrypted,created_at) VALUES(?,?,?,?,?)", id, name, kind, encrypt(id, write(value)), System.currentTimeMillis());
        return Map.of("id", id, "name", name, "kind", kind);
    }
    public void delete(String id) { jdbc.update("DELETE FROM automation_credential WHERE id=?", id); }
    public Map<String, String> headers(String id, WorkflowPrivacy privacy) {
        if (id == null || id.isBlank()) return Map.of();
        requireKey();
        var rows = jdbc.queryForList("SELECT kind,encrypted FROM automation_credential WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("HTTP 凭据不存在，请重新选择");
        var row = rows.getFirst();
        Map<String, Object> value = read(decrypt(id, row.get("encrypted").toString()));
        String name = "Authorization", header;
        switch (row.get("kind").toString()) {
            case "bearer" -> { privacy.secret(text(value.get("token"))); header = "Bearer " + text(value.get("token")); }
            case "basic" -> {
                privacy.secret(text(value.get("password")));
                header = "Basic " + Base64.getEncoder().encodeToString((text(value.get("username")) + ":" + text(value.get("password"))).getBytes(StandardCharsets.UTF_8));
            }
            default -> { name = text(value.get("header")); header = text(value.get("secret")); }
        }
        privacy.secret(header); return Map.of(name, header);
    }
    private void requireKey() { if (key == null) throw new IllegalArgumentException("凭据存储尚未启用，请联系管理员配置加密密钥"); }
    private String encrypt(String id, String value) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法保存凭据"); }
    }
    private String decrypt(String id, String value) {
        try {
            String[] parts = value.split(":", 2); var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))); cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalArgumentException("无法读取凭据，请确认服务器加密配置"); }
    }
}
