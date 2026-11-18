package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatImagePersistenceTest {
    @TempDir Path directory;
    @Test void storedBytesMetadataAndMessageAssociationSurviveDatabaseReopen() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("images");String id=UUID.randomUUID().toString();byte[] expected;
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var store=new DeviceMessageStore(jdbc);store.initialize();var images=new ChatImageService(jdbc,store);images.initialize();
        images.upload(id,"shared",ChatImageIntegrationTest.png(40,20));expected=images.bytes(id);
        store.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos","","shared",List.of(),List.of(id)));jdbc.execute("SHUTDOWN");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));store=new DeviceMessageStore(jdbc);store.initialize();images=new ChatImageService(jdbc,store);images.initialize();
        assertArrayEquals(expected,images.bytes(id));assertEquals(id,store.page(null,null,100).messages().getFirst().images().getFirst().id());jdbc.execute("SHUTDOWN");
    }
}
