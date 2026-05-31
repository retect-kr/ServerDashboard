package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.models.ChatChannel;
import com.serverdashboard.models.ChatMessage;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ChatManager implements Listener {

    /** Modules can register an interceptor to handle chat before channel routing. */
    @FunctionalInterface
    public interface ChatInterceptor {
        /** @return true if the message was handled (skips normal channel routing). */
        boolean intercept(Player player, String message);
    }

    private static final int LOG_SIZE = 100;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final DashboardPlugin plugin;
    private final java.util.concurrent.CopyOnWriteArrayList<ChatInterceptor> interceptors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<ChatChannel>                     channels     = new ArrayList<>();
    private final Map<UUID, String>                     playerCh     = new ConcurrentHashMap<>(); // uuid → channelId
    private final Map<UUID, UUID>                       pmTarget     = new ConcurrentHashMap<>(); // sender → last target
    private final Map<UUID, UUID>                       pmFrom       = new ConcurrentHashMap<>(); // receiver → last sender
    private final Map<String, ArrayDeque<ChatMessage>>  chLogs       = new ConcurrentHashMap<>();
    private final ArrayDeque<ChatMessage>               pmLog        = new ArrayDeque<>();

    public ChatManager(DashboardPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // ── Config ─────────────────────────────────────────────────────────────────

    public void reload() {
        synchronized (channels) {
            channels.clear();
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("chat.channels");
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    ChatChannel ch = new ChatChannel();
                    ch.setId(id);
                    ch.setName(sec.getString(id + ".name", id));
                    ch.setAlias(sec.getString(id + ".alias", id.substring(0, 1)));
                    ch.setColor(sec.getString(id + ".color", "§f"));
                    ch.setDistance(sec.getInt(id + ".distance", 0));
                    String perm = sec.getString(id + ".permission", "");
                    ch.setPermission(perm.isBlank() ? null : perm);
                    ch.setDefault(sec.getBoolean(id + ".default", false));
                    channels.add(ch);
                    chLogs.computeIfAbsent(id, k -> new ArrayDeque<>());
                }
            }
            if (channels.isEmpty()) {
                channels.add(new ChatChannel("global", "글로벌", "g", "§f", 0, null, true));
                channels.add(new ChatChannel("local",  "로컬",   "l", "§7", 200, null, false));
                channels.add(new ChatChannel("staff",  "스태프", "s", "§c", 0, "serverdashboard.admin", false));
                channels.forEach(ch -> chLogs.computeIfAbsent(ch.getId(), k -> new ArrayDeque<>()));
            }
        }
    }

    // ── Chat event ─────────────────────────────────────────────────────────────

    public void addInterceptor(ChatInterceptor ci)    { interceptors.add(ci); }
    public void removeInterceptor(ChatInterceptor ci) { interceptors.remove(ci); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;

        Player p = event.getPlayer();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Let registered interceptors (e.g. Teams module) handle first
        for (ChatInterceptor ci : interceptors) {
            if (ci.intercept(p, plain)) {
                event.setCancelled(true);
                return;
            }
        }

        ChatChannel target = null;
        String msgText = plain;

        synchronized (channels) {
            for (ChatChannel ch : channels) {
                if (plain.startsWith(ch.getAlias() + " ")) {
                    target = ch;
                    msgText = plain.substring(ch.getAlias().length() + 1);
                    break;
                }
            }
            if (target == null) {
                String cid = playerCh.getOrDefault(p.getUniqueId(), defaultChannel().getId());
                target = getChannel(cid);
                if (target == null) target = defaultChannel();
            }
        }

        if (target == null) return;

        if (target.getPermission() != null && !p.hasPermission(target.getPermission())) {
            p.sendMessage("§c이 채널에 접근 권한이 없습니다.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        String fmt = plugin.getConfig().getString("chat.format", "{color}[{name}] §7{player}§f: {message}");
        String formatted = fmt
                .replace("{color}", target.getColor())
                .replace("{name}",  target.getName())
                .replace("{alias}", target.getAlias())
                .replace("{player}", p.getName())
                .replace("{message}", msgText);
        Component msg = LEGACY.deserialize(formatted);

        final ChatChannel ch   = target;
        final String finalText = msgText;

        if (ch.getDistance() > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                double r2 = (double) ch.getDistance() * ch.getDistance();
                p.getWorld().getPlayers().stream()
                        .filter(pl -> pl.getLocation().distanceSquared(p.getLocation()) <= r2)
                        .filter(pl -> ch.getPermission() == null || pl.hasPermission(ch.getPermission()))
                        .forEach(pl -> pl.sendMessage(msg));
            });
        } else {
            Bukkit.getOnlinePlayers().stream()
                    .filter(pl -> ch.getPermission() == null || pl.hasPermission(ch.getPermission()))
                    .forEach(pl -> pl.sendMessage(msg));
        }

        log(ch.getId(), p.getName(), null, finalText);
        plugin.getLogger().info("[" + ch.getAlias().toUpperCase() + "] " + p.getName() + ": " + finalText);
    }

    // ── Commands ───────────────────────────────────────────────────────────────

    public boolean handleCh(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§c플레이어 전용 명령어입니다."); return true; }
        if (args.length == 0) {
            ChatChannel cur = getChannel(playerCh.getOrDefault(p.getUniqueId(), defaultChannel().getId()));
            p.sendMessage("§7현재 채널: §e" + (cur != null ? cur.getName() : "없음"));
            p.sendMessage("§7채널 목록:");
            synchronized (channels) {
                channels.forEach(ch -> p.sendMessage("  §e" + ch.getAlias() + " §7- §f" + ch.getName() +
                        (ch.getDistance() > 0 ? " §7(반경 §e" + ch.getDistance() + "§7블록)" : "") +
                        (ch.getPermission() != null && !p.hasPermission(ch.getPermission()) ? " §c[권한 필요]" : "")));
            }
            return true;
        }
        String input = args[0].toLowerCase();
        ChatChannel found;
        synchronized (channels) {
            found = channels.stream()
                    .filter(ch -> ch.getId().equalsIgnoreCase(input) || ch.getAlias().equalsIgnoreCase(input))
                    .findFirst().orElse(null);
        }
        if (found == null) { p.sendMessage("§c'" + input + "' 채널을 찾을 수 없습니다."); return true; }
        if (found.getPermission() != null && !p.hasPermission(found.getPermission())) {
            p.sendMessage("§c이 채널에 접근 권한이 없습니다."); return true;
        }
        playerCh.put(p.getUniqueId(), found.getId());
        p.sendMessage("§a채널이 §e" + found.getName() + " §a으로 변경되었습니다.");
        return true;
    }

    public boolean handleMsg(CommandSender sender, String[] args) {
        if (!(sender instanceof Player from)) { sender.sendMessage("§c플레이어 전용 명령어입니다."); return true; }
        if (args.length < 2) { from.sendMessage("§c사용법: /msg <플레이어> <메시지>"); return true; }
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null) { from.sendMessage("§c'" + args[0] + "' 플레이어가 온라인이 아닙니다."); return true; }
        if (to.getUniqueId().equals(from.getUniqueId())) { from.sendMessage("§c자신에게 귓속말을 보낼 수 없습니다."); return true; }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        deliverPm(from, to, text);
        return true;
    }

    public boolean handleReply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§c플레이어 전용 명령어입니다."); return true; }
        if (args.length == 0) { p.sendMessage("§c사용법: /r <메시지>"); return true; }
        UUID targetId = pmTarget.get(p.getUniqueId());
        if (targetId == null) targetId = pmFrom.get(p.getUniqueId());
        if (targetId == null) { p.sendMessage("§c답장할 대상이 없습니다."); return true; }
        Player to = Bukkit.getPlayer(targetId);
        if (to == null) { p.sendMessage("§c상대방이 오프라인입니다."); return true; }
        String text = String.join(" ", args);
        deliverPm(p, to, text);
        return true;
    }

    private void deliverPm(Player from, Player to, String text) {
        String sendFmt = plugin.getConfig().getString("chat.pm-send-format",    "§d[귓속말 → {to}] §f{message}");
        String recvFmt = plugin.getConfig().getString("chat.pm-receive-format", "§d[귓속말 ← {from}] §f{message}");
        from.sendMessage(LEGACY.deserialize(sendFmt.replace("{to}", to.getName()).replace("{message}", text)));
        to  .sendMessage(LEGACY.deserialize(recvFmt.replace("{from}", from.getName()).replace("{message}", text)));
        pmTarget.put(from.getUniqueId(), to.getUniqueId());
        pmFrom  .put(to.getUniqueId(),  from.getUniqueId());
        log(null, from.getName(), to.getName(), text);
        plugin.getLogger().info("[PM] " + from.getName() + " → " + to.getName() + ": " + text);
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    private void log(String channelId, String sender, String recipient, String content) {
        ChatMessage msg = new ChatMessage(channelId, sender, recipient, content);
        if (channelId == null) {
            synchronized (pmLog) { pmLog.addLast(msg); while (pmLog.size() > LOG_SIZE * 2) pmLog.pollFirst(); }
        } else {
            ArrayDeque<ChatMessage> q = chLogs.computeIfAbsent(channelId, k -> new ArrayDeque<>());
            synchronized (q) { q.addLast(msg); while (q.size() > LOG_SIZE) q.pollFirst(); }
        }
    }

    // ── API helpers ────────────────────────────────────────────────────────────

    public List<ChatChannel> getChannels() {
        synchronized (channels) { return new ArrayList<>(channels); }
    }

    public ChatChannel getChannel(String id) {
        synchronized (channels) {
            return channels.stream().filter(ch -> ch.getId().equals(id)).findFirst().orElse(null);
        }
    }

    public ChatChannel defaultChannel() {
        synchronized (channels) {
            return channels.stream().filter(ChatChannel::isDefault).findFirst()
                    .orElse(channels.isEmpty() ? null : channels.get(0));
        }
    }

    public List<ChatMessage> getChannelLog(String channelId) {
        ArrayDeque<ChatMessage> q = chLogs.get(channelId);
        if (q == null) return List.of();
        synchronized (q) { return new ArrayList<>(q); }
    }

    public List<ChatMessage> getPmLog() {
        synchronized (pmLog) { return new ArrayList<>(pmLog); }
    }

    public Map<String, String> getPlayerChannelMap() {
        Map<String, String> result = new LinkedHashMap<>();
        Bukkit.getOnlinePlayers().forEach(p -> {
            String cid = playerCh.getOrDefault(p.getUniqueId(), defaultChannel() != null ? defaultChannel().getId() : "?");
            result.put(p.getName(), cid);
        });
        return result;
    }

    public void sayToChannel(String channelId, String senderName, String text) {
        ChatChannel ch;
        synchronized (channels) { ch = getChannel(channelId); }
        if (ch == null) return;
        String fmt = plugin.getConfig().getString("chat.format", "{color}[{name}] §7{player}§f: {message}");
        String formatted = fmt.replace("{color}", ch.getColor()).replace("{name}", ch.getName())
                .replace("{alias}", ch.getAlias()).replace("{player}", senderName).replace("{message}", text);
        Component msg = LEGACY.deserialize(formatted);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> ch.getPermission() == null || p.hasPermission(ch.getPermission()))
                .forEach(p -> p.sendMessage(msg));
        log(channelId, senderName, null, text);
    }

    public String getPlayerChannel(java.util.UUID uuid) {
        String cid = playerCh.get(uuid);
        if (cid != null) return cid;
        ChatChannel def = defaultChannel();
        return def != null ? def.getId() : null;
    }

    public boolean addChannel(ChatChannel ch) {
        synchronized (channels) {
            if (channels.stream().anyMatch(c -> c.getId().equals(ch.getId()))) return false;
            channels.add(ch);
            chLogs.computeIfAbsent(ch.getId(), k -> new ArrayDeque<>());
            saveChannels();
            return true;
        }
    }

    /** 채널을 메모리에만 등록 (config 저장 안 함 — 모듈이 동적으로 등록할 때 사용) */
    public boolean addTransientChannel(ChatChannel ch) {
        synchronized (channels) {
            if (channels.stream().anyMatch(c -> c.getId().equals(ch.getId()))) return false;
            channels.add(ch);
            chLogs.computeIfAbsent(ch.getId(), k -> new ArrayDeque<>());
            return true;
        }
    }

    public boolean updateChannel(String id, ChatChannel updated) {
        synchronized (channels) {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).getId().equals(id)) {
                    channels.set(i, updated);
                    saveChannels();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean deleteChannel(String id) {
        synchronized (channels) {
            boolean removed = channels.removeIf(ch -> ch.getId().equals(id));
            if (removed) saveChannels();
            return removed;
        }
    }

    public List<String> channelTabComplete(String prefix) {
        synchronized (channels) {
            return channels.stream()
                    .flatMap(ch -> java.util.stream.Stream.of(ch.getId(), ch.getAlias()))
                    .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                    .distinct().collect(Collectors.toList());
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private void saveChannels() {
        plugin.getConfig().set("chat.channels", null);
        synchronized (channels) {
            for (ChatChannel ch : channels) {
                String base = "chat.channels." + ch.getId();
                plugin.getConfig().set(base + ".name",       ch.getName());
                plugin.getConfig().set(base + ".alias",      ch.getAlias());
                plugin.getConfig().set(base + ".color",      ch.getColor());
                plugin.getConfig().set(base + ".distance",   ch.getDistance());
                plugin.getConfig().set(base + ".permission", ch.getPermission() != null ? ch.getPermission() : "");
                plugin.getConfig().set(base + ".default",    ch.isDefault());
            }
        }
        plugin.saveConfig();
    }
}
