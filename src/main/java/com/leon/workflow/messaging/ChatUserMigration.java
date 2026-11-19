package com.leon.workflow.messaging;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** Records ownership of existing single-user data without deleting or rewriting message contents. */
@Component
@DependsOn({"chatUserRegistry","deviceMessageStore","workflowStore"})
public class ChatUserMigration {
    private final JdbcTemplate jdbc;
    private final ChatUserRegistry users;
    private final TransactionTemplate transaction;
    public ChatUserMigration(JdbcTemplate jdbc,ChatUserRegistry users){
        this.jdbc=jdbc;this.users=users;
        this.transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    @PostConstruct void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_user_migration (name VARCHAR(100) PRIMARY KEY, completed_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_conversation_member (conversation_id VARCHAR(80) NOT NULL, user_id VARCHAR(36) NOT NULL, joined_at BIGINT NOT NULL, PRIMARY KEY(conversation_id,user_id), FOREIGN KEY(conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE, FOREIGN KEY(user_id) REFERENCES chat_user(id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversation_member_user ON chat_conversation_member(user_id,conversation_id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_user_model_usage (user_id VARCHAR(36) NOT NULL, provider VARCHAR(20) NOT NULL, model VARCHAR(100) NOT NULL, input_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL, total_tokens BIGINT NOT NULL, reported_calls BIGINT NOT NULL, unreported_calls BIGINT NOT NULL, first_recorded_at BIGINT NOT NULL, last_recorded_at BIGINT NOT NULL, PRIMARY KEY(user_id,provider,model), FOREIGN KEY(user_id) REFERENCES chat_user(id))");
        transaction.executeWithoutResult(status->{
            if(jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_migration WHERE name='single-user-to-leon-v1'",Integer.class)>0)return;
            String leon=users.getOrCreate("Leon").id();long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO chat_conversation_member(conversation_id,user_id,joined_at) SELECT id,?,? FROM chat_conversation",leon,now);
            jdbc.update("UPDATE chat_conversation SET owner_id=? WHERE owner_id IS NULL",leon);
            jdbc.update("UPDATE chat_context_operation SET user_id=? WHERE user_id IS NULL",leon);
            jdbc.update("UPDATE device_message SET user_id=? WHERE user_id IS NULL",leon);
            jdbc.update("UPDATE chat_agent_job SET user_id=? WHERE user_id IS NULL",leon);
            jdbc.update("INSERT INTO chat_user_model_usage(user_id,provider,model,input_tokens,output_tokens,total_tokens,reported_calls,unreported_calls,first_recorded_at,last_recorded_at) SELECT ?,provider,model,input_tokens,output_tokens,total_tokens,reported_calls,unreported_calls,first_recorded_at,last_recorded_at FROM automation_model_usage",leon);
            jdbc.update("INSERT INTO chat_user_migration(name,completed_at) VALUES('single-user-to-leon-v1',?)",now);
        });
    }
}
