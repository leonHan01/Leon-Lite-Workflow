package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.leon.workflow.automation.WorkflowJson.*;

class WorkflowGraphTest {
    private static WorkflowGraph.Node node(String id, String type, Map<String, Object> config) { return new WorkflowGraph.Node(id, type, id, config, Map.of()); }
    private static WorkflowGraph.Node script(String id, String language, String source) { return node(id, "script", Map.of("language", language, "source", source)); }
    private static WorkflowGraph.Edge edge(String source, String target, String branch) { return new WorkflowGraph.Edge(source + "_" + target, source, target, branch); }
    @Test void requiresExactlyOneStartAndEndEvenForIncompleteDrafts() {
        var start = node("start", "start", Map.of());
        var end = node("end", "end", Map.of());
        var action = script("action", "javascript", "function main(i,c){return i;}");
        for (var nodes : List.of(List.of(start, action), List.of(action, end),
                List.of(start, node("anotherStart", "start", Map.of()), end),
                List.of(start, end, node("anotherEnd", "end", Map.of())),
                List.of(start, end, node("legacyStart", "manual", Map.of())))) {
            assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, List.of()).validateDraft());
        }
        var draft = new WorkflowGraph(List.of(start, end, node("unfinished", "http", Map.of())), List.of());
        assertDoesNotThrow(draft::validateDraft);
        assertThrows(IllegalArgumentException.class, draft::validate);
        assertDoesNotThrow(() -> WorkflowGraph.initial().validate());
    }

    @Test void everyBranchMustReachTheSharedEndAndOtherJoinsRemainUnsupported() {
        var nodes = List.of(node("start", "start", Map.of()), node("check", "if", Map.of("operator", "truthy")),
                script("action", "javascript", "function main(i,c){return i;}"), node("end", "end", Map.of()));
        var edges = List.of(edge("start", "check", ""), edge("check", "action", "true"), edge("check", "end", "false"), edge("action", "end", ""));
        assertDoesNotThrow(() -> new WorkflowGraph(nodes, edges).validate());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, edges.subList(0, 3)).validate());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, List.of(edge("start", "check", ""),
                edge("check", "action", "true"), new WorkflowGraph.Edge("false_action", "check", "action", "false"), edge("action", "end", ""))).validate());
        var outOfEnd = new ArrayList<>(edges); outOfEnd.add(edge("end", "action", ""));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, outOfEnd).validate());
        var intoStart = new ArrayList<>(edges); intoStart.add(edge("end", "start", ""));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, intoStart).validate());
    }

    @Test void oneOutletCanReachMultipleNodesButDuplicateConnectionsAreRejected() {
        var nodes = List.of(node("start", "start", Map.of()), script("left", "javascript", "function main(i,c){return i;}"),
                script("right", "javascript", "function main(i,c){return i;}"), node("end", "end", Map.of()));
        var edges = List.of(edge("start", "left", ""), edge("start", "right", ""), edge("left", "end", ""), edge("right", "end", ""));
        assertDoesNotThrow(() -> new WorkflowGraph(nodes, edges).validate());
        var duplicate = new ArrayList<>(edges); duplicate.add(new WorkflowGraph.Edge("duplicate", "start", "left", ""));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(nodes, duplicate).validate());
        var disconnectedCycle = new WorkflowGraph(nodes, List.of(edge("start", "end", ""), edge("left", "right", ""), edge("right", "left", "")));
        assertThrows(IllegalArgumentException.class, disconnectedCycle::validate);
    }

    @Test void adaptsLegacyLeavesAndMissingBranchesWithoutChangingIdsConfigOrOriginalDefinition() {
        var legacy = new WorkflowGraph(List.of(node("entry", "manual", Map.of()),
                node("check", "if", Map.of("operator", "truthy")), node("end", "http", Map.of("url", "https://example.test"))),
                List.of(edge("entry", "check", ""), edge("check", "end", "true")));
        String original = write(legacy);
        var adapted = WorkflowGraph.stored(read(original)).validate();
        assertEquals("start", adapted.nodes().getFirst().type());
        assertEquals(legacy.nodes().getFirst().id(), adapted.nodes().getFirst().id());
        assertEquals(legacy.nodes().get(2), adapted.nodes().get(2));
        assertTrue(adapted.edges().containsAll(legacy.edges()));
        var terminal = adapted.nodes().getLast(); assertEquals("end_1", terminal.id());
        assertTrue(adapted.edges().stream().anyMatch(e -> e.source().equals("check") && e.branch().equals("false") && e.target().equals(terminal.id())));
        assertTrue(adapted.edges().stream().anyMatch(e -> e.source().equals("end") && e.target().equals(terminal.id())));
        assertEquals(original, write(legacy));
        assertEquals(adapted, WorkflowGraph.stored(read(write(adapted))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowGraph.parse(read(original)));
    }

    @Test void legacyWorkflowsAtTheOldNodeLimitStillRunAfterAddingTheTerminal() {
        List<WorkflowGraph.Node> nodes = new ArrayList<>();
        List<WorkflowGraph.Edge> edges = new ArrayList<>();
        nodes.add(node("entry", "manual", Map.of()));
        for (int i = 1; i < 64; i++) {
            nodes.add(script("n" + i, "javascript", "function main(i,c){return i;}"));
            edges.add(edge(nodes.get(i - 1).id(), "n" + i, ""));
        }
        var adapted = WorkflowGraph.stored(read(write(new WorkflowGraph(nodes, edges))));
        assertEquals(65, adapted.nodes().size()); assertDoesNotThrow(adapted::validate);
    }
}
