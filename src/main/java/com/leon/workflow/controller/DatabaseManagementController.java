package com.leon.workflow.controller;

import com.leon.workflow.ApiTokenInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.Reader;
import java.sql.*;
import java.util.*;

/** Uses the existing, proxy-protected database-backup route. */
@RestController
public class DatabaseManagementController {
    private final DataSource dataSource;
    private final ApiTokenInterceptor auth;
    private final Object queryLock = new Object();

    public DatabaseManagementController(DataSource dataSource, ApiTokenInterceptor auth) {
        this.dataSource = dataSource;
        this.auth = auth;
    }

    private void authorize(HttpServletRequest request, boolean write) {
        if (auth.enabled() && (!auth.validBrowserSession(request) || (write && !auth.validAdminWrite(request))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请从管理页面登录后操作");
    }

    @GetMapping(value = "/api/admin/database-backup", params = "tables")
    public ResponseEntity<?> tables(HttpServletRequest request) throws SQLException {
        authorize(request, false);
        List<Map<String, Object>> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (Table table : listTables(connection)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("schema", table.schema()); item.put("name", table.name());
                item.put("rows", null); item.put("bytes", null);
                try (ResultSet columns = connection.getMetaData().getColumns(null, table.schema(), table.name(), null)) {
                    List<String> lengths = new ArrayList<>();
                    while (columns.next()) {
                        String col = quote(columns.getString("COLUMN_NAME"));
                        int type = columns.getInt("DATA_TYPE");
                        String value = switch (type) {
                            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> col;
                            default -> "CAST(" + col + " AS VARCHAR)";
                        };
                        lengths.add("COALESCE(OCTET_LENGTH(" + value + "), 0)");
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(2);
                        String total = lengths.isEmpty() ? "0" : String.join(" + ", lengths);
                        try (ResultSet result = statement.executeQuery("SELECT COUNT(*), COALESCE(SUM(" + total + "), 0) FROM " + table.sqlName())) {
                            if (result.next()) { item.put("rows", result.getLong(1)); item.put("bytes", result.getLong(2)); }
                        }
                    }
                } catch (SQLException ignored) { /* Some column types or large tables cannot be measured within the limit. */ }
                tables.add(item);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("tables", tables));
    }

    @PostMapping("/api/admin/database-backup")
    public ResponseEntity<?> query(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        authorize(request, true);
        Object value = body.get("sql");
        if (!(value instanceof String sql) || sql.isBlank() || sql.length() > 16000)
            throw badRequest("请输入不超过 16000 字符的 SQL 查询");
        sql = sql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.indexOf(';') >= 0 || !sql.matches("(?is)^(SELECT|WITH)\\b[\\s\\S]*"))
            throw badRequest("只支持一条 SELECT 或 WITH 查询，语句中不能包含分号");
        synchronized (queryLock) {
            try {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(executeReadOnly(sql));
            } catch (SQLException e) {
                // Do not return database error text: it may contain SQL or sensitive values.
                throw badRequest("查询失败，请检查 SQL、表名和查询权限，或缩小查询范围");
            }
        }
    }

    private Map<String, Object> executeReadOnly(String sql) throws SQLException {
        String user = "DB_VIEW_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = dataSource.getConnection(); Statement setup = admin.createStatement()) {
            boolean created = false;
            try {
                setup.execute("CREATE USER " + user + " PASSWORD '" + password + "'");
                created = true;
                for (Table table : listTables(admin)) setup.execute("GRANT SELECT ON " + table.sqlName() + " TO " + user);
                try (Connection reader = DriverManager.getConnection(admin.getMetaData().getURL(), user, password);
                     Statement statement = reader.createStatement()) {
                    statement.setQueryTimeout(5);
                    statement.setMaxRows(201);
                    try (ResultSet result = statement.executeQuery(sql)) {
                        ResultSetMetaData meta = result.getMetaData();
                        int count = Math.min(meta.getColumnCount(), 100);
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= count; i++) columns.add(meta.getColumnLabel(i));
                        List<List<String>> rows = new ArrayList<>();
                        boolean truncated = meta.getColumnCount() > count;
                        int chars = 0;
                        while (result.next()) {
                            if (rows.size() == 200 || chars >= 200000) { truncated = true; break; }
                            List<String> row = new ArrayList<>();
                            for (int i = 1; i <= count; i++) {
                                String cell = readCell(result, i);
                                row.add(cell);
                                if (cell != null) chars += cell.length();
                            }
                            rows.add(row);
                        }
                        return Map.of("columns", columns, "rows", rows, "truncated", truncated);
                    }
                }
            } finally {
                if (created) setup.execute("DROP USER " + user);
            }
        }
    }

    private static String readCell(ResultSet result, int index) throws SQLException {
        try (Reader reader = result.getCharacterStream(index)) {
            if (reader == null) return null;
            char[] buffer = new char[2049];
            int length = 0, n;
            while (length < buffer.length && (n = reader.read(buffer, length, buffer.length - length)) != -1) length += n;
            return length > 2048 ? new String(buffer, 0, 2048) + "…" : new String(buffer, 0, length);
        } catch (java.io.IOException e) { throw new SQLException("Cannot read value", e); }
    }

    private static List<Table> listTables(Connection connection) throws SQLException {
        List<Table> tables = new ArrayList<>();
        try (ResultSet result = connection.getMetaData().getTables(null, null, "%", new String[]{"BASE TABLE"})) {
            while (result.next()) {
                String schema = result.getString("TABLE_SCHEM");
                if (!schema.equalsIgnoreCase("INFORMATION_SCHEMA")) tables.add(new Table(schema, result.getString("TABLE_NAME")));
            }
        }
        tables.sort(Comparator.comparing(Table::schema).thenComparing(Table::name));
        return tables;
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private record Table(String schema, String name) { String sqlName() { return quote(schema) + "." + quote(name); } }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
