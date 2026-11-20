package com.leon.workflow.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChatUserRegistryTest {
    @TempDir Path dir;
    @Test void usersAndRevocableSessionsPersistWithoutStoringTokens(){
        String url="jdbc:h2:file:"+dir.resolve("users");var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var users=new ChatUserRegistry(jdbc);users.initialize();var leon=users.getOrCreate("leon");assertEquals("Leon",leon.username());
        var login=users.login(" Alice ");assertEquals("Alice",login.user().username());assertEquals(login.user(),users.authenticate(login.sessionToken()));
        users.updateBackground(login.user().id(),"中文背景",0L);
        assertNotEquals(login.sessionToken(),jdbc.queryForObject("SELECT token_hash FROM chat_user_session",String.class));
        var initial=users;assertThrows(Exception.class,()->initial.authenticate("bad"));assertThrows(Exception.class,()->initial.login("\n"));
        jdbc.execute("SHUTDOWN");jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));users=new ChatUserRegistry(jdbc);users.initialize();
        assertEquals(login.user(),users.authenticate(login.sessionToken()));assertEquals(new ChatUserRegistry.Background("中文背景",1),users.background(login.user().id()));users.logout(login.sessionToken());
        var reopened=users;assertThrows(Exception.class,()->reopened.authenticate(login.sessionToken()));jdbc.execute("SHUTDOWN");
    }
    @Test void addsBackgroundToExistingUsersWithoutChangingThem(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:background-migration;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE chat_user (id VARCHAR(36) PRIMARY KEY, username VARCHAR(60) NOT NULL, username_key VARCHAR(60) NOT NULL UNIQUE, created_at BIGINT NOT NULL)");
        jdbc.update("INSERT INTO chat_user VALUES(?,?,?,?)","existing-id","Existing","existing",123L);
        var registry=new ChatUserRegistry(jdbc);registry.initialize();
        assertEquals(new ChatUserRegistry.Background("",0),registry.background("existing-id"));
        assertEquals("Existing",jdbc.queryForObject("SELECT username FROM chat_user WHERE id='existing-id'",String.class));
    }
}
