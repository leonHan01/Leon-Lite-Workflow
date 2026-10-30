package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.dao.entity.SimpleKV;
import com.leon.workflow.dao.repository.SimpleKVRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class H2PersistenceTest {
    @TempDir Path directory;

    @Test void fileDatabaseSurvivesApplicationRestart() {
        String url = "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("workflow");
        try (ConfigurableApplicationContext context = start(url)) {
            SimpleKV item = new SimpleKV();
            item.setKvKey("persistent-key");
            item.setKvValue("persistent-value");
            context.getBean(SimpleKVRepository.class).saveAndFlush(item);
        }
        try (ConfigurableApplicationContext context = start(url)) {
            assertEquals("persistent-value", context.getBean(SimpleKVRepository.class).findByKvKey("persistent-key").getKvValue());
        }
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(HelloServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(url, "--spring.jpa.hibernate.ddl-auto=update");
    }
}
