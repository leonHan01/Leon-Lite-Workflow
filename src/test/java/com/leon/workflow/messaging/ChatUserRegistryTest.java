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
        assertNotEquals(login.sessionToken(),jdbc.queryForObject("SELECT token_hash FROM chat_user_session",String.class));
        var initial=users;assertThrows(Exception.class,()->initial.authenticate("bad"));assertThrows(Exception.class,()->initial.login("\n"));
        jdbc.execute("SHUTDOWN");jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));users=new ChatUserRegistry(jdbc);users.initialize();
        assertEquals(login.user(),users.authenticate(login.sessionToken()));users.logout(login.sessionToken());
        var reopened=users;assertThrows(Exception.class,()->reopened.authenticate(login.sessionToken()));jdbc.execute("SHUTDOWN");
    }
}
