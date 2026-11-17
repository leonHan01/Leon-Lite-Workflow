package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowCredentialsTest {
    JdbcTemplate jdbc;
    WorkflowCredentials credentials;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:credentials-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        new WorkflowStore(jdbc).initialize();
        credentials = new WorkflowCredentials(jdbc, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    String create(String kind, Map<String, Object> value) { return text(credentials.create(Map.of("name", "原凭据", "kind", kind, "value", value)).get("id")); }
    Map<String, Object> smtp() { return Map.of("host", "smtp.example.com", "port", 465, "security", "ssl", "username", "sender@example.com", "password", "test-original-password", "from", "sender@example.com"); }
    @Test void smtpEditsKeepTheSameIdAndBlankPasswordsKeepTheExistingSecret() {
        String id = create("smtp", smtp());
        long createdAt = jdbc.queryForObject("SELECT created_at FROM automation_credential WHERE id=?", Long.class, id);
        var detail = credentials.get(id);
        assertEquals(Map.of("host", "smtp.example.com", "port", 465, "security", "ssl", "username", "sender@example.com", "from", "sender@example.com"), detail.get("value"));
        assertFalse(write(detail).contains("password"));
        var result = credentials.update(id, Map.of("name", "修改邮箱", "kind", "smtp", "value", Map.of("host", "mail.example.com", "port", 587, "security", "starttls", "password", "")));
        assertEquals(Map.of("id", id, "name", "修改邮箱", "kind", "smtp"), result);
        assertEquals("test-original-password", credentials.smtp(id, new WorkflowPrivacy()).get("password"));
        assertEquals("mail.example.com", credentials.smtp(id, new WorkflowPrivacy()).get("host"));
        assertEquals(createdAt, jdbc.queryForObject("SELECT created_at FROM automation_credential WHERE id=?", Long.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM automation_credential", Integer.class));
        credentials.update(id, Map.of("value", Map.of("password", "test-replacement-password")));
        assertEquals("test-replacement-password", credentials.smtp(id, new WorkflowPrivacy()).get("password"));
        assertFalse(jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, id).contains("test-replacement-password"));
    }
    @Test void everyHttpCredentialCanBeEditedWithoutReturningSecrets() {
        String bearer = create("bearer", Map.of("token", "test-original-token"));
        assertEquals(Map.of(), credentials.get(bearer).get("value"));
        credentials.update(bearer, Map.of("name", "重命名令牌", "value", Map.of("token", "")));
        assertEquals("Bearer test-original-token", credentials.headers(bearer, new WorkflowPrivacy()).get("Authorization"));
        credentials.update(bearer, Map.of("value", Map.of("token", "test-new-token")));
        assertEquals("Bearer test-new-token", credentials.headers(bearer, new WorkflowPrivacy()).get("Authorization"));
        String basic = create("basic", Map.of("username", "old-user", "password", "test-basic-password"));
        assertEquals(Map.of("username", "old-user"), credentials.get(basic).get("value"));
        credentials.update(basic, Map.of("value", Map.of("username", "new-user", "password", "")));
        String authorization = credentials.headers(basic, new WorkflowPrivacy()).get("Authorization");
        assertEquals("new-user:test-basic-password", new String(Base64.getDecoder().decode(authorization.substring(6)), java.nio.charset.StandardCharsets.UTF_8));
        String header = create("header", Map.of("header", "X-Old", "secret", "test-header-secret"));
        assertEquals(Map.of("header", "X-Old"), credentials.get(header).get("value"));
        credentials.update(header, Map.of("value", Map.of("header", "X-New", "secret", "")));
        assertEquals(Map.of("X-New", "test-header-secret"), credentials.headers(header, new WorkflowPrivacy()));
    }
    @Test void feishuEditsKeepPrivateFieldsAndAllowExplicitlyClearingTheOptionalSignature() {
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/test-original";
        String id = create("feishu", Map.of("webhookUrl", url, "secret", "test-signature"));
        assertEquals(Map.of(), credentials.get(id).get("value"));
        credentials.update(id, Map.of("name", "修改机器人", "value", Map.of("webhookUrl", "", "secret", "")));
        assertEquals(new WorkflowCredentials.FeishuWebhook(url, "test-signature"), credentials.feishu(id, new WorkflowPrivacy()));
        String replacement = "https://open.feishu.cn/open-apis/bot/v2/hook/test-new";
        credentials.update(id, Map.of("value", Map.of("webhookUrl", replacement), "clearSecret", true));
        assertEquals(new WorkflowCredentials.FeishuWebhook(replacement, ""), credentials.feishu(id, new WorkflowPrivacy()));
        assertFalse(write(credentials.get(id)).contains(replacement));
    }
    @Test void invalidEditsDoNotOverwriteTheExistingRecord() {
        String id = create("smtp", smtp());
        for (var update : List.<Map<String, Object>>of(Map.of("name", "尝试修改", "kind", "bearer"), Map.of("value", Map.of("port", 0)), Map.of("name", ""), Map.of("value", Map.of("security", "none"))))
            assertThrows(IllegalArgumentException.class, () -> credentials.update(id, update));
        assertEquals("原凭据", credentials.get(id).get("name"));
        assertEquals(smtp(), credentials.smtp(id, new WorkflowPrivacy()));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> credentials.get("missing")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> credentials.update("missing", Map.of("name", "missing"))).getStatusCode().value());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowCredentials(jdbc, "").update(id, Map.of("name", "拒绝")));
    }
    @Test void openAiKeysAreEncryptedNeverReturnedAndBlankEditsRetainTheSameSecret() {
        String id=create("openai",Map.of("apiKey","test-openai-secret"));
        assertEquals(Map.of(),credentials.get(id).get("value")); assertFalse(write(credentials.list()).contains("test-openai-secret"));
        assertFalse(jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?",String.class,id).contains("test-openai-secret"));
        credentials.update(id,Map.of("name","修改后的 OpenAI","value",Map.of("apiKey","")));
        var privacy=new WorkflowPrivacy(); assertEquals("test-openai-secret",credentials.openai(id,privacy)); assertEquals("[已隐藏]",privacy.message("test-openai-secret"));
        credentials.update(id,Map.of("value",Map.of("apiKey","test-new-openai-secret"))); assertEquals("test-new-openai-secret",credentials.openai(id,new WorkflowPrivacy()));
        assertThrows(IllegalArgumentException.class,()->credentials.headers(id,new WorkflowPrivacy()));
        String other=create("bearer",Map.of("token","http-only-token")); assertThrows(IllegalArgumentException.class,()->credentials.openai(other,new WorkflowPrivacy()));
        assertThrows(IllegalArgumentException.class,()->create("openai",Map.of("apiKey","bad\nkey")));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM automation_credential",Integer.class));
    }
}
