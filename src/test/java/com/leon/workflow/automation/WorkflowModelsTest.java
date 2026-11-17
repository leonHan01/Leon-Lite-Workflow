package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
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
    Map<String,Object> usage(String id) { return object(models.list().stream().filter(m->id.equals(m.get("id"))).findFirst().orElseThrow().get("usage")); }
    @Test void actualModelTotalsAreSharedAcrossConfigurationsAndSurviveRenameDisableAndRestart() {
        models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",300,"completion_tokens",40,"total_tokens",340,
                "prompt_tokens_details",Map.of("cached_tokens",100),"completion_tokens_details",Map.of("reasoning_tokens",20)));
        models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",10,"completion_tokens",2));
        var alias=models.save(null,Map.of("name","另一个流程","provider","deepseek","model","deepseek-flash"));
        assertEquals(usage("default-deepseek"),object(alias.get("usage")));
        assertEquals(310L,usage("default-deepseek").get("inputTokens")); assertEquals(42L,usage("default-deepseek").get("outputTokens"));
        assertEquals(352L,usage("default-deepseek").get("totalTokens")); assertEquals(2L,usage("default-deepseek").get("reportedCalls"));
        assertNotNull(usage("default-deepseek").get("firstRecordedAt"));
        models.save("default-deepseek",Map.of("name","改名并停用","provider","deepseek","model","deepseek-flash","enabled",false));
        new WorkflowStore(jdbc).initialize(); assertEquals(352L,usage("default-deepseek").get("totalTokens"));
        models.save("default-deepseek",Map.of("name","换模型","provider","deepseek","model","deepseek-v4-pro","usage",Map.of("totalTokens",99999)));
        assertEquals(0L,usage("default-deepseek").get("totalTokens")); assertEquals(352L,usage(text(alias.get("id"))).get("totalTokens"));
        models.recordUsage("openai","deepseek-flash",Map.of("prompt_tokens",500,"completion_tokens",20,"total_tokens",520));
        assertEquals(352L,usage(text(alias.get("id"))).get("totalTokens"),"不同服务不能混算同名模型");
    }
    @Test void missingOrMalformedUsageIsMarkedWithoutInventingTokenCounts() {
        for(Object bad:List.of(Map.of(),Map.of("prompt_tokens",3),Map.of("prompt_tokens",-1,"completion_tokens",3),
                Map.of("prompt_tokens",1.5,"completion_tokens",3),Map.of("prompt_tokens",1,"completion_tokens",3,"total_tokens",9),
                Map.of("prompt_tokens",Long.MAX_VALUE,"completion_tokens",1),Map.of("prompt_tokens","10","completion_tokens",3)))
            models.recordUsage("deepseek","deepseek-flash",bad);
        models.recordUsage("deepseek","deepseek-flash",null);
        assertEquals(0L,usage("default-deepseek").get("totalTokens")); assertEquals(0L,usage("default-deepseek").get("reportedCalls"));
        assertEquals(8L,usage("default-deepseek").get("unreportedCalls"));
        models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",0,"completion_tokens",0,"total_tokens",0));
        assertEquals(1L,usage("default-deepseek").get("reportedCalls"));
    }
    @Test void simultaneousFirstCallsAndUpdatesNeverLoseUsageAndTotalsUseLongIntegers() throws Exception {
        var executor=Executors.newFixedThreadPool(6); var start=new CountDownLatch(1); var tasks=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<60;i++) tasks.add(executor.submit(()->{start.await();models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120));return null;}));
            start.countDown(); for(var task:tasks) task.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        assertEquals(60L,usage("default-deepseek").get("reportedCalls")); assertEquals(7200L,usage("default-deepseek").get("totalTokens"));
        models.recordUsage("deepseek","deepseek-flash",Map.of("prompt_tokens",3_000_000_000L,"completion_tokens",2_000_000_000L,"total_tokens",5_000_000_000L));
        assertEquals(5_000_007_200L,usage("default-deepseek").get("totalTokens"));
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
