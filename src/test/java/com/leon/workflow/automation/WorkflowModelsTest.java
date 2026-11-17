package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowModelsTest {
    JdbcTemplate jdbc; WorkflowModels models; WorkflowCredentials credentials;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:models-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        new WorkflowStore(jdbc).initialize(); credentials=new WorkflowCredentials(jdbc,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); models=new WorkflowModels(jdbc,credentials);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    @Test void sharedProfilesPersistAndDefaultIsSeededOnlyOnce() {
        assertEquals("default-deepseek",models.list().getFirst().get("id"));
        var changed=models.save("default-deepseek",Map.of("name","我的 DeepSeek","provider","deepseek","model","deepseek-chat","enabled",false));
        new WorkflowStore(jdbc).initialize();
        assertEquals(List.of(changed),new WorkflowModels(jdbc,credentials).list());
        var legacy=Map.<String,Object>of("model","deepseek-flash"); assertSame(legacy,models.resolve(legacy));
    }
    @Test void nodesResolveCurrentSharedProfileAndDisabledOrMissingProfilesFailClearly() {
        var profile=models.save(null,Map.of("name","日常模型","provider","deepseek","model","deepseek-flash")); String id=text(profile.get("id"));
        var config=Map.<String,Object>of("modelId",id,"prompt","用户要求");
        assertEquals(Map.of("provider","deepseek","model","deepseek-flash","prompt","用户要求"),models.resolve(config));
        models.save(id,Map.of("name","日常模型","provider","deepseek","model","deepseek-chat"));
        assertEquals("deepseek-chat",models.resolve(config).get("model"));
        models.save(id,Map.of("name","日常模型","provider","deepseek","model","deepseek-chat","enabled",false));
        assertTrue(assertThrows(IllegalArgumentException.class,()->models.resolve(config)).getMessage().contains("停用"));
        assertThrows(IllegalArgumentException.class,()->models.resolve(Map.of("modelId","missing")));
    }
    @Test void profilesUseEncryptedOpenAiReferencesAndRejectKeysEndpointsAndWrongCredentialKinds() {
        String key="catalog-private-api-key";
        String id=text(credentials.create(Map.of("name","OpenAI","kind","openai","value",Map.of("apiKey",key))).get("id"));
        var profile=models.save(null,Map.of("name","GPT 日报","provider","openai","model","gpt-6.1-sol","credentialId",id));
        assertEquals(id,models.resolve(Map.of("modelId",profile.get("id"))).get("credentialId"));
        assertFalse(write(models.list()).contains(key));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM automation_credential WHERE encrypted LIKE ?",Integer.class,"%"+key+"%"));
        String bearer=text(credentials.create(Map.of("name","HTTP","kind","bearer","value",Map.of("token","test-token"))).get("id"));
        for(var bad:List.<Map<String,Object>>of(Map.of("provider","openai","credentialId",bearer),Map.of("provider","openai"),Map.of("apiKey",key),Map.of("baseUrl","https://example.com"),Map.of("modelId","default-deepseek"))) {
            var request=new LinkedHashMap<>(bad); request.put("name","invalid"); assertThrows(RuntimeException.class,()->models.save(null,request));
        }
        for(var bad:List.<Map<String,Object>>of(Map.of("modelId",""),Map.of("modelId","default-deepseek","apiKey",key),Map.of("modelId","default-deepseek","provider","openai")))
            assertThrows(IllegalArgumentException.class,()->WorkflowAiModel.validate(bad));
    }
}
