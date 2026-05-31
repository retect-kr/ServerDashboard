package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditManager {

    private final DashboardPlugin plugin;
    private final DatabaseManager db;

    public AuditManager(DashboardPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db     = db;
    }

    public void log(String actor, String action, String target, String detail, String ip) {
        try {
            db.execute(
                "INSERT INTO audit_log (ts, actor, action, target, detail, ip) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), actor, action, target, detail, ip
            );
        } catch (SQLException e) {
            plugin.getLogger().warning("[Audit] 로그 저장 실패: " + e.getMessage());
        }
    }

    /** actor가 null이면 전체 조회 */
    public List<Map<String, Object>> getLogs(int limit, int offset, String actor) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = actor != null
            ? "SELECT * FROM audit_log WHERE actor=? ORDER BY ts DESC LIMIT ? OFFSET ?"
            : "SELECT * FROM audit_log ORDER BY ts DESC LIMIT ? OFFSET ?";
        try {
            ResultSet rs = actor != null
                ? db.query(sql, actor, limit, offset)
                : db.query(sql, limit, offset);
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",     rs.getLong("id"));
                row.put("ts",     rs.getLong("ts"));
                row.put("actor",  rs.getString("actor"));
                row.put("action", rs.getString("action"));
                row.put("target", rs.getString("target"));
                row.put("detail", rs.getString("detail"));
                row.put("ip",     rs.getString("ip"));
                result.add(row);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Audit] 조회 실패: " + e.getMessage());
        }
        return result;
    }
}
