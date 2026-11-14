package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptProcessRunnerTest {
    @Test void mountsOnlyNamedPublicResourcesFromDistributionJdkSymlinks() throws Exception {
        var root = java.nio.file.Files.createTempDirectory("jdk-resource-test-");
        try {
            var jdk = java.nio.file.Files.createDirectories(root.resolve("jdk/lib" )).getParent();
            var external = java.nio.file.Files.createDirectories(root.resolve("external/security" )).getParent();
            var timezone = java.nio.file.Files.writeString(root.resolve("tzdb.dat"), "timezone-data");
            var policy = java.nio.file.Files.writeString(external.resolve("security/java.security"), "public-policy");
            var privateFile = java.nio.file.Files.writeString(external.resolve("private-app.env"), "must-not-be-mounted");
            java.nio.file.Files.createSymbolicLink(jdk.resolve("conf"), external);
            java.nio.file.Files.createSymbolicLink(jdk.resolve("lib/tzdb.dat"), timezone);
            assertEquals(Set.of(timezone.toRealPath(), policy.toRealPath()), ScriptProcessRunner.externalJdkResources(jdk.toRealPath()));
            assertFalse(ScriptProcessRunner.externalJdkResources(jdk).contains(privateFile));
        } finally {
            try (var files = java.nio.file.Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
    private final Map<String, Object> input = Map.of("items", List.of(Map.of("json", Map.of("name", "leon中文", "amount", 120))));
    @Test void runsBothLanguagesWithTheSameJsonContractAndBoundedLogs() throws Exception {
        try (var runner = new Runner()) {
            var js = runner.execute("javascript", "function main(input, ctx) { console.log('js-ok 中文'); return {items: input.items.map(i => ({json: {name: i.json.name.toUpperCase(), amount: i.json.amount + ctx.params.increment}}))}; }", input, Map.of("params", Map.of("increment", 5)), 15000);
            var groovy = runner.execute("groovy", "def main(Map input, Map ctx) { println 'groovy-ok 中文'; return [items: input.items.collect { [json: [name: it.json.name.toUpperCase(), amount: it.json.amount + ctx.params.increment]] }] }", input, Map.of("params", Map.of("increment", 5)), 15000);
            assertEquals(js.output(), groovy.output()); assertTrue(js.logs().contains("js-ok 中文")); assertTrue(groovy.logs().contains("groovy-ok 中文"));
            assertTrue(WorkflowJson.write(js.output()).contains("LEON中文"));
        }
    }
    @Test void killsInfiniteLoopsAndRejectsInvalidOutput() throws Exception {
        try (var runner = new Runner()) {
            var timeout = assertThrows(IllegalArgumentException.class, () -> runner.execute("groovy", "def main(Map input, Map ctx) { while(true) {} }", input, Map.of(), 2500));
            assertTrue(timeout.getMessage().contains("超时"));
            assertThrows(IllegalArgumentException.class, () -> runner.execute("javascript", "function main(input, ctx) { return 42; }", input, Map.of(), 15000));
        }
    }
    @Test void groovyCannotReadHostFilesOrStartHostCommandsAndJsCannotAccessJava() throws Exception {
        try (var runner = new Runner()) {
            assertThrows(IllegalArgumentException.class, () -> runner.execute("groovy", "def main(Map i, Map c) { return [items:[[json:[data:new File('/etc/passwd').text]]]] }", input, Map.of(), 15000));
            assertThrows(IllegalArgumentException.class, () -> runner.execute("groovy", "def main(Map i, Map c) { return [items:[[json:[data:'/usr/bin/id'.execute().text]]]] }", input, Map.of(), 15000));
            assertThrows(IllegalArgumentException.class, () -> runner.execute("javascript", "function main(i,c) { Java.type('java.lang.System').exit(0); return i; }", input, Map.of(), 15000));
        }
    }
    private static class Runner extends ScriptProcessRunner implements AutoCloseable {}
}
