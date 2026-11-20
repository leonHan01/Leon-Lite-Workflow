package com.leon.workflow.admin;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServerAddressTest {
    @Test public void normalizesHttpsOriginsAndDefaultPorts() {
        ServerAddress server = new ServerAddress(" HTTPS://Admin.Example:443/ ");
        assertEquals("https://admin.example", server.origin());
        assertTrue(server.owns("https://ADMIN.example:443/workflows.html?view=1"));
        assertTrue(server.owns("https://admin.example/"));
        assertFalse(server.owns("https://admin.example:8443/"));
        assertFalse(server.owns("http://admin.example/"));
    }

    @Test public void rejectsCredentialsPathsAndMalformedOrigins() {
        String[] invalid = {"http://121.40.254.112", "file:///etc/passwd", "javascript:alert(1)",
                "https://user:secret@example.com", "https://example.com/path", "https://example.com?token=secret",
                "https://example.com/#fragment", "https://example.com:0", "https://example.com:65536",
                "https://example.com\\@evil.test", "//example.com", "", "https://"};
        for (String value : invalid) assertThrows(value, IllegalArgumentException.class, () -> new ServerAddress(value));
    }

    @Test public void authenticatedRequestsStayOnTheExactOrigin() {
        ServerAddress server = new ServerAddress("https://121.40.254.112");
        String[] other = {"https://121.40.254.112.evil.test/", "https://evil.test/?host=121.40.254.112",
                "https://121.40.254.112@evil.test/", "https://evil@121.40.254.112/", "https://121.40.254.112:444/",
                "blob:https://121.40.254.112/id", "content://121.40.254.112/file", "data:text/html,test", null};
        for (String value : other) assertFalse(String.valueOf(value), server.owns(value));
        assertEquals("https://121.40.254.112/json-record.html?id=42", server.resolve("/json-record.html?id=42"));
        for (String path : new String[]{"//evil.test", "https://evil.test", "/\\evil.test", null})
            assertThrows(IllegalArgumentException.class, () -> server.resolve(path));
    }

    @Test public void supportsPrivateHttpsPortsAndIpv6() {
        ServerAddress server = new ServerAddress("https://[::1]:8443/");
        assertEquals("https://[::1]:8443/workflows.html", server.resolve("/workflows.html"));
        assertTrue(server.owns("https://[::1]:8443/"));
        assertFalse(server.owns("https://[::1]/"));
    }

    @Test public void exportFilenamesCannotInjectPathsOrControlCharacters() {
        assertEquals(".._.._backup.sql", ServerAddress.filename("../../backup.sql"));
        assertEquals("backup___.json", ServerAddress.filename("backup\r\n\t.json"));
        assertEquals("leon-export.dat", ServerAddress.filename(".."));
        assertEquals("快捷项.json", ServerAddress.filename("快捷项.json"));
        assertEquals(160, ServerAddress.filename("a".repeat(500)).length());
    }
}
