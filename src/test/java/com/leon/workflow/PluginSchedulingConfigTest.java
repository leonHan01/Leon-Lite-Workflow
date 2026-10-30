package com.leon.workflow;

import com.leon.workflow.service.TodoDigestScheduler;
import com.leon.workflow.service.TodoDigestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PluginSchedulingConfigTest {
    @Test void schedulingIsOptInAndRegistersShanghaiCronJobs() throws Exception {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PluginSchedulingConfig.class)
                .withBean(TodoDigestService.class, () -> mock(TodoDigestService.class))
                .withBean(TodoDigestScheduler.class);
        runner.run(context -> assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty()));
        runner.withPropertyValues("leon.plugin.digest-scheduling-enabled=true").run(context ->
                assertEquals(4, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size()));
        for (var entry : Map.of("statsAtTen", "0 0 10 * * *", "statsAtFourteen", "0 0 14 * * *",
                "statsAtTwenty", "0 0 20 * * *", "summaryAtMidnight", "0 0 0 * * *").entrySet()) {
            Scheduled schedule = TodoDigestScheduler.class.getMethod(entry.getKey()).getAnnotation(Scheduled.class);
            assertEquals(entry.getValue(), schedule.cron());
            assertEquals("Asia/Shanghai", schedule.zone());
        }
    }
}
