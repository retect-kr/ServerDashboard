package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;

import java.nio.file.Path;
import java.sql.*;

public class DatabaseManager {

    private enum DbType { SQLITE, MYSQL, POSTGRES }

    private final DashboardPlugin plugin;
    private Connection conn;
    private DbType dbType;

    // Stored for reconnection
    private String connUrl;
    private String connUser;
    private String connPass;

    public DatabaseManager(DashboardPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() throws Exception {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        dbType = switch (type) {
            case "mysql"                  -> DbType.MYSQL;
            case "postgres", "postgresql" -> DbType.POSTGRES;
            default                       -> DbType.SQLITE;
        };

        switch (dbType) {
            case MYSQL -> {
                String host = plugin.getConfig().getString("database.host",     "localhost");
                int    port = plugin.getConfig().getInt(   "database.port",     3306);
                String db   = plugin.getConfig().getString("database.database", "serverdashboard");
                connUser    = plugin.getConfig().getString("database.username", "root");
                connPass    = plugin.getConfig().getString("database.password", "");
                connUrl     = "jdbc:mysql://" + host + ":" + port + "/" + db
                        + "?useSSL=false&autoReconnect=true&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
                Class.forName("com.mysql.cj.jdbc.Driver");
                conn = DriverManager.getConnection(connUrl, connUser, connPass);
                plugin.getLogger().info("[DB] MySQL 연결됨: " + host + ":" + port + "/" + db);
            }
            case POSTGRES -> {
                String host = plugin.getConfig().getString("database.host",     "localhost");
                int    port = plugin.getConfig().getInt(   "database.port",     5432);
                String db   = plugin.getConfig().getString("database.database", "serverdashboard");
                connUser    = plugin.getConfig().getString("database.username", "postgres");
                connPass    = plugin.getConfig().getString("database.password", "");
                connUrl     = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                Class.forName("org.postgresql.Driver");
                conn = DriverManager.getConnection(connUrl, connUser, connPass);
                plugin.getLogger().info("[DB] PostgreSQL 연결됨: " + host + ":" + port + "/" + db);
            }
            case SQLITE -> {
                Path dbFile = plugin.getDataFolder().toPath().resolve("data.db");
                connUrl     = "jdbc:sqlite:" + dbFile.toAbsolutePath();
                connUser    = null;
                connPass    = null;
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection(connUrl);
                conn.createStatement().execute("PRAGMA journal_mode=WAL");
                plugin.getLogger().info("[DB] SQLite 초기화됨: " + dbFile.getFileName());
            }
        }

        createTables();
    }

    private void createTables() throws SQLException {
        // audit_log의 id 컬럼 자동증가 문법은 DB마다 다름
        String auditIdDef = switch (dbType) {
            case SQLITE   -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL    -> "BIGINT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRES -> "BIGSERIAL PRIMARY KEY";
        };

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS iam_users (
                    id         VARCHAR(64)  PRIMARY KEY,
                    role       VARCHAR(32)  NOT NULL DEFAULT 'admin',
                    hash       VARCHAR(64)  NOT NULL,
                    salt       VARCHAR(32)  NOT NULL,
                    created_at BIGINT       NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id     %s,
                    ts     BIGINT      NOT NULL,
                    actor  VARCHAR(64) NOT NULL,
                    action VARCHAR(64) NOT NULL,
                    target VARCHAR(128),
                    detail TEXT,
                    ip     VARCHAR(64)
                )""".formatted(auditIdDef));
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public synchronized PreparedStatement prepare(String sql) throws SQLException {
        ensureConnected();
        return conn.prepareStatement(sql);
    }

    public synchronized void execute(String sql, Object... params) throws SQLException {
        ensureConnected();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    public synchronized ResultSet query(String sql, Object... params) throws SQLException {
        ensureConnected();
        PreparedStatement ps = conn.prepareStatement(sql);
        bind(ps, params);
        return ps.executeQuery();
    }

    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    private void ensureConnected() throws SQLException {
        try {
            if (conn != null && conn.isValid(2)) return;
        } catch (SQLException ignored) {}
        // Reconnect
        try {
            if (connUser != null) conn = DriverManager.getConnection(connUrl, connUser, connPass);
            else                  conn = DriverManager.getConnection(connUrl);
            plugin.getLogger().info("[DB] 재연결 성공.");
        } catch (Exception e) {
            throw new SQLException("DB 재연결 실패: " + e.getMessage(), e);
        }
    }
}
