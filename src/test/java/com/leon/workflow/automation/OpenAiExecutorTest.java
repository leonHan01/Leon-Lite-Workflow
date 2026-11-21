package com.leon.workflow.automation;

import com.leon.workflow.ai.OpenAiExecutor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiExecutorTest {
    JdbcTemplate jdbc; OpenAiExecutor executor; WorkflowModels models;
    String worker,token;
    ExecutorService callers;
    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:mac-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""); jdbc=new JdbcTemplate(data);
        new WorkflowStore(jdbc).initialize(); var credentials=new WorkflowCredentials(jdbc,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); models=new WorkflowModels(jdbc,credentials);
        var credential=credentials.create(Map.of("kind","openai","name","test","value",Map.of("apiKey","test-secret")));
        models.save(null,Map.of("name","Luna","provider","openai","model","gpt-6-luna","credentialId",credential.get("id")));
        executor=new OpenAiExecutor(jdbc,models,new DataSourceTransactionManager(data),"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");executor.initialize();
        var registered=executor.register("Mac mini");worker=text(registered.get("id"));token=text(registered.get("token"));
        executor.heartbeat(worker,true);executor.mode(true);callers=Executors.newFixedThreadPool(3);
    }
    @AfterEach void close() { callers.shutdownNow();jdbc.execute("SHUTDOWN"); }
    Map<String,Object> body() { return Map.of("model","gpt-6-luna","stream",false,"messages",List.of(Map.of("role","user","content","private test prompt"))); }
    Future<Map<String,Object>> submit() { return callers.submit(()->executor.execute(body(),"test-secret",5000)); }
    Map<String,Object> claim() throws Exception {
        for(int i=0;i<100;i++) { var job=executor.claim(worker);if(!job.isEmpty())return job;Thread.sleep(10); }
        throw new AssertionError("job not queued");
    }
    Map<String,Object> result() { return Map.of("status",200,"body",Map.of("usage",Map.of("prompt_tokens",20,"completion_tokens",5,"total_tokens",25),"choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","answer test-secret"))))); }
    long tokens() { return ((Number)object(models.list().stream().filter(m->m.get("model").equals("gpt-6-luna")).findFirst().orElseThrow().get("usage")).get("totalTokens")).longValue(); }
    @Test void encryptsTasksAndResultsAndAccountsDuplicateAcknowledgementsExactlyOnce() throws Exception {
        var future=submit();var job=claim();String id=text(job.get("id")),lease=text(job.get("lease"));
        assertEquals("test-secret",job.get("apiKey"));assertEquals(body(),job.get("body"));
        String stored=jdbc.queryForObject("SELECT request_encrypted FROM ai_executor_job WHERE id=?",String.class,id);
        assertFalse(stored.contains("test-secret"));assertFalse(stored.contains("private test prompt"));
        executor.start(worker,id,lease);executor.complete(worker,id,lease,result());executor.complete(worker,id,lease,result());
        var output=future.get(2,TimeUnit.SECONDS);assertFalse(write(output).contains("test-secret"));assertTrue(write(output).contains("已隐藏凭据"));assertEquals(25,tokens());
        assertEquals("",jdbc.queryForObject("SELECT request_encrypted FROM ai_executor_job WHERE id=?",String.class,id));
        assertEquals("",jdbc.queryForObject("SELECT result_encrypted FROM ai_executor_job WHERE id=?",String.class,id));
    }
    @Test void onlyUnstartedLeasesCanBeReclaimedAndCancelledCallsNeverReplay() throws Exception {
        var future=submit();var first=claim();String id=text(first.get("id"));
        jdbc.update("UPDATE ai_executor_job SET lease_until=0 WHERE id=?",id);
        var second=claim();assertEquals(id,second.get("id"));assertNotEquals(first.get("lease"),second.get("lease"));
        assertThrows(ResponseStatusException.class,()->executor.start(worker,id,text(first.get("lease"))));
        executor.start(worker,id,text(second.get("lease")));jdbc.update("UPDATE ai_executor_job SET lease_until=0 WHERE id=?",id);
        assertTrue(executor.claim(worker).isEmpty());assertThrows(ResponseStatusException.class,()->executor.start(worker,id,text(second.get("lease"))));
        future.cancel(true);
        for(int i=0;i<100 && !"CANCELLED".equals(jdbc.queryForObject("SELECT state FROM ai_executor_job WHERE id=?",String.class,id));i++)Thread.sleep(10);
        executor.complete(worker,id,text(second.get("lease")),result());executor.complete(worker,id,text(second.get("lease")),result());
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM ai_executor_job WHERE id=?",String.class,id));assertEquals(25,tokens());assertTrue(executor.claim(worker).isEmpty());
    }
    @Test void restartFailsInFlightWorkAndDoesNotRequeueAnAmbiguousProviderCall() throws Exception {
        var future=submit();var job=claim();String id=text(job.get("id")),lease=text(job.get("lease"));executor.start(worker,id,lease);
        executor.initialize();assertTrue(executor.enabled());
        var failed=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));assertTrue(failed.getCause().getMessage().contains("服务重启"));
        executor.heartbeat(worker,true);assertTrue(executor.claim(worker).isEmpty());
        executor.complete(worker,id,lease,result());assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM ai_executor_job WHERE id=?",String.class,id));assertEquals(25,tokens());
    }
    @Test void offlineAndRevokedWorkersFailClosedWithoutChangingExecutionMode() throws Exception {
        assertEquals(worker,executor.authenticate("Bearer "+token));assertThrows(ResponseStatusException.class,()->executor.authenticate("Bearer wrong"));
        jdbc.update("UPDATE ai_executor_worker SET last_seen=0");assertThrows(IllegalArgumentException.class,()->executor.execute(body(),"test-secret",1000));
        executor.heartbeat(worker,true);var future=submit();var job=claim();executor.start(worker,text(job.get("id")),text(job.get("lease")));
        executor.revoke(worker);assertThrows(ResponseStatusException.class,()->executor.authenticate("Bearer "+token));assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));assertTrue(executor.enabled());
    }
    @Test void leasesAreScopedToTheirWorkerAndProviderFailuresDoNotStoreRawBodies() throws Exception {
        var future=submit();var job=claim();String id=text(job.get("id")),lease=text(job.get("lease"));
        executor.start(worker,id,lease);assertThrows(ResponseStatusException.class,()->executor.complete("other",id,lease,result()));
        executor.complete(worker,id,lease,Map.of("status",401,"body",Map.of("error","echo test-secret")));
        var output=future.get(2,TimeUnit.SECONDS);assertEquals(401,output.get("status"));assertTrue(object(output.get("body")).isEmpty());assertEquals(0,tokens());
    }
    @Test void searchAnnotationsCrossEncryptedQueueAndDuplicateAcknowledgementsCountUsageOnce() throws Exception {
        var body=Map.<String,Object>of("model","gpt-6-luna","stream",false,"input",List.of(Map.of("role","user","content","最新资料")),"tools",List.of(Map.of("type","web_search","external_web_access",true)));
        var future=callers.submit(()->executor.executeResponses(body,"test-secret",5000,null));
        var job=claim();assertEquals(body,job.get("body"));assertEquals("responses",job.get("api"));
        String id=text(job.get("id")),lease=text(job.get("lease"));executor.start(worker,id,lease);
        var annotation=Map.of("type","url_citation","start_index",0,"end_index",4,"url","https://example.test/source","title","官方来源");
        var payload=Map.<String,Object>of("status",200,"body",Map.of("status","completed","usage",Map.of("input_tokens",20,"output_tokens",5,"total_tokens",25),"output",List.of(Map.of("type","web_search_call","status","completed"),Map.of("type","message","content",List.of(Map.of("type","output_text","text","最新资料","annotations",List.of(annotation)))))));
        executor.complete(worker,id,lease,payload);executor.complete(worker,id,lease,payload);
        assertEquals(write(payload),write(future.get(2,TimeUnit.SECONDS)));assertEquals(25,tokens());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM automation_model_usage WHERE model LIKE 'gpt-image-%'",Integer.class));
    }
    @Test void responsesCarryApiTypeAndLargeImagesAndAccountMainlineAndToolSeparately() throws Exception {
        var body=Map.<String,Object>of("model","gpt-6-luna","stream",false,"input",List.of(Map.of("role","user","content","draw")),"tools",List.of(Map.of("type","image_generation","model","gpt-image-2.5-flare")));
        var future=callers.submit(()->executor.executeResponses(body,"test-secret",5000,null));
        var job=claim();assertEquals("responses",job.get("api"));String id=text(job.get("id")),lease=text(job.get("lease"));executor.start(worker,id,lease);
        Map<String,Object> payload=Map.of("status",200,"body",Map.of("status","completed","usage",Map.of("input_tokens",20,"output_tokens",5,"total_tokens",25),"output",List.of(Map.of("type","image_generation_call","status","completed","result","A".repeat(1_100_000)))));
        executor.complete(worker,id,lease,payload);executor.complete(worker,id,lease,payload);
        assertTrue(write(future.get(2,TimeUnit.SECONDS)).length()>1_048_576);assertEquals(25,tokens());
        assertEquals(1,jdbc.queryForObject("SELECT unreported_calls FROM automation_model_usage WHERE model='gpt-image-2.5-flare'",Integer.class));
        var textFuture=submit();job=claim();id=text(job.get("id"));lease=text(job.get("lease"));executor.start(worker,id,lease);
        String textId=id,textLease=lease;
        assertThrows(ResponseStatusException.class,()->executor.complete(worker,textId,textLease,payload));
        executor.complete(worker,id,lease,result());textFuture.get(2,TimeUnit.SECONDS);
    }
}
