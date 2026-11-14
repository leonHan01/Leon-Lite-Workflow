package com.leon.workflow.automation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.leon.workflow.automation.WorkflowJson.*;

class WorkflowSchemaMigrationTest {
    @Test void addsSchedulingMetadataWithoutReplacingExistingWorkflowsVersionsOrRuns() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:legacy-workflows-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        try {
            var store = new WorkflowStore(jdbc); store.initialize();
            String workflow = text(store.create("原有工作流").get("id")); store.publish(workflow, 1, "{}");
            var input = Map.of("items", List.of(Map.of("json", Map.of("legacy", "preserved"))));
            String run = store.createRun(workflow, 1, WorkflowGraph.initial(), input); store.finish(run, "SUCCEEDED", input, null);
            jdbc.execute("DROP TABLE automation_schedule"); jdbc.execute("ALTER TABLE automation_run DROP COLUMN trigger_type"); jdbc.execute("ALTER TABLE automation_run DROP COLUMN scheduled_at");
            new WorkflowStore(jdbc).initialize();
            var migrated = store.run(run);
            assertEquals("SUCCEEDED", migrated.get("status")); assertEquals("MANUAL", migrated.get("triggerType"));
            assertEquals(input, migrated.get("input")); assertEquals(input, migrated.get("output"));
            assertEquals(1, store.get(workflow).get("publishedVersion")); assertEquals(1, store.versions(workflow).size());
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
