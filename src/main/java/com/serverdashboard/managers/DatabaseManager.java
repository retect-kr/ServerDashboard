package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;

import java.nio.file.Path;
import java.sql.*;

public class DatabaseManager {

    private final DashboardPlugin plugin;
    private Connection conn;
    private boolean mysql;

    public DatabaseManager(DashboardPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() throws Exception {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        mysql = "mysql".equals(type);
        if (mysql) {
            String host = plugin.getConfig().getString("database.host",     "localhost");
            int    port = plugin.getConfig().getInt(   "database.port",     3306);
            String db   = plugin.getConfig().getString("database.database", "serverdashboard");
            String user = plugin.getConfig().getString("database.username", "root");
            String pass = plugin.getConfig().getString("database.password", "");
            String url  = "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&autoReconnect=true&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
            Class.forName("com.mysql.cj.jdbc.Driver");
            conn = DriverManager.getConnection(url, user, pass);
            plugin.getLogger().info("[DB] MySQL 연결됨: " + host + ":" + port + "/" + db);
        } else {
            Path dbFile = plugin.getDataFolder().toPath().resolve("data.db");
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            conn.createStatement().execute("PRAGMA journal_mode=WAL");
            plugin.getLogger().info("[DB] SQLite 초기화됨: " + dbFile.getFileName());
        }
        createTables();
    }

    private void createTables() throws SQLException {
        String autoInc = mysql ? "AUTO_INCREMENT" : "AUTOINCREMENT";
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
                    id        INTEGER PRIMARY KEY %s,
                    ts        BIGINT      NOT NULL,
                    actor     VARCHAR(64) NOT NULL,
                    action    VARCHAR(64) NOT NULL,
                    target    VARCHAR(128),
                    detail    TEXT,
                    ip        VARCHAR(64)
                )""".formatted(autoInc));
        }
    }

    public synchronized PreparedStatement prepare(String sql) throws SQLException {
        ensureConnected();
        return conn.prepareStatement(sql);
    }

    public synchronized void execute(String sql, Object... params) throws SQLException {
        ensureConnected();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    public synchronized ResultSet query(String sql, Object... params) throws SQLException {
        ensureConnected();
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        return ps.executeQuery();
    }

    private void ensureConnected() throws SQLException {
        if (conn == null || (mysql && conn.isClosed())) {
            try { init(); } catch (Exception e) { throw new SQLException("DB 재연결 실패: " + e.getMessage(), e); }
        }
    }

    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }
}
