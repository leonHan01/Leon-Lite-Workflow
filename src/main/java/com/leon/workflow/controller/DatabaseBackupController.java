package com.leon.workflow.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
public class DatabaseBackupController {
    private final DataSource dataSource;

    public DatabaseBackupController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/admin/database-backup")
    public ResponseEntity<byte[]> download() throws SQLException {
        StringBuilder script = new StringBuilder("-- Leon Workflow database backup (browser sessions excluded)\n");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SCRIPT NOPASSWORDS NOSETTINGS")) {
            while (rows.next()) {
                String sql = rows.getString(1);
                // Session identifiers and token fingerprints are authentication material, not business data.
                if (sql.startsWith("INSERT INTO \"PUBLIC\".\"BROWSER_SESSION\"")) continue;
                if (sql.startsWith("INSERT INTO \"PUBLIC\".\"AI_EXECUTOR_")) continue;
                script.append(sql).append('\n');
            }
        }
        String filename = "leon-workflow-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sql";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("application", "sql", StandardCharsets.UTF_8))
                .body(script.toString().getBytes(StandardCharsets.UTF_8));
    }
}
