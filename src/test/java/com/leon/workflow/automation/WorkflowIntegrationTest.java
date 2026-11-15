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
        "leon.workflows.scheduling-enabled=false",
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
            server.createContext("/echo", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
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
    @Autowired WorkflowHttp http;
    @Autowired WorkflowSchedules schedules;
    @Autowired WorkflowScheduler scheduler;
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
    @Test void newWorkflowsContainProtectedTerminalsAndRejectMissingOrDuplicateTerminalsOnSave() throws Exception {
        var workflow = request(post("/api/workflows").content(write(Map.of("name", "起止节点"))));
        String id = text(workflow.get("id"));
        var graph = WorkflowGraph.parse(workflow.get("definition"));
        assertEquals(List.of("start", "end"), graph.nodes().stream().map(WorkflowGraph.Node::type).toList());
        for (var nodes : List.of(List.of(node("start", "start", Map.of()), node("action", "http", Map.of())),
                List.of(node("action", "http", Map.of()), node("end", "end", Map.of())),
                List.of(node("start", "start", Map.of()), node("extra", "start", Map.of()), node("end", "end", Map.of())),
                List.of(node("start", "start", Map.of()), node("end", "end", Map.of()), node("extra", "end", Map.of())),
                List.of(node("start", "start", Map.of()), node("end", "end", Map.of()), node("extra", "manual", Map.of())))) {
            mvc.perform(put("/api/workflows/" + id).cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest")
                    .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("name", "起止节点", "revision", 1, "definition", new WorkflowGraph(nodes, List.of())))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        assertEquals(1L, store.get(id).get("revision"));
        request(post("/api/workflows/" + id + "/publish").content(write(Map.of("revision", 1))));
        var run = await(text(engine.start(id, empty()).get("id")));
        assertEquals("SUCCEEDED", run.get("status")); assertEquals(empty(), run.get("output"));
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>) run.get("nodes");
        assertEquals(List.of("start", "end"), steps.stream().map(n -> text(n.get("type"))).toList());
        assertTrue(steps.stream().allMatch(n -> "SUCCEEDED".equals(n.get("status"))));
    }
    @Test void bothIfOutcomesExecuteTheSharedEndExactlyOnceAndKeepTheirOutput() throws Exception {
        var graph = new WorkflowGraph(List.of(node("start", "start", Map.of()), node("check", "if", Map.of("left", "{{ input.items[0].json.flag }}", "operator", "truthy")), node("end", "end", Map.of())),
                List.of(edge("start", "check", ""), new WorkflowGraph.Edge("true_end", "check", "end", "true"), new WorkflowGraph.Edge("false_end", "check", "end", "false")));
        var workflow = createPublished(graph);
        for (boolean flag : List.of(true, false)) {
            var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("flag", flag))));
            var run = await(text(engine.start(text(workflow.get("id")), input).get("id")));
            assertEquals("SUCCEEDED", run.get("status")); assertEquals(input, run.get("output"));
            @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>) run.get("nodes");
            assertEquals(3, steps.size()); assertTrue(steps.stream().allMatch(n -> "SUCCEEDED".equals(n.get("status"))));
        }
    }
    @Test void fanOutExecutesEverySuccessorWithIndependentInputsAndFinishesTheEndOnce() throws Exception {
        var graph = new WorkflowGraph(List.of(node("start", "start", Map.of()),
                script("left", "javascript", "function main(i,c){i.items[0].json.value=7;return i;}"),
                script("right", "groovy", "def main(Map i,Map c){return i}"), node("end", "end", Map.of())),
                List.of(edge("start", "left", ""), edge("start", "right", ""), edge("left", "end", ""), edge("right", "end", "")));
        var workflow = createPublished(graph);
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("value", 21))));
        var run = await(text(engine.start(text(workflow.get("id")), input).get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error")));
        var items = (List<?>)object(run.get("output")).get("items");
        assertEquals(List.of(7, 21), items.stream().map(i -> object(object(i).get("json")).get("value")).toList());
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>)run.get("nodes");
        assertEquals(4, steps.size()); assertTrue(steps.stream().allMatch(n -> "SUCCEEDED".equals(n.get("status"))));
        var end = steps.stream().filter(n -> "end".equals(n.get("type"))).findFirst().orElseThrow();
        for (var step : steps) if (!"end".equals(step.get("type")))
            assertFalse(java.time.Instant.parse(text(end.get("startedAt"))).isBefore(java.time.Instant.parse(text(step.get("finishedAt")))));
        assertEquals(input, steps.stream().filter(n -> "right".equals(n.get("nodeId"))).findFirst().orElseThrow().get("input"));
    }
    @Test void ifFanOutExecutesAllMatchingConnectionsAndSkipsTheOtherBranch() throws Exception {
        int requests = skippedRequests.get();
        var graph = new WorkflowGraph(List.of(node("start", "start", Map.of()), node("check", "if", Map.of("operator", "truthy", "left", true)),
                node("left", "http", Map.of("url", base + "/data")), node("right", "http", Map.of("url", base + "/data")),
                node("no", "http", Map.of("url", base + "/skipped")), node("end", "end", Map.of())),
                List.of(edge("start", "check", ""), edge("check", "left", "true"), edge("check", "right", "true"), edge("check", "no", "false"),
                        edge("left", "end", ""), edge("right", "end", ""), edge("no", "end", "")));
        var workflow = createPublished(graph);
        var run = await(text(engine.start(text(workflow.get("id")), empty()).get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error")));
        assertEquals(2, ((List<?>)object(run.get("output")).get("items")).size());
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>)run.get("nodes");
        assertEquals("SKIPPED", steps.stream().filter(n -> "no".equals(n.get("nodeId"))).findFirst().orElseThrow().get("status"));
        assertEquals(5, steps.stream().filter(n -> "SUCCEEDED".equals(n.get("status"))).count());
        assertEquals(requests, skippedRequests.get());
    }
    @Test void fanOutFailureStopsPendingSuccessorsAndAggregateOutputsKeepTheItemLimit() throws Exception {
        int requests = skippedRequests.get();
        var failing = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("bad", "javascript", "function main(i,c){throw Error('branch failed');}"),
                node("later", "http", Map.of("url", base + "/skipped")), node("end", "end", Map.of())),
                List.of(edge("start", "bad", ""), edge("start", "later", ""), edge("bad", "end", ""), edge("later", "end", "")));
        var workflow = createPublished(failing);
        var run = await(text(engine.start(text(workflow.get("id")), empty()).get("id")));
        assertEquals("FAILED", run.get("status")); assertEquals(requests, skippedRequests.get());
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>)run.get("nodes");
        assertEquals("SKIPPED", steps.stream().filter(n -> "later".equals(n.get("nodeId"))).findFirst().orElseThrow().get("status"));
        var limited = new WorkflowGraph(List.of(node("start", "start", Map.of()), node("left", "if", Map.of("operator", "truthy", "left", true)),
                node("right", "if", Map.of("operator", "truthy", "left", true)), node("end", "end", Map.of())),
                List.of(edge("start", "left", ""), edge("start", "right", ""), new WorkflowGraph.Edge("lt", "left", "end", "true"), new WorkflowGraph.Edge("lf", "left", "end", "false"),
                        new WorkflowGraph.Edge("rt", "right", "end", "true"), new WorkflowGraph.Edge("rf", "right", "end", "false")));
        var many = createPublished(limited);
        var largeInput = Map.<String, Object>of("items", Collections.nCopies(600, Map.of("json", Map.of("value", 1))));
        var rejected = await(text(engine.start(text(many.get("id")), largeInput).get("id")));
        assertEquals("FAILED", rejected.get("status")); assertTrue(text(rejected.get("error")).contains("1000"));
    }
    @Test void legacyDraftsAndPublishedVersionsGainTerminalsWithoutRewritingHistory() throws Exception {
        var legacy = new WorkflowGraph(List.of(node("entry", "manual", Map.of())), List.of());
        String original = write(legacy), id = text(store.create("旧工作流").get("id"));
        jdbc.update("UPDATE automation_workflow SET draft=?,published_version=1 WHERE id=?", original, id);
        jdbc.update("INSERT INTO automation_version(workflow_id,version,definition,runtimes,created_at) VALUES(?,1,?,'{}',?)", id, original, System.currentTimeMillis());
        String history = store.createRun(id, 1, legacy, empty()); store.finish(history, "SUCCEEDED", empty(), null);
        var adapted = WorkflowGraph.parse(store.get(id).get("definition"));
        assertEquals("entry", adapted.nodes().getFirst().id()); assertEquals("start", adapted.nodes().getFirst().type());
        var run = await(text(engine.start(id, empty()).get("id")));
        assertEquals("SUCCEEDED", run.get("status")); assertEquals(2, ((List<?>) run.get("nodes")).size());
        assertEquals(2, WorkflowGraph.parse(run.get("definition")).nodes().size());
        assertEquals(original, write(store.run(history).get("definition"))); assertEquals(1, ((List<?>)store.run(history).get("nodes")).size());
        assertEquals(original, jdbc.queryForObject("SELECT definition FROM automation_version WHERE workflow_id=? AND version=1", String.class, id));
        store.save(id, "旧工作流", adapted, 1);
        assertEquals(adapted, WorkflowGraph.parse(read(jdbc.queryForObject("SELECT draft FROM automation_workflow WHERE id=?", String.class, id))));
        assertEquals(1, store.versions(id).size());
    }
    @Test void publishedMixedLanguageWorkflowRunsHttpAndOnlyTheSelectedBranchDespiteDraftChanges() throws Exception {
        int requests = skippedRequests.get();
        var nodes = List.of(node("start", "start", Map.of()), node("http", "http", Map.of("method", "GET", "url", base + "/data")),
                script("js", "javascript", "function main(i,c) { console.log('js-step'); return {items:[{json:{...i.items[0].json.body,amount:i.items[0].json.body.amount + c.params.increment}}]}; }", Map.of("increment", 5)),
                script("groovy", "groovy", "def main(Map i,Map c) { println 'groovy-step'; return [items:i.items.collect{[json:it.json+[amount:it.json.amount*2]]}]}"),
                node("check", "if", Map.of("left", "{{ input.items[0].json.amount }}", "operator", "gte", "right", 200)),
                script("yes", "javascript", "function main(i,c){ return i; }"), node("no", "http", Map.of("method", "POST", "url", base + "/skipped")), node("end", "end", Map.of()));
        var graph = new WorkflowGraph(nodes, List.of(edge("start", "http", ""), edge("http", "js", ""), edge("js", "groovy", ""), edge("groovy", "check", ""), edge("check", "yes", "true"), edge("check", "no", "false"), edge("yes", "end", ""), edge("no", "end", "")));
        Map<String, Object> workflow = createPublished(graph);
        String id = text(workflow.get("id"));
        var changed = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("bad", "javascript", "function main(i,c){ throw Error('draft must not run'); }"), node("end", "end", Map.of())), List.of(edge("start", "bad", ""), edge("bad", "end", "")));
        store.save(id, "未发布修改", changed, ((Number) workflow.get("revision")).longValue());
        var started = request(post("/api/workflows/" + id + "/runs").content(write(Map.of("input", empty()))));
        var run = await(text(started.get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals(1, run.get("version"));
        var items = (List<?>) object(run.get("output")).get("items"); assertEquals(250, object(object(items.getFirst()).get("json")).get("amount"));
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>) run.get("nodes");
        assertEquals("SKIPPED", steps.stream().filter(n -> n.get("nodeId").equals("no")).findFirst().orElseThrow().get("status"));
        assertTrue(steps.stream().anyMatch(n -> text(n.get("logs")).contains("groovy-step"))); assertEquals(requests, skippedRequests.get());
        mvc.perform(get("/api/workflows/runs/" + run.get("id")).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.definition.nodes.length()").value(8));
    }
    @Test void rejectsInvalidPublishingAndStaleDraftsButAllowsIncompleteDrafts() throws Exception {
        var workflow = store.create("未连接草稿"); String id = text(workflow.get("id"));
        var disconnected = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("other", "javascript", "function main(i,c){return i;}"), node("end", "end", Map.of())), List.of());
        store.save(id, "未连接草稿", disconnected, 1);
        assertThrows(IllegalArgumentException.class, () -> store.publish(id, 2, write(engine.runtimes())));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.save(id, "过期", disconnected, 1));
        var cycle = new WorkflowGraph(disconnected.nodes(), List.of(edge("start", "other", ""), edge("other", "start", "")));
        assertThrows(IllegalArgumentException.class, cycle::validate);
        var unsupported = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("other", "python", "pass"), node("end", "end", Map.of())), List.of(edge("start", "other", ""), edge("other", "end", "")));
        assertThrows(IllegalArgumentException.class, unsupported::validate);
        mvc.perform(post("/api/workflows/" + id + "/publish").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
    @Test void recordsScriptFailuresRedactsSecretsAndCanCancelRunningProcesses() throws Exception {
        var graph = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("bad", "javascript", "function main(i,c){console.log('失败前日志',i.items[0].json.token);throw Error(i.items[0].json.token);}"), script("last", "groovy", "def main(Map i,Map c){return i}"), node("end", "end", Map.of())), List.of(edge("start", "bad", ""), edge("bad", "last", ""), edge("last", "end", "")));
        var workflow = createPublished(graph);
        var input = Map.<String, Object>of("items", List.of(Map.of("json", Map.of("token", "example-private-token"))));
        var run = await(text(engine.start(text(workflow.get("id")), input).get("id")));
        assertEquals("FAILED", run.get("status")); assertFalse(write(run).contains("example-private-token")); assertTrue(text(run.get("error")).contains("[已隐藏]"));
        assertTrue(write(run).contains("失败前日志"));
        var loop = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("loop", "groovy", "def main(Map i,Map c){while(true){}}"), node("end", "end", Map.of())), List.of(edge("start", "loop", ""), edge("loop", "end", "")));
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
    @Test void credentialEditingRequiresAnAdminSessionAndExactWriteOriginAndNeverReturnsSecrets() throws Exception {
        var created = request(post("/api/workflows/credentials").content(write(Map.of("name", "编辑测试", "kind", "basic", "value", Map.of("username", "old-user", "password", "test-edit-password")))));
        String endpoint = "/api/workflows/credentials/" + created.get("id");
        String update = write(Map.of("name", "已编辑", "kind", "basic", "value", Map.of("username", "new-user", "password", "")));
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization", "Bearer integration-test-token")).andExpect(status().isForbidden());
        mvc.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isUnauthorized());
        mvc.perform(put(endpoint).header("Authorization", "Bearer integration-test-token").contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isForbidden());
        mvc.perform(put(endpoint).cookie(cookie).header("Origin", "https://evil.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isForbidden());
        mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value.username").value("old-user")).andExpect(jsonPath("$.value.password").doesNotExist());
        var result = request(put(endpoint).content(update));
        assertEquals(created.get("id"), result.get("id")); assertEquals("已编辑", result.get("name")); assertEquals(Set.of("id", "name", "kind"), result.keySet());
        mvc.perform(get(endpoint).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.value.username").value("new-user")).andExpect(jsonPath("$.value.password").doesNotExist());
    }
    @Test void feishuCredentialsAreEncryptedAndOnlyMetadataIsReturnedByTheApi() throws Exception {
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/integration-example", secret = "integration-feishu-secret";
        var created = request(post("/api/workflows/credentials").content(write(Map.of("name", "飞书测试机器人", "kind", "feishu", "value", Map.of("webhookUrl", url, "secret", secret)))));
        assertEquals(Set.of("id", "name", "kind"), created.keySet()); assertEquals("feishu", created.get("kind"));
        String encrypted = jdbc.queryForObject("SELECT encrypted FROM automation_credential WHERE id=?", String.class, created.get("id"));
        assertNotNull(encrypted); assertFalse(encrypted.contains(url)); assertFalse(encrypted.contains(secret));
        String listed = mvc.perform(get("/api/workflows/credentials").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(listed.contains(url)); assertFalse(listed.contains(secret)); assertFalse(listed.contains("webhookUrl"));
        mvc.perform(post("/api/workflows/credentials").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                .content(write(Map.of("name", "无效地址", "kind", "feishu", "value", Map.of("webhookUrl", "http://127.0.0.1/hook"))))).andExpect(status().isBadRequest());
    }
    @Test void typedNodeTransportPreservesLiteralTemplateTextAfterResolution() throws Exception {
        var body = Map.of("content", Map.of("text", "{{ input.items[0].json.literal }}"));
        var response = http.executeResolved(Map.of("url", base + "/echo", "method", "POST", "body", body), new WorkflowPrivacy());
        assertEquals(body, object(object(((List<?>)response.get("items")).getFirst()).get("json")).get("body"));
    }
    @Test void marksInterruptedRunsFailedWithoutReplayingThemOnRestart() {
        var graph = WorkflowGraph.initial(); var workflow = createPublished(graph);
        String id = store.createRun(text(workflow.get("id")), 1, graph, empty());
        new WorkflowStore(jdbc).initialize();
        assertEquals("FAILED", store.run(id).get("status"));
    }
    @Test void scheduledRunsUsePublishedVersionsAndCarryTheirPlannedTimeWithoutDuplicateClaims() throws Exception {
        var graph = new WorkflowGraph(List.of(node("start", "start", Map.of()), script("js", "javascript", "function main(i,c){return {items:[{json:{value:i.items[0].json.value+1}}]};}"),
                script("groovy", "groovy", "def main(Map i,Map c){return [items:[[json:[value:i.items[0].json.value*2]]]]}"), node("end", "end", Map.of())), List.of(edge("start", "js", ""), edge("js", "groovy", ""), edge("groovy", "end", "")));
        var workflow = createPublished(graph); String id = text(workflow.get("id"));
        var config = schedules.save(id, schedule(true, 0), java.time.Instant.now());
        java.time.Instant due = java.time.Instant.parse(text(config.get("nextRunAt")));
        scheduler.tick(due.plusMillis(100)); scheduler.tick(due.plusMillis(200));
        assertEquals(1, store.runs(id).size());
        var run = await(text(store.runs(id).getFirst().get("id")));
        assertEquals("SUCCEEDED", run.get("status"), text(run.get("error"))); assertEquals("SCHEDULED", run.get("triggerType"));
        assertEquals(due.toString(), run.get("scheduledAt")); assertEquals(1, run.get("version"));
        assertEquals(42, object(object(((List<?>)object(run.get("output")).get("items")).getFirst()).get("json")).get("value"));
        store.save(id, "定时最新发布", WorkflowGraph.initial(), 2); store.publish(id, 3, write(engine.runtimes()));
        java.time.Instant following = java.time.Instant.parse(text(schedules.get(id).get("nextRunAt")));
        scheduler.tick(following);
        var newest = await(text(store.runs(id).getFirst().get("id")));
        assertEquals(2, newest.get("version")); assertEquals("SUCCEEDED", newest.get("status"));
        schedules.save(id, schedule(false, 1), following.plusSeconds(1));
    }
    @Test void schedulesValidatePublicationCronTimezoneAndOptimisticRevisionAndSupportPausing() throws Exception {
        String id = text(store.create("定时配置校验").get("id"));
        assertThrows(IllegalArgumentException.class, () -> schedules.save(id, schedule(true, 0), java.time.Instant.now()));
        var draft = schedule(false, 0);
        request(put("/api/workflows/" + id + "/schedule").content(write(draft)));
        assertFalse((Boolean)schedules.get(id).get("enabled")); assertNull(schedules.get(id).get("nextRunAt"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> schedules.save(id, draft, java.time.Instant.now()));
        for (var invalid : List.of(Map.of("cron", "invalid", "timeZone", "Asia/Shanghai"), Map.of("cron", "0 0 9 * * *", "timeZone", "Unknown/Zone")))
            mvc.perform(post("/api/workflows/schedule-preview").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content(write(invalid))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/workflows/schedule-preview").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON).content("{\"cron\":\"0 0 9 * * *\",\"timeZone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextRuns.length()").value(5));
        mvc.perform(put("/api/workflows/" + id + "/schedule").cookie(cookie).header("Origin", "https://evil.test").contentType(MediaType.APPLICATION_JSON).content(write(draft))).andExpect(status().isForbidden());
    }
    @Test void schedulingSkipsOverlapsAndDisablingOrDeletingStopsFutureTriggers() throws Exception {
        var workflow = createPublished(WorkflowGraph.initial()); String id = text(workflow.get("id"));
        var config = schedules.save(id, schedule(true, 0), java.time.Instant.now());
        String manual = store.createRun(id, 1, WorkflowGraph.initial(), empty());
        java.time.Instant due = java.time.Instant.parse(text(config.get("nextRunAt")));
        scheduler.tick(due);
        assertEquals(1, store.runs(id).size()); assertEquals("SKIPPED", schedules.get(id).get("lastOutcome"));
        assertTrue(java.time.Instant.parse(text(schedules.get(id).get("nextRunAt"))).isAfter(due));
        schedules.save(id, schedule(false, 1), due); scheduler.tick(due.plusSeconds(120));
        assertEquals(1, store.runs(id).size()); assertEquals("QUEUED", store.run(manual).get("status"));
        store.finish(manual, "CANCELLED", null, null);
        schedules.save(id, schedule(true, 2), due); store.delete(id); scheduler.tick(due.plusSeconds(120));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM automation_schedule WHERE workflow_id=?", Boolean.class, id));
        var history = store.allRuns(id, null, null, null, 1, 20);
        assertEquals(1L, history.get("total")); assertTrue((Boolean)object(((List<?>)history.get("items")).getFirst()).get("workflowDeleted"));
    }
    @Test void racingSchedulePollersCreateExactlyOneQueuedInstanceAndRestartSkipsMissedTimes() throws Exception {
        var workflow = createPublished(WorkflowGraph.initial()); String id = text(workflow.get("id"));
        var config = schedules.save(id, schedule(true, 0), java.time.Instant.now());
        java.time.Instant time = java.time.Instant.parse(text(config.get("nextRunAt")));
        var due = new WorkflowSchedules.Due(id, time.toEpochMilli());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return schedules.claim(due, time); });
            var second = pool.submit(() -> { start.await(); return schedules.claim(due, time); }); start.countDown();
            assertEquals(1, (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1));
        }
        var run = store.runs(id).getFirst(); store.finish(text(run.get("id")), "CANCELLED", null, null);
        var saved = schedules.get(id); new WorkflowStore(jdbc).initialize();
        assertEquals(saved.get("nextRunAt"), schedules.get(id).get("nextRunAt"));
        var missed = new WorkflowSchedules.Due(id, java.time.Instant.parse(text(saved.get("nextRunAt"))).toEpochMilli());
        java.time.Instant restarted = time.plusSeconds(3600); schedules.skipMissed(missed, restarted);
        assertEquals("MISSED", schedules.get(id).get("lastOutcome"));
        assertTrue(java.time.Instant.parse(text(schedules.get(id).get("nextRunAt"))).isAfter(restarted)); assertEquals(1, store.runs(id).size());
        schedules.save(id, schedule(false, 1), restarted);
    }
    @Test void globalInstancesArePaginatedFilteredAndExcludePayloadsUntilDetailIsRequested() throws Exception {
        var workflow = createPublished(WorkflowGraph.initial()); String id = text(workflow.get("id"));
        for (int i = 0; i < 25; i++) {
            String run = store.createRun(id, 1, WorkflowGraph.initial(), empty(), i % 2 == 0 ? "SCHEDULED" : "MANUAL", i % 2 == 0 ? System.currentTimeMillis() : null);
            store.finish(run, i % 3 == 0 ? "FAILED" : "SUCCEEDED", empty(), i % 3 == 0 ? "测试失败" : null);
        }
        var first = store.allRuns(id, null, null, null, 1, 20);
        var second = store.allRuns(id, null, null, null, 2, 20);
        assertEquals(25L, first.get("total")); assertEquals(20, ((List<?>)first.get("items")).size()); assertEquals(5, ((List<?>)second.get("items")).size());
        Set<Object> ids = new HashSet<>(); for (Object row : (List<?>)first.get("items")) assertTrue(ids.add(object(row).get("id")));
        for (Object row : (List<?>)second.get("items")) assertTrue(ids.add(object(row).get("id")));
        assertEquals(13L, store.allRuns(id, null, "SCHEDULED", null, 1, 20).get("total"));
        assertEquals(9L, store.allRuns(id, "FAILED", null, null, 1, 20).get("total"));
        assertEquals(0L, store.allRuns(id, null, null, "%_!", 1, 20).get("total"));
        mvc.perform(get("/api/workflows/runs").param("workflowId", id).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.items[0].workflowName").exists()).andExpect(jsonPath("$.items[0].triggerType").exists()).andExpect(jsonPath("$.items[0].input").doesNotExist()).andExpect(jsonPath("$.items[0].definition").doesNotExist());
        mvc.perform(get("/api/workflows/runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows/runs").cookie(cookie).param("status", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workflows/runs").cookie(cookie).param("pageSize", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/workflow-runs.html").cookie(cookie)).andExpect(status().isOk());
    }
    private static Map<String, Object> schedule(boolean enabled, int revision) {
        return Map.of("enabled", enabled, "cron", "*/5 * * * * *", "timeZone", "Asia/Shanghai", "revision", revision,
                "input", Map.of("items", List.of(Map.of("json", Map.of("value", 20)))));
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
