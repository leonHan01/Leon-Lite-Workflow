package com.leon.workflow.messaging;

import com.example.helloservice.HelloServiceApplication;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:device-message-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "leon.workflows.scheduling-enabled=false",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class DeviceMessageIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired DeviceMessageStore store;
    @Autowired JdbcTemplate jdbc;
    private static final String AUTH = "Bearer integration-test-token";
    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }
    private DeviceMessageStore.Send input(String text) {
        return new DeviceMessageStore.Send(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Mac", "macos", text);
    }
    @BeforeEach void clean() { jdbc.update("DELETE FROM device_message"); }

    @Test void nativeClientsSendAndReceiveThroughAuthenticatedApi() throws Exception {
        String body = "{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"deviceId\":\"" + UUID.randomUUID()
                + "\",\"sender\":\"手机\",\"platform\":\"android\",\"text\":\"你好 Mac\\n第二行\"}";
        mvc().perform(get("/api/device-messages")).andExpect(status().isUnauthorized());
        mvc().perform(post("/api/device-messages").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/device-messages").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc().perform(post("/api/device-messages").header("Authorization", AUTH).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platform").value("android"));
        mvc().perform(get("/api/device-messages").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.messages[0].text").value("你好 Mac\n第二行"));
        mvc().perform(post("/api/device-messages").header("Authorization", AUTH).contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertEquals(1, store.page(null, null, 100).messages().size());
    }

    @Test void paginatesWithoutSkippingAndUsesLatestPageOnInitialLoad() {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 105; i++) ids.add(store.send(input("message " + i)).id());
        var latest = store.page(null, null, 100);
        assertTrue(latest.hasMore());
        assertEquals(ids.subList(5, 105), latest.messages().stream().map(DeviceMessageStore.Message::id).toList());
        var older = store.page(null, latest.messages().getFirst().id(), 100);
        assertFalse(older.hasMore());
        assertEquals(ids.subList(0, 5), older.messages().stream().map(DeviceMessageStore.Message::id).toList());
        var first = store.page(0L, null, 100);
        var rest = store.page(first.messages().getLast().id(), null, 100);
        assertTrue(first.hasMore()); assertFalse(rest.hasMore());
        assertEquals(ids.subList(100, 105), rest.messages().stream().map(DeviceMessageStore.Message::id).toList());
    }

    @Test void retriesAreIdempotentAndCannotChangeContent() {
        var send = input("hi");
        var saved = store.send(send);
        assertEquals(saved, store.send(send));
        var conflict = new DeviceMessageStore.Send(send.clientMessageId(), send.deviceId(), send.sender(), send.platform(), "changed");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.send(conflict));
        assertEquals(1, store.page(null, null, 100).messages().size());
    }

    @Test void concurrentRetriesCreateOneRowAndRestartPreservesMessages() throws Exception {
        var send = input("并发消息 🚀");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 24; i++) tasks.add(() -> store.send(send).id());
            var ids = new HashSet<Long>();
            for (var future : pool.invokeAll(tasks)) ids.add(future.get());
            assertEquals(1, ids.size());
        }
        var restarted = new DeviceMessageStore(jdbc);
        restarted.initialize();
        assertEquals("并发消息 🚀", restarted.page(null, null, 100).messages().getFirst().text());
    }

    @Test void rejectsInvalidBodiesAndPagination() throws Exception {
        var original = input("hi");
        for (String text : List.of("", "  \n", "x".repeat(8001))) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> store.send(new DeviceMessageStore.Send(original.clientMessageId(), original.deviceId(), "Mac", "macos", text)));
        }
        for (String query : List.of("?after=-1", "?limit=101", "?limit=0", "?before=0", "?after=0&before=1", "?after=abc"))
            mvc().perform(get("/api/device-messages" + query).header("Authorization", AUTH)).andExpect(status().isBadRequest());
        mvc().perform(post("/api/device-messages").header("Authorization", AUTH).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertTrue(store.page(null, null, 100).messages().isEmpty());
    }
}
