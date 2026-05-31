package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IamManager {

    private static final long SESSION_TTL_MS = 24 * 60 * 60 * 1000L;

    public record Session(String userId, String role, long expiresAt) {}

    private final DashboardPlugin plugin;
    private final DatabaseManager db;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public IamManager(DashboardPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db     = db;
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** @return session token, or null on failure */
    public String login(String id, String password) {
        try {
            ResultSet rs = db.query("SELECT role, hash, salt FROM iam_users WHERE id=?", id);
            if (!rs.next()) return null;
            String role = rs.getString("role");
            String hash = rs.getString("hash");
            String salt = rs.getString("salt");
            if (!checkPw(password, salt, hash)) return null;
            purgeExpired();
            String token = randomHex(24);
            sessions.put(token, new Session(id, role, System.currentTimeMillis() + SESSION_TTL_MS));
            return token;
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 로그인 오류: " + e.getMessage());
            return null;
        }
    }

    public Session validate(String token) {
        Session s = sessions.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expiresAt()) { sessions.remove(token); return null; }
        return s;
    }

    public void logout(String token) { sessions.remove(token); }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    public boolean createUser(String id, String password, String role) {
        if (id == null || id.isBlank()) return false;
        try {
            ResultSet rs = db.query("SELECT id FROM iam_users WHERE id=?", id);
            if (rs.next()) return false; // already exists
            String salt = randomHex(16);
            db.execute(
                "INSERT INTO iam_users (id, role, hash, salt, created_at) VALUES (?,?,?,?,?)",
                id, role, hashPw(password, salt), salt, System.currentTimeMillis()
            );
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 사용자 생성 오류: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteUser(String id) {
        try {
            ResultSet rs = db.query("SELECT id FROM iam_users WHERE id=?", id);
            if (!rs.next()) return false;
            db.execute("DELETE FROM iam_users WHERE id=?", id);
            sessions.entrySet().removeIf(e -> e.getValue().userId().equals(id));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 삭제 오류: " + e.getMessage());
            return false;
        }
    }

    public boolean changePassword(String id, String newPassword) {
        try {
            ResultSet rs = db.query("SELECT id FROM iam_users WHERE id=?", id);
            if (!rs.next()) return false;
            String salt = randomHex(16);
            db.execute("UPDATE iam_users SET hash=?, salt=? WHERE id=?", hashPw(newPassword, salt), salt, id);
            sessions.entrySet().removeIf(e -> e.getValue().userId().equals(id));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 비밀번호 변경 오류: " + e.getMessage());
            return false;
        }
    }

    public boolean changeRole(String id, String role) {
        try {
            db.execute("UPDATE iam_users SET role=? WHERE id=?", role, id);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 역할 변경 오류: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ResultSet rs = db.query("SELECT id, role, created_at FROM iam_users ORDER BY created_at ASC");
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        rs.getString("id"));
                m.put("role",      rs.getString("role"));
                m.put("createdAt", rs.getLong("created_at"));
                result.add(m);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[IAM] 사용자 목록 오류: " + e.getMessage());
        }
        return result;
    }

    public int userCount() {
        try {
            ResultSet rs = db.query("SELECT COUNT(*) FROM iam_users");
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String hashPw(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean checkPw(String password, String salt, String expected) {
        return hashPw(password, salt).equals(expected);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
