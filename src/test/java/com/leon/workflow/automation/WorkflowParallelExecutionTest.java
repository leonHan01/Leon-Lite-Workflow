package com.leon.workflow.automation;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowParallelExecutionTest {
    JdbcTemplate jdbc; WorkflowStore store; WorkflowEngine engine; WorkflowHttp http;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:parallel-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        store = new WorkflowStore(jdbc); store.initialize(); http = mock(WorkflowHttp.class);
        engine = new WorkflowEngine(store, mock(ScriptProcessRunner.class), http, mock(WorkflowFeishu.class), mock(WorkflowEmail.class), mock(WorkflowGithubTrending.class), mock(WorkflowRss.class), mock(WorkflowAiSummary.class), mock(WorkflowQq.class));
    }
    @AfterEach void close() { engine.close(); jdbc.execute("SHUTDOWN"); }
    Map<String,Object> input() { return read("{\"items\":[{\"json\":{\"value\":21}}]}"); }
    WorkflowGraph.Node node(String id, String type) { return new WorkflowGraph.Node(id, type, id, "http".equals(type) ? Map.of("url", "https://example.test/" + id) : Map.of(), Map.of()); }
    WorkflowGraph.Edge edge(String from, String to) { return new WorkflowGraph.Edge(from + "_" + to, from, to, ""); }
    WorkflowGraph fanOut(int count) {
        var nodes = new ArrayList<WorkflowGraph.Node>(); var edges = new ArrayList<WorkflowGraph.Edge>(); nodes.add(node("start", "start"));
        for (int i = 0; i < count; i++) { String id = "branch" + i; nodes.add(node(id, "http")); edges.add(edge("start", id)); edges.add(edge(id, "end")); }
        nodes.add(node("end", "end")); return new WorkflowGraph(nodes, edges);
    }
    String start(WorkflowGraph graph) {
        String id = text(store.create("并行测试").get("id")); store.save(id, "并行测试", graph, 1); store.publish(id, 2, "{}");
        return text(engine.start(id, input()).get("id"));
    }
    Map<String,Object> await(String id) throws Exception {
        for (int i = 0; i < 500; i++) { var run = store.run(id); if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return run; Thread.sleep(10); }
        throw new AssertionError("流程未完成");
    }
    Map<String,Object> step(String run, String id) { return ((List<?>)store.run(run).get("nodes")).stream().map(WorkflowJson::object).filter(n -> id.equals(n.get("nodeId"))).findFirst().orElseThrow(); }
    void awaitStep(String run, String id, String status) throws Exception {
        for (int i = 0; i < 300; i++) { if (status.equals(step(run,id).get("status"))) return; Thread.sleep(10); }
        assertEquals(status, step(run,id).get("status"));
    }
    @Test void siblingsOverlapAndEndWaitsWhileOutputOrderAndInputsStayStable() throws Exception {
        var entered = new CountDownLatch(3); var release = List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        when(http.execute(anyMap(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            int branch = Integer.parseInt(text(((Map<?,?>)call.getArgument(0)).get("url")).replace("https://example.test/branch", ""));
            Map<String,Object> data = call.getArgument(1); assertEquals(21, object(object(((List<?>)data.get("items")).getFirst()).get("json")).get("value"));
            entered.countDown(); if (!release.get(branch).await(4, TimeUnit.SECONDS)) throw new AssertionError("分支没有并行启动");
            object(object(((List<?>)data.get("items")).getFirst()).get("json")).put("value", branch); return data;
        });
        String id = start(fanOut(3));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS), "三个分支必须在任一个返回前全部开始");
            for (int i = 0; i < 3; i++) assertEquals("RUNNING", step(id,"branch"+i).get("status"));
            assertEquals("PENDING", step(id,"end").get("status"));
            release.get(2).countDown(); awaitStep(id,"branch2","SUCCEEDED");
            release.get(1).countDown(); awaitStep(id,"branch1","SUCCEEDED");
            assertEquals("PENDING", step(id,"end").get("status")); release.get(0).countDown();
            var run = await(id); assertEquals("SUCCEEDED", run.get("status"), text(run.get("error")));
            assertEquals(List.of(0,1,2), ((List<?>)object(run.get("output")).get("items")).stream().map(i -> object(object(i).get("json")).get("value")).toList());
        } finally { release.forEach(CountDownLatch::countDown); }
    }
    @Test void independentBranchesFinishAfterFailureAndFailedDescendantsNeverStart() throws Exception {
        var entered = new CountDownLatch(3); var release = new CountDownLatch(1);
        when(http.execute(anyMap(), anyMap(), anyMap(), any())).thenAnswer(call -> {
            String url = text(((Map<?,?>)call.getArgument(0)).get("url")); entered.countDown();
            if (!entered.await(3,TimeUnit.SECONDS)) throw new AssertionError("分支未全部开始");
            if (url.endsWith("branch0")) throw new IllegalArgumentException("模拟失败");
            assertFalse(url.endsWith("child"), "失败节点的后续不能运行"); release.await(); return call.getArgument(1);
        });
        var base = fanOut(3); var nodes = new ArrayList<>(base.nodes()); nodes.add(node("child","http"));
        var edges = new ArrayList<>(base.edges()); edges.removeIf(e -> e.source().equals("branch0") && e.target().equals("end")); edges.add(edge("branch0","child")); edges.add(edge("child","end"));
        String id = start(new WorkflowGraph(nodes,edges));
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS)); awaitStep(id,"branch0","FAILED");
            assertEquals("RUNNING",store.run(id).get("status")); assertEquals("RUNNING",step(id,"branch1").get("status"));
            release.countDown(); var run = await(id); assertEquals("FAILED",run.get("status")); assertEquals("模拟失败",run.get("error"));
            assertEquals("SUCCEEDED",step(id,"branch1").get("status")); assertEquals("SUCCEEDED",step(id,"branch2").get("status"));
            assertEquals("SKIPPED",step(id,"child").get("status")); assertEquals("SKIPPED",step(id,"end").get("status"));
        } finally { release.countDown(); }
    }
    @Test void fastBranchCanAdvanceWhileItsSiblingIsStillRunning() throws Exception {
        var slowEntered = new CountDownLatch(1); var childEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(http.execute(anyMap(),anyMap(),anyMap(),any())).thenAnswer(call -> {
            String url = text(((Map<?,?>)call.getArgument(0)).get("url"));
            if (url.endsWith("slow")) { slowEntered.countDown(); release.await(); }
            if (url.endsWith("fast")) assertTrue(slowEntered.await(2,TimeUnit.SECONDS));
            if (url.endsWith("child")) { assertEquals(1,release.getCount()); childEntered.countDown(); }
            return call.getArgument(1);
        });
        var graph = new WorkflowGraph(List.of(node("start","start"),node("fast","http"),node("child","http"),node("slow","http"),node("end","end")),
                List.of(edge("start","fast"),edge("start","slow"),edge("fast","child"),edge("child","end"),edge("slow","end")));
        String id = start(graph);
        try { assertTrue(childEntered.await(2,TimeUnit.SECONDS)); assertEquals("RUNNING",step(id,"slow").get("status")); }
        finally { release.countDown(); }
        assertEquals("SUCCEEDED",await(id).get("status"));
    }
    @Test void twoConcurrentRunsDoNotDeadlockTheirCoordinators() throws Exception {
        var entered = new CountDownLatch(6); var release = new CountDownLatch(1);
        when(http.execute(anyMap(),anyMap(),anyMap(),any())).thenAnswer(call -> { entered.countDown(); release.await(); return call.getArgument(1); });
        String one=start(fanOut(3)), two=start(fanOut(3));
        try { assertTrue(entered.await(2,TimeUnit.SECONDS),"流程协调线程不能占用分支工作线程"); }
        finally { release.countDown(); }
        assertEquals("SUCCEEDED",await(one).get("status")); assertEquals("SUCCEEDED",await(two).get("status"));
    }
    @Test void cancellationInterruptsRunningNodesAndPreventsQueuedNodesFromSending() throws Exception {
        var entered = new CountDownLatch(WorkflowEngine.MAX_PARALLEL_NODES); var stopped = new CountDownLatch(WorkflowEngine.MAX_PARALLEL_NODES); var calls = new AtomicInteger();
        when(http.execute(anyMap(),anyMap(),anyMap(),any())).thenAnswer(call -> {
            calls.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); return call.getArgument(1); }
            finally { stopped.countDown(); }
        });
        String id=start(fanOut(9)); assertTrue(entered.await(2,TimeUnit.SECONDS)); engine.cancel(id);
        assertTrue(stopped.await(2,TimeUnit.SECONDS)); assertEquals(WorkflowEngine.MAX_PARALLEL_NODES,calls.get());
        var run=store.run(id); assertEquals("CANCELLED",run.get("status"));
        assertTrue(((List<?>)run.get("nodes")).stream().map(WorkflowJson::object).noneMatch(n -> Set.of("RUNNING","PENDING").contains(n.get("status"))));
    }
    @Test void concurrentRedactionRegistersAndMasksSecretsWithoutSharingMutableInputs() throws Exception {
        var privacy = new WorkflowPrivacy();
        try (var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<100;i++) { String secret="test-private-value-"+i; futures.add(executor.submit(() -> {
                privacy.secret(secret); assertEquals("[已隐藏]",privacy.message(secret)); assertEquals(Map.of("value","[已隐藏]"),privacy.mask(Map.of("value",secret)));
            })); }
            for(var future:futures) future.get(3,TimeUnit.SECONDS);
        }
    }
}
