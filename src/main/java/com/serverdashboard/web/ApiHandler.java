package com.serverdashboard.web;

import com.google.gson.*;
import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.api.DashboardModule;
import com.serverdashboard.managers.AnnouncementManager;
import com.serverdashboard.models.Announcement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_BODY_BYTES = 64 * 1024; // 64 KB
    private static final int MAX_AUTH_FAILURES = 10;
    private static final long AUTH_WINDOW_MS = 60_000L;

    private final DashboardPlugin plugin;
    private final String token;
    // [failureCount, windowStartMs]
    private final Map<String, long[]> authFailures = new java.util.concurrent.ConcurrentHashMap<>();

    public ApiHandler(DashboardPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(ip)) {
            send(ex, 429, obj("error", "Too Many Requests"));
            return;
        }

        String authHeader = ex.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.equals("Bearer " + token)) {
            recordAuthFailure(ip);
            send(ex, 401, obj("error", "Unauthorized"));
            return;
        }
        authFailures.remove(ip);

        String path = ex.getRequestURI().getPath().replaceFirst("^/api", "");
        String method = ex.getRequestMethod();

        try {
            route(ex, method, path);
        } catch (Exception e) {
            plugin.getLogger().warning("API error: " + e.getMessage());
            send(ex, 500, obj("error", e.getMessage()));
        }
    }

    private void route(HttpExchange ex, String method, String path) throws Exception {
        if (path.equals("/players") && method.equals("GET")) {
            handleGetPlayers(ex);
        } else if (path.equals("/kick") && method.equals("POST")) {
            handleKick(ex);
        } else if (path.equals("/ban") && method.equals("POST")) {
            handleBan(ex);
        } else if (path.equals("/unban") && method.equals("POST")) {
            handleUnban(ex);
        } else if (path.equals("/banned") && method.equals("GET")) {
            handleGetBanned(ex);
        } else if (path.equals("/announcements") && method.equals("GET")) {
            handleGetAnnouncements(ex);
        } else if (path.equals("/announcements") && method.equals("POST")) {
            handleCreateAnnouncement(ex);
        } else if (path.matches("/announcements/[^/]+") && method.equals("PUT")) {
            String id = path.substring("/announcements/".length());
            handleUpdateAnnouncement(ex, id);
        } else if (path.matches("/announcements/[^/]+/toggle") && method.equals("POST")) {
            String id = path.substring("/announcements/".length()).replace("/toggle", "");
            handleToggleAnnouncement(ex, id);
        } else if (path.matches("/announcements/[^/]+") && method.equals("DELETE")) {
            String id = path.substring("/announcements/".length());
            handleDeleteAnnouncement(ex, id);
        } else if (path.equals("/status") && method.equals("GET")) {
            handleGetStatus(ex);
        } else if (path.equals("/motd") && method.equals("POST")) {
            handleSetMotd(ex);
        } else if (path.equals("/logs") && method.equals("GET")) {
            handleGetLogs(ex);
        } else if (path.equals("/console") && method.equals("POST")) {
            handleConsole(ex);
        } else if (path.equals("/plugins") && method.equals("GET")) {
            handleGetPlugins(ex);
        } else if (path.matches("/plugins/[^/]+/enable") && method.equals("POST")) {
            handlePluginAction(ex, path.split("/")[2], "enable");
        } else if (path.matches("/plugins/[^/]+/disable") && method.equals("POST")) {
            handlePluginAction(ex, path.split("/")[2], "disable");
        } else if (path.matches("/plugins/[^/]+/reload") && method.equals("POST")) {
            handlePluginAction(ex, path.split("/")[2], "reload");
        } else if (path.equals("/whitelist") && method.equals("GET")) {
            handleGetWhitelist(ex);
        } else if (path.equals("/whitelist/add") && method.equals("POST")) {
            handleWhitelistAdd(ex);
        } else if (path.equals("/whitelist/remove") && method.equals("POST")) {
            handleWhitelistRemove(ex);
        } else if (path.equals("/whitelist/toggle") && method.equals("POST")) {
            handleWhitelistToggle(ex);
        } else if (path.equals("/gamerules") && method.equals("GET")) {
            handleGetGamerules(ex);
        } else if (path.equals("/gamerules") && method.equals("POST")) {
            handleSetGamerule(ex);
        } else if (path.equals("/modules") && method.equals("GET")) {
            handleGetModules(ex);
        } else if (path.equals("/modules/reload") && method.equals("POST")) {
            handleReloadAllModules(ex);
        } else if (path.matches("/modules/[^/]+/reload") && method.equals("POST")) {
            handleReloadModule(ex, path.split("/")[2]);
        } else if (path.startsWith("/module/")) {
            handleModuleRoute(ex, method, path.substring("/module/".length()));
        } else if (path.equals("/version") && method.equals("GET")) {
            JsonObject v = new JsonObject();
            v.addProperty("version", plugin.getDescription().getVersion());
            v.addProperty("name",    plugin.getDescription().getName());
            send(ex, 200, v);
        } else if (path.startsWith("/chat")) {
            handleChatRoute(ex, method, path.substring("/chat".length()));
        } else {
            send(ex, 404, obj("error", "Not Found"));
        }
    }

    // ── Chat API ──────────────────────────────────────────────────────────────

    private void handleChatRoute(HttpExchange ex, String method, String path) throws Exception {
        var chat = plugin.getChatManager();
        if (chat == null) { send(ex, 503, obj("error", "채팅 시스템이 비활성화되어 있습니다")); return; }

        if (path.equals("/channels") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            chat.getChannels().forEach(ch -> arr.add(channelJson(ch)));
            send(ex, 200, arr);

        } else if (path.equals("/channels") && method.equals("POST")) {
            JsonObject body = readBody(ex);
            com.serverdashboard.models.ChatChannel ch = new com.serverdashboard.models.ChatChannel();
            ch.setId(getStr(body, "id"));
            ch.setName(getStr(body, "name", "채널"));
            ch.setAlias(getStr(body, "alias", "?"));
            ch.setColor(getStr(body, "color", "§f"));
            ch.setDistance(body.has("distance") ? body.get("distance").getAsInt() : 0);
            ch.setPermission(getStr(body, "permission"));
            ch.setDefault(body.has("default") && body.get("default").getAsBoolean());
            if (ch.getId() == null || ch.getId().isBlank()) { send(ex, 400, obj("error", "id 필드가 필요합니다")); return; }
            if (chat.addChannel(ch)) send(ex, 201, channelJson(ch));
            else send(ex, 409, obj("error", "'" + ch.getId() + "' 채널이 이미 존재합니다"));

        } else if (path.matches("/channels/[^/]+") && method.equals("PUT")) {
            String id = path.split("/")[2];
            JsonObject body = readBody(ex);
            com.serverdashboard.models.ChatChannel ch = new com.serverdashboard.models.ChatChannel();
            ch.setId(id);
            ch.setName(getStr(body, "name", id));
            ch.setAlias(getStr(body, "alias", id.substring(0, 1)));
            ch.setColor(getStr(body, "color", "§f"));
            ch.setDistance(body.has("distance") ? body.get("distance").getAsInt() : 0);
            ch.setPermission(getStr(body, "permission"));
            ch.setDefault(body.has("default") && body.get("default").getAsBoolean());
            if (chat.updateChannel(id, ch)) send(ex, 200, channelJson(ch));
            else send(ex, 404, obj("error", "채널을 찾을 수 없습니다"));

        } else if (path.matches("/channels/[^/]+") && method.equals("DELETE")) {
            String id = path.split("/")[2];
            if (chat.deleteChannel(id)) send(ex, 200, obj("message", "채널이 삭제되었습니다."));
            else send(ex, 404, obj("error", "채널을 찾을 수 없습니다"));

        } else if (path.matches("/channels/[^/]+/log") && method.equals("GET")) {
            String id = path.split("/")[2];
            JsonArray arr = new JsonArray();
            chat.getChannelLog(id).forEach(m -> arr.add(msgJson(m)));
            send(ex, 200, arr);

        } else if (path.matches("/channels/[^/]+/say") && method.equals("POST")) {
            String id = path.split("/")[2];
            JsonObject body = readBody(ex);
            String text = getStr(body, "message");
            if (text == null || text.isBlank()) { send(ex, 400, obj("error", "message 필드가 필요합니다")); return; }
            String sender = getStr(body, "sender", "[서버]");
            runOnMain(() -> { chat.sayToChannel(id, sender, text); return null; });
            send(ex, 200, obj("message", "전송되었습니다."));

        } else if (path.equals("/pm") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            chat.getPmLog().forEach(m -> arr.add(msgJson(m)));
            send(ex, 200, arr);

        } else if (path.equals("/players") && method.equals("GET")) {
            JsonObject obj = new JsonObject();
            chat.getPlayerChannelMap().forEach(obj::addProperty);
            send(ex, 200, obj);

        } else {
            send(ex, 404, obj("error", "Not Found"));
        }
    }

    private JsonObject channelJson(com.serverdashboard.models.ChatChannel ch) {
        JsonObject o = new JsonObject();
        o.addProperty("id",         ch.getId());
        o.addProperty("name",       ch.getName());
        o.addProperty("alias",      ch.getAlias());
        o.addProperty("color",      ch.getColor());
        o.addProperty("distance",   ch.getDistance());
        o.addProperty("permission", ch.getPermission() != null ? ch.getPermission() : "");
        o.addProperty("default",    ch.isDefault());
        return o;
    }

    private JsonObject msgJson(com.serverdashboard.models.ChatMessage m) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", m.getTimestamp());
        o.addProperty("channelId", m.getChannelId());
        o.addProperty("sender",    m.getSender());
        o.addProperty("recipient", m.getRecipient());
        o.addProperty("content",   m.getContent());
        return o;
    }

    private void handleGetPlayers(HttpExchange ex) throws Exception {
        JsonArray arr = runOnMain(() -> {
            JsonArray a = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName());
                o.addProperty("uuid", p.getUniqueId().toString());
                o.addProperty("ping", p.getPing());
                o.addProperty("world", p.getWorld().getName());
                o.addProperty("op", p.isOp());
                a.add(o);
            }
            return a;
        });
        send(ex, 200, arr);
    }

    private void handleKick(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        String reason = getStr(body, "reason", "Kicked from dashboard.");

        Boolean result = runOnMain(() -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) return false;
            p.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(reason));
            return true;
        });

        if (result) send(ex, 200, obj("message", name + " kicked."));
        else send(ex, 404, obj("error", "Player not found."));
    }

    private void handleBan(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        String reason = getStr(body, "reason", "Banned from dashboard.");
        String expiryStr = body.has("expiry") && !body.get("expiry").isJsonNull()
                ? body.get("expiry").getAsString() : null;

        Boolean result = runOnMain(() -> {
            Date expiry = null;
            if (expiryStr != null && !expiryStr.isEmpty()) {
                try {
                    expiry = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").parse(expiryStr);
                } catch (Exception ignored) {}
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expiry, "Dashboard");
            Player online = target.getPlayer();
            if (online != null) {
                online.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(reason));
            }
            return true;
        });

        send(ex, 200, obj("message", name + " banned."));
    }

    private void handleUnban(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");

        runOnMain(() -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            return null;
        });

        send(ex, 200, obj("message", name + " unbanned."));
    }

    private void handleGetBanned(HttpExchange ex) throws Exception {
        JsonArray arr = runOnMain(() -> {
            JsonArray a = new JsonArray();
            for (var entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                JsonObject o = new JsonObject();
                o.addProperty("player", entry.getTarget());
                o.addProperty("reason", entry.getReason());
                o.addProperty("source", entry.getSource());
                o.addProperty("created", entry.getCreated() != null ? entry.getCreated().toString() : "");
                o.addProperty("expires", entry.getExpiration() != null ? entry.getExpiration().toString() : "Permanent");
                a.add(o);
            }
            return a;
        });
        send(ex, 200, arr);
    }

    private void handleGetAnnouncements(HttpExchange ex) throws IOException {
        AnnouncementManager am = plugin.getAnnouncementManager();
        JsonArray arr = new JsonArray();
        for (Announcement a : am.getAll()) {
            arr.add(announcementToJson(a));
        }
        send(ex, 200, arr);
    }

    private void handleCreateAnnouncement(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String message = getStr(body, "message");
        int interval = body.has("interval") ? body.get("interval").getAsInt() : 300;
        boolean enabled = !body.has("enabled") || body.get("enabled").getAsBoolean();
        String permission = body.has("permission") && !body.get("permission").isJsonNull()
                ? body.get("permission").getAsString() : null;

        if (message == null || message.isBlank()) {
            send(ex, 400, obj("error", "The 'message' field is required."));
            return;
        }

        Announcement created = runOnMain(() ->
            plugin.getAnnouncementManager().create(message, interval, enabled, permission)
        );

        send(ex, 201, announcementToJson(created));
    }

    private void handleUpdateAnnouncement(HttpExchange ex, String id) throws Exception {
        JsonObject body = readBody(ex);
        String message = getStr(body, "message");
        int interval = body.has("interval") ? body.get("interval").getAsInt() : 300;
        boolean enabled = !body.has("enabled") || body.get("enabled").getAsBoolean();
        String permission = body.has("permission") && !body.get("permission").isJsonNull()
                ? body.get("permission").getAsString() : null;

        Boolean ok = runOnMain(() ->
            plugin.getAnnouncementManager().update(id, message, interval, enabled, permission)
        );

        if (ok) send(ex, 200, obj("message", "Announcement updated."));
        else send(ex, 404, obj("error", "Announcement not found."));
    }

    private void handleToggleAnnouncement(HttpExchange ex, String id) throws Exception {
        Boolean ok = runOnMain(() -> plugin.getAnnouncementManager().toggle(id));
        if (ok) send(ex, 200, obj("message", "Announcement toggled."));
        else send(ex, 404, obj("error", "Announcement not found."));
    }

    private void handleDeleteAnnouncement(HttpExchange ex, String id) throws Exception {
        Boolean ok = runOnMain(() -> plugin.getAnnouncementManager().delete(id));
        if (ok) send(ex, 200, obj("message", "Announcement deleted."));
        else send(ex, 404, obj("error", "Announcement not found."));
    }

    private void handleGetStatus(HttpExchange ex) throws Exception {
        JsonObject status = runOnMain(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("online", Bukkit.getOnlinePlayers().size());
            o.addProperty("max", Bukkit.getMaxPlayers());
            String customMotd = plugin.getConfig().getString("web.motd", "");
            o.addProperty("motd", customMotd.isBlank() ? Bukkit.getMotd() : customMotd);
            o.addProperty("motdCustom", !customMotd.isBlank());
            o.addProperty("version", Bukkit.getVersion());
            o.addProperty("mcVersion", Bukkit.getMinecraftVersion());
            o.addProperty("tps", String.format("%.2f", Bukkit.getTPS()[0]));
            return o;
        });
        send(ex, 200, status);
    }

    private void handleSetMotd(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String motd = getStr(body, "motd");
        if (motd == null) { send(ex, 400, obj("error", "The 'motd' field is required.")); return; }
        plugin.getConfig().set("web.motd", motd);
        plugin.saveConfig();
        send(ex, 200, obj("message", "MOTD updated."));
    }

    private void handleGetLogs(HttpExchange ex) throws IOException {
        int since = 0;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("since=")) {
                    try { since = Integer.parseInt(p.substring(6)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        var lm = plugin.getLogManager();
        List<String> lines = lm.getLines(since);
        int total = lm.totalSeen();
        JsonObject resp = new JsonObject();
        resp.addProperty("total", total);
        JsonArray arr = new JsonArray();
        for (String l : lines) arr.add(l);
        resp.add("lines", arr);
        send(ex, 200, resp);
    }

    private void handleConsole(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String command = getStr(body, "command");
        if (command == null || command.isBlank()) {
            send(ex, 400, obj("error", "The 'command' field is required."));
            return;
        }
        final String cmd = command.trim();
        runOnMain(() -> { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); return null; });
        send(ex, 200, obj("message", "Command executed."));
    }

    private void handleGetPlugins(HttpExchange ex) throws Exception {
        JsonArray result = runOnMain(() -> {
            JsonArray a = new JsonArray();
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName());
                o.addProperty("version", p.getDescription().getVersion());
                o.addProperty("description", p.getDescription().getDescription() != null
                        ? p.getDescription().getDescription() : "");
                JsonArray authors = new JsonArray();
                p.getDescription().getAuthors().forEach(authors::add);
                o.add("authors", authors);
                o.addProperty("enabled", p.isEnabled());
                o.addProperty("self", p.getName().equals(plugin.getName()));
                a.add(o);
            }
            return a;
        });
        send(ex, 200, result);
    }

    private void handlePluginAction(HttpExchange ex, String name, String action) throws Exception {
        Boolean ok = runOnMain(() -> {
            org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target == null) return null;
            switch (action) {
                case "enable"  -> Bukkit.getPluginManager().enablePlugin(target);
                case "disable" -> Bukkit.getPluginManager().disablePlugin(target);
                case "reload"  -> {
                    Bukkit.getPluginManager().disablePlugin(target);
                    Bukkit.getPluginManager().enablePlugin(target);
                }
            }
            return true;
        });
        if (ok == null) send(ex, 404, obj("error", "Plugin not found: " + name));
        else send(ex, 200, obj("message", name + " " + action + "d."));
    }

    private void handleGetWhitelist(HttpExchange ex) throws Exception {
        JsonObject result = runOnMain(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", Bukkit.hasWhitelist());
            JsonArray arr = new JsonArray();
            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                JsonObject po = new JsonObject();
                po.addProperty("name", p.getName() != null ? p.getName() : "Unknown");
                po.addProperty("uuid", p.getUniqueId().toString());
                arr.add(po);
            }
            o.add("players", arr);
            return o;
        });
        send(ex, 200, result);
    }

    private void handleWhitelistAdd(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        if (name == null || name.isBlank()) { send(ex, 400, obj("error", "player is required")); return; }
        runOnMain(() -> { Bukkit.getOfflinePlayer(name).setWhitelisted(true); return null; });
        send(ex, 200, obj("message", name + " added to whitelist."));
    }

    private void handleWhitelistRemove(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        if (name == null || name.isBlank()) { send(ex, 400, obj("error", "player is required")); return; }
        Boolean ok = runOnMain(() -> {
            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                if (name.equalsIgnoreCase(p.getName())) { p.setWhitelisted(false); return true; }
            }
            return false;
        });
        if (ok) send(ex, 200, obj("message", name + " removed from whitelist."));
        else send(ex, 404, obj("error", name + " is not on the whitelist."));
    }

    private void handleWhitelistToggle(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        boolean enabled = body.has("enabled") && body.get("enabled").getAsBoolean();
        runOnMain(() -> { Bukkit.setWhitelist(enabled); return null; });
        send(ex, 200, obj("message", "Whitelist " + (enabled ? "enabled" : "disabled") + "."));
    }

    @SuppressWarnings("deprecation")
    private void handleGetGamerules(HttpExchange ex) throws Exception {
        JsonArray result = runOnMain(() -> {
            JsonArray arr = new JsonArray();
            World world = Bukkit.getWorlds().get(0);
            for (String name : world.getGameRules()) {
                String value = world.getGameRuleValue(name);
                if (value == null) value = "";
                JsonObject o = new JsonObject();
                o.addProperty("name", name);
                o.addProperty("type", value.equals("true") || value.equals("false") ? "boolean" : "integer");
                o.addProperty("value", value);
                arr.add(o);
            }
            return arr;
        });
        send(ex, 200, result);
    }

    private void handleSetGamerule(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String ruleName = getStr(body, "rule");
        String value    = getStr(body, "value");
        if (ruleName == null || value == null) { send(ex, 400, obj("error", "rule and value are required")); return; }
        runOnMain(() -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule " + ruleName + " " + value);
            return null;
        });
        send(ex, 200, obj("message", ruleName + " → " + value));
    }

    private void handleGetModules(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (DashboardModule m : plugin.getModuleManager().getAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getId());
            o.addProperty("name", m.getName());
            o.addProperty("icon", m.getIcon());
            o.addProperty("html", m.getSectionHtml());
            o.addProperty("script", m.getInitScript());
            o.addProperty("jarName", plugin.getModuleManager().getJarName(m.getId()));
            arr.add(o);
        }
        send(ex, 200, arr);
    }

    private void handleReloadAllModules(HttpExchange ex) throws Exception {
        int before = plugin.getModuleManager().count();
        runOnMain(() -> { plugin.getModuleManager().reloadAll(); return null; });
        int after = plugin.getModuleManager().count();
        send(ex, 200, obj("message", "Reloaded: " + before + " → " + after + " module(s)."));
    }

    private void handleReloadModule(HttpExchange ex, String id) throws Exception {
        Boolean ok = runOnMain(() -> plugin.getModuleManager().reload(id));
        if (ok) send(ex, 200, obj("message", id + " reloaded."));
        else send(ex, 404, obj("error", "Module not found: " + id));
    }

    private void handleModuleRoute(HttpExchange ex, String method, String remainder) throws Exception {
        int slash = remainder.indexOf('/');
        String moduleId = slash == -1 ? remainder : remainder.substring(0, slash);
        String subPath  = slash == -1 ? "/" : remainder.substring(slash);

        DashboardModule m = plugin.getModuleManager().get(moduleId);
        if (m == null) {
            send(ex, 404, obj("error", "Module not found: " + moduleId));
            return;
        }
        try {
            m.handleRoute(subPath, method, ex);
        } catch (Exception e) {
            plugin.getLogger().warning("[Modules] Route error in '" + moduleId + "': " + e.getMessage());
            send(ex, 500, obj("error", e.getMessage()));
        }
    }

    // --- utilities ---

    private <T> T runOnMain(java.util.concurrent.Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(task.call()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Main thread timed out");
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] buf = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (buf.length > MAX_BODY_BYTES) throw new IOException("Request body too large");
        String body = new String(buf, StandardCharsets.UTF_8);
        if (body.isBlank()) return new JsonObject();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private boolean isRateLimited(String ip) {
        long[] v = authFailures.get(ip);
        if (v == null) return false;
        if (System.currentTimeMillis() - v[1] > AUTH_WINDOW_MS) { authFailures.remove(ip); return false; }
        return v[0] >= MAX_AUTH_FAILURES;
    }

    private void recordAuthFailure(String ip) {
        long now = System.currentTimeMillis();
        authFailures.compute(ip, (k, v) -> {
            if (v == null || now - v[1] > AUTH_WINDOW_MS) return new long[]{1, now};
            return new long[]{v[0] + 1, v[1]};
        });
    }

    private String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private String getStr(JsonObject obj, String key, String def) {
        String v = getStr(obj, key);
        return v != null ? v : def;
    }

    private JsonObject announcementToJson(Announcement a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.getId());
        o.addProperty("message", a.getMessage());
        o.addProperty("interval", a.getIntervalSeconds());
        o.addProperty("enabled", a.isEnabled());
        o.addProperty("permission", a.getPermission());
        return o;
    }

    private JsonObject obj(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.getResponseHeaders().add("Connection", "close");
    }

    private void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }
}
