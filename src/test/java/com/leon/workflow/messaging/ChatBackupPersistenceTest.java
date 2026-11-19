package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBackupPersistenceTest {
    @TempDir Path directory;
    @Test void restoredHistoryAndImportIdentitySurviveDatabaseReopen()throws Exception{
        String url="jdbc:h2:file:"+directory.resolve("chat-backup");var json=new ObjectMapper();
        var agent=new AgentRegistry.Agent(UUID.randomUUID().toString(),"Sol","sol","openai","test-model","",true,true,1);
        var agents=mock(AgentRegistry.class);when(agents.get(agent.id())).thenReturn(agent);when(agents.list()).thenReturn(List.of(agent));
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var store=new DeviceMessageStore(jdbc);store.initialize();var images=new ChatImageService(jdbc,store);images.initialize();
        var backups=new ChatBackupService(store,images,agents,jdbc,json);backups.initialize();
        var source=store.direct(agent);String image=UUID.randomUUID().toString();images.upload(image,source.id(),ChatImageIntegrationTest.png(24,12));
        store.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"手机","android","持久化记录",source.id(),List.of(),List.of(image)));
        byte[] archive=backups.export(source.id()),expected=images.bytes(image);String request=UUID.randomUUID().toString();var restored=backups.restore(request,null,archive);
        jdbc.execute("SHUTDOWN");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));store=new DeviceMessageStore(jdbc);store.initialize();images=new ChatImageService(jdbc,store);images.initialize();
        backups=new ChatBackupService(store,images,agents,jdbc,json);backups.initialize();
        assertEquals(restored,backups.restore(request,null,archive));assertEquals(3,store.conversations().size());
        var message=store.page(restored.id(),null,null,100).messages().getFirst();assertEquals("持久化记录",message.text());assertArrayEquals(expected,images.bytes(message.images().getFirst().id()));
        jdbc.execute("SHUTDOWN");
    }
}
