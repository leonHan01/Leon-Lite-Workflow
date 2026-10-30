package com.leon.workflow.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlfredIconServiceTest {
    @TempDir Path directory;

    @Test
    void listsPngsAndReadsOnlyFilesInsideConfiguredDirectory() throws Exception {
        Path icons = Files.createDirectory(directory.resolve("icons"));
        Files.write(icons.resolve("b.png"), new byte[]{1, 2});
        Files.write(icons.resolve("A.PNG"), new byte[]{3});
        Files.writeString(icons.resolve("ignored.txt"), "text");
        Files.writeString(directory.resolve("outside.png"), "outside");
        Files.createSymbolicLink(icons.resolve("escape.png"), directory.resolve("outside.png"));
        AlfredIconService service = new AlfredIconService(icons.toString());

        assertTrue(service.list().containsAll(List.of("A.PNG", "b.png", "escape.png", "github.png")));
        assertArrayEquals(new byte[]{1, 2}, service.read("b.png").orElseThrow());
        assertTrue(service.read("../outside.png").isEmpty());
        assertTrue(service.read("escape.png").isEmpty());
        assertTrue(service.read("missing.png").isEmpty());
    }

    @Test
    void bundledIconsWorkWithoutAnAlfredDirectoryAndCustomIconsOverrideThem() throws Exception {
        Path icons = directory.resolve("missing");
        AlfredIconService service = new AlfredIconService(icons.toString());
        List<String> names = service.list();
        assertTrue(names.size() >= 100);
        assertTrue(names.containsAll(List.of("github.png", "link.png", "日历.png")));
        for (String name : names) {
            byte[] png = service.read(name).orElseThrow();
            assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}, java.util.Arrays.copyOf(png, 4), name);
        }
        Files.createDirectory(icons);
        Files.write(icons.resolve("github.png"), new byte[]{7, 8});
        assertArrayEquals(new byte[]{7, 8}, service.read("github.png").orElseThrow());
        assertEquals(1, service.list().stream().filter("github.png"::equals).count());
    }
}
