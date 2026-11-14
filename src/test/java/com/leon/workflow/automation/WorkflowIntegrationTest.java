package com.leon.workflow.automation;

import com.example.helloservice.HelloServiceApplication;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.leon.workflow.automation.WorkflowJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:automation;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69",
        "leon.workflows.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
class WorkflowIntegrationTest {
    static HttpServer server;
    static String base;
    static AtomicInteger skippedRequests = new AtomicInteger();
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/data", exchange -> {
                byte[] body = "{\"name\":\"leon\",\"amount\":120}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.createContext("/skipped", exchange -> { skippedRequests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
            server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location", "/skipped"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
            server.createContext("/large", exchange -> { byte[] body = new byte[MAX_BYTES + 1]; Arrays.fill(body, (byte)'a'); exchange.sendResponseHeaders(200, body.length); try { exchange.getResponseBody().write(body); } catch (Exception ignored) {} exchange.close(); });
            server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) { registry.add("leon.workflows.http-allowed-origins", () -> base); }
    @AfterAll static void stop() { server.stop(0); }
    @Autowired WebApplicationContext context;
    @Autowired WorkflowStore store;
    @Autowired WorkflowEngine engine;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Cookie cookie;
    @BeforeEach void login() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String header = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest")
                .content("{\"token\":\"integration-test-token\",\"remember\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        cookie = new Cookie("__Host-leon_session", header.split(";", 2)[0].split("=", 2)[1]);
    }
    @Test void requiresAdminCookieAndExactWriteOrigin() throws Exception {
        mvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer integration-test-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/workflows").cookie(cookie).header("Origin", "https://evil.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"拒绝\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/workflows/capabilities").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.runtimes.javascript").exists()).andExpect(jsonPath("$.runtimes.groovy").exists());
        mvc.perform(get("/workflows.html").cookie(cookie)).andExpect(status().isOk());
    }
    @Test void publishedMixedLanguageWorkflowRunsHttpAndOnlyTheSelectedBranchDespiteDraftChanges() throws Exception {
        int requests = skippedRequests.get();
        var nodes = List.of(node("start", "manual", Map.of()), node("http", "http", Map.of("method", "GET", "url", base + "/data")),
                script("js", "javascript", "function main(i,c) { console.log('js-step'); return {items:[{json:{...i.items[0].json.body,amount:i.items[0].json.body.amount + c.params.increment}}]}; }", Map.of("increment", 5)),
                script("groovy", "groovy", "def main(Map i,Map c) { println 'groovy-step'; return [items:i.items.collect{[json:it.json+[amount:it.json.amount*2]]}]}"),
                node("check", "if", Map.of("left", "{{ input.items[0].json.amount }}", "operator", "gte", "right", 200)),
                script("yes", "javascript", "function main(i,c){ return i; }"), node("no", "http", Map.of("method", "POST", "url", base + "/skipped")));
        var graph = new WorkflowGraph(nodes, List.of(edge("start", "http", ""), edge("http", "js", ""), edge("js", "groovy", ""), edge("groovy", "check", ""), edge("check", "yes", "true"), edge("check", "no", "false")));
        Map<String, Object> workflow = createPublished(graph);
        String id = text(workflow.get("id"));
        var changed = new WorkflowGraph(List.of(node("start", "manual", Map.of()), script("bad", "javascript", "function main(i,c){ throw Error('draft must not run'); }")), List.of(edge("start", "bad", "")));
        store.save(id, "未发布修改", changed, ((Number) workflow.get("revision")).longValue());
        var started = request(post("/api/workflows/" + id + "/runs").content(write(Map.of("input", empty()))));
        var run = await(text(started.get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(1, run.get("version"));
        var items = (List<?>) object(run.get("output")).get("items"); assertEquals(250, object(object(items.getFirst()).get("json")).get("amount"));
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>) run.get("nodes");
        assertEquals("SKIPPED", steps.stream().filter(n -> n.get("nodeId").equals("no")).findFirst().orElseThrow().get("status"));
        assertTrue(steps.stream().anyMatch(n -> text(n.get("logs")).contains("groovy-step"))); assertEquals(requests, skippedRequests.get());
        mvc.perform(get("/api/workflows/runs/" + run.get("id")).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.definition.nodes.length()").value(7));
    }
    @Test void rejectsInvalidPublishingAndStaleDraftsButAllowsIncompleteDrafts() throws Exception {
        var workflow = store.create("未连接草稿"); String id = text(workflow.get("id"));
        var disconnected = new WorkflowGraph(List.of(node("start", "manual", Map.of()), script("other", "javascript", "function main(i,c){return i;}")), List.of());
        store.save(id, "未连接草稿", disconnected, 1);
        assertThrows(IllegalArgumentException.class, () -> store.publish(id, 2, write(engine.runtimes())));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.save(id, "过期", disconnected, 1));
        var cycle = new WorkflowGraph(disconnected.nodes(), List.of(edge("start", "other", ""), edge("other", "start", "")));
        assertThrows(IllegalArgumentException.class, cycle::validate);
        var unsupported = new WorkflowGraph(List.of(node("start", "manual", Map.of()), script("other", "python", "pass")), List.of(edge("start", "other", "")));
        assertThrows(IllegalArgumentException.class, unsupported::validate);
        mvc.perform(post("/api/workflows/" + id + "/publish").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
    @Test void recordsScriptFailuresRedactsSecretsAndCanCancelRunningProcesses() throws Exception {
        var graph = new WorkflowGraph(List.of(node("start", "manual", Map.of()), script("bad", "javascript", "function main(i,c){console.log('失败前日志',i.items[0].json.token);throw Error(i.items[0].json.token);}"), script("last", "groovy", "def main(Map i,Map c){return i}")), List.of(edge("start", "bad", ""), edge("bad", "last", "")));
        var workflow = createPublished(graph);
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("token", "example-private-token"))));
        var run = await(text(engine.start(text(workflow.get("id")), input).get("id")));
        assertEquals("FAILED", run.get("status")); assertFalse(write(run).contains("example-private-token")); assertTrue(text(run.get("error")).contains("[已隐藏]"));
        assertTrue(write(run).contains("失败前日志"));
        var loop = new WorkflowGraph(List.of(node("start", "manual", Map.of()), script("loop", "groovy", "def main(Map i,Map c){while(true){}}")), List.of(edge("start", "loop", "")));
        var runningWorkflow = createPublished(loop);
        var running = engine.start(text(runningWorkflow.get("id")), empty());
        String runId = text(running.get("id"));
        Thread.sleep(400); engine.cancel(runId); Thread.sleep(200);
        assertEquals("CANCELLED", store.run(runId).get("status"));
    }
    @Test void httpRejectsPrivateTargetsRedirectsAndOversizeResponses() throws Exception {
        for (String url : List.of("http://169.254.169.254/latest/meta-data", "http://127.0.0.1:1/", base + "/redirect", base + "/large")) {
            mvc.perform(post("/api/workflows/test-node").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                    .content(write(Map.of("node", node("http", "http", Map.of("url", url, "method", "GET")), "input", empty())))).andExpect(status().isBadRequest());
        }
    }
    @Test void storesCredentialsEncryptedAndNeverReturnsTheirValues() throws Exception {
        var created = request(post("/api/workflows/credentials").content(write(Map.of("name", "测试凭据", "kind", "bearer", "value", Map.of("token", "example-credential-value")))));
        assertFalse(write(created).contains("example-credential-value"));
        String encrypted = jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, created.get("id"));
        assertNotNull(encrypted); assertFalse(encrypted.contains("example-credential-value"));
        mvc.perform(get("/api/workflows/credentials").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$[0].value").doesNotExist());
    }
    @Test void marksInterruptedRunsFailedWithoutReplayingThemOnRestart() {
        var graph = WorkflowGraph.initial(); var workflow = createPublished(graph);
        String id = store.createRun(text(workflow.get("id")), 1, graph, empty());
        new WorkflowStore(jdbc).initialize();
        assertEquals("FAILED", store.run(id).get("status"));
    }
    private Map<String, Object> createPublished(WorkflowGraph graph) {
        var workflow = store.create("集成测试"); String id = text(workflow.get("id"));
        store.save(id, "集成测试", graph, 1); return store.publish(id, 2, write(engine.runtimes()));
    }
    private Map<String, Object> request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return read(body);
    }
    private Map<String, Object> await(String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            var run = store.run(id); if (!Set.of("QUEUED", "RUNNING").contains(run.get("status"))) return run;
            Thread.sleep(80);
        }
        fail("工作流执行超时"); return null;
    }
    static Map<String, Object> empty() { return Map.of("items", List.of(Map.of("json", Map.of()))); }
    static WorkflowGraph.Node node(String id, String type, Map<String, Object> config) { return new WorkflowGraph.Node(id, type, id, config, Map.of("x", 60, "y", 80)); }
    static WorkflowGraph.Node script(String id, String language, String source) { return script(id, language, source, Map.of()); }
    static WorkflowGraph.Node script(String id, String language, String source, Map<String, Object> params) { return node(id, "script", Map.of("language", language, "source", source, "params", params, "timeoutMs", 15000)); }
    static WorkflowGraph.Edge edge(String source, String target, String branch) { return new WorkflowGraph.Edge(source + "_" + target, source, target, branch); }
}
