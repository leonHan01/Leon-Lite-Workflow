package com.leon.workflow.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentHistoryServiceTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private void release(int number, String status) throws Exception {
        var time = Instant.parse("2026-11-11T16:00:00Z").plusSeconds(number);
        var record = new DeploymentHistoryService.Deployment("release-" + number, time,
                status.equals("DEPLOYING") ? null : time.plusSeconds(30), "2026.11.12." + number,
                "bbd8d1f42d7853278c4bda200ec78f0fac430234", false, "发布测试", status, "a".repeat(64));
        Files.writeString(root.resolve("release-" + number + ".json"), mapper.writeValueAsString(record));
    }

    @Test void retainsHistoryAcrossServiceRecreationAndLimitsOnlyTheResponse() throws Exception {
        for (int index = 0; index < 55; index++) release(index, index == 54 ? "ROLLED_BACK" : "SUCCESS");
        var first = new DeploymentHistoryService(mapper, root.toString(), "release-53").history();
        assertEquals(55, first.total());
        assertEquals(50, first.records().size());
        assertEquals("release-54", first.records().getFirst().id());
        assertEquals("release-53", first.currentId());
        assertEquals("ROLLED_BACK", first.records().getFirst().status());
        assertEquals(first, new DeploymentHistoryService(mapper, root.toString(), "release-53").history());
        try (var files = Files.list(root)) { assertEquals(55, files.count()); }
    }

    @Test void ignoresPartialWritesAndReportsMalformedRecordsWithoutDroppingValidOnes() throws Exception {
        release(1, "DEPLOYING");
        Files.writeString(root.resolve("bad.json"), "{broken");
        Files.writeString(root.resolve("huge.json"), "x".repeat(16385));
        Files.writeString(root.resolve("upload.tmp"), "{partial");
        var history = new DeploymentHistoryService(mapper, root.toString(), "release-1").history();
        assertTrue(history.available());
        assertEquals(2, history.unreadable());
        assertEquals(1, history.total());
        assertNull(history.records().getFirst().finishedAt());
    }

    @Test void distinguishesEmptyHistoryFromUnreadableStorage() throws Exception {
        assertTrue(new DeploymentHistoryService(mapper, root.resolve("missing").toString(), "").history().available());
        Path file = Files.writeString(root.resolve("not-a-directory"), "");
        assertFalse(new DeploymentHistoryService(mapper, file.toString(), "").history().available());
    }
}
