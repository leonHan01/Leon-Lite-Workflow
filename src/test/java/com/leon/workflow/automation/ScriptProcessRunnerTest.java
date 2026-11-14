package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptProcessRunnerTest {
    private final Map<String, Object> input = Map.of("items", List.of(Map.of("json", Map.of("name", "leon", "amount", 120))));
    @Test void runsBothLanguagesWithTheSameJsonContractAndBoundedLogs() throws Exception {
        try (var runner = new Runner()) {
            var js = runner.execute("javascript", "function main(input, ctx) { console.log('js-ok'); return {items: input.items.map(i => ({json: {name: i.json.name.toUpperCase(), amount: i.json.amount + ctx.params.increment}}))}; }", input, Map.of("params", Map.of("increment", 5)), 15000);
            var groovy = runner.execute("groovy", "def main(Map input, Map ctx) { println 'groovy-ok'; return [items: input.items.collect { [json: [name: it.json.name.toUpperCase(), amount: it.json.amount + ctx.params.increment]] }] }", input, Map.of("params", Map.of("increment", 5)), 15000);
            assertEquals(js.output(), groovy.output()); assertTrue(js.logs().contains("js-ok")); assertTrue(groovy.logs().contains("groovy-ok"));
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
