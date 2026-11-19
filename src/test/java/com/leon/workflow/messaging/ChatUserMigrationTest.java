package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatUserMigrationTest {
    @Test void existingMessagesConversationsJobsAndUsageAreAssignedOnce(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var store=new DeviceMessageStore(jdbc);store.initialize();var users=new ChatUserRegistry(jdbc);users.initialize();
        jdbc.execute("CREATE TABLE automation_model_usage (provider VARCHAR(20),model VARCHAR(100),input_tokens BIGINT,output_tokens BIGINT,total_tokens BIGINT,reported_calls BIGINT,unreported_calls BIGINT,first_recorded_at BIGINT,last_recorded_at BIGINT,PRIMARY KEY(provider,model))");
        jdbc.update("INSERT INTO automation_model_usage VALUES('openai','test-model',10,20,30,1,0,100,200)");
        var message=store.send(new DeviceMessageStore.Send(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Mac","macos","保留历史"));
        String agent=UUID.randomUUID().toString();jdbc.update("INSERT INTO chat_agent_job(message_id,agent_id,conversation_id,agent_name,status) VALUES(?,?,'shared','Agent','FAILED')",message.id(),agent);
        var migration=new ChatUserMigration(jdbc,users);migration.initialize();String leon=users.getOrCreate("Leon").id();
        assertEquals(leon,jdbc.queryForObject("SELECT user_id FROM device_message WHERE id=?",String.class,message.id()));
        assertEquals(leon,jdbc.queryForObject("SELECT user_id FROM chat_agent_job WHERE message_id=?",String.class,message.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_member WHERE conversation_id='shared' AND user_id=?",Integer.class,leon));
        assertEquals(30L,jdbc.queryForObject("SELECT total_tokens FROM chat_user_model_usage WHERE user_id=?",Long.class,leon));
        migration.initialize();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_model_usage",Integer.class));
        assertEquals("保留历史",store.page(null,null,100).messages().getFirst().text());
    }
}
