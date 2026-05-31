package com.serverdashboard;

import com.serverdashboard.managers.AcmeManager;
import com.serverdashboard.managers.AnnouncementManager;
import com.serverdashboard.managers.AuditManager;
import com.serverdashboard.managers.ChatManager;
import com.serverdashboard.managers.DatabaseManager;
import com.serverdashboard.managers.IamManager;
import com.serverdashboard.managers.LogManager;
import com.serverdashboard.managers.ModuleManager;
import com.serverdashboard.web.WebServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HexFormat;

public class DashboardPlugin extends JavaPlugin implements Listener {
    private static DashboardPlugin instance;
    private WebServer webServer;
    private AnnouncementManager announcementManager;
    private AcmeManager acmeManager;
    private LogManager logManager;
    private ModuleManager moduleManager;
    private ChatManager chatManager;
    private DatabaseManager databaseManager;
    private IamManager iamManager;
    private AuditManager auditManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        logManager = new LogManager();

        databaseManager = new DatabaseManager(this);
        try {
            databaseManager.init();
        } catch (Exception e) {
            getLogger().severe("[DB] 초기화 실패: " + e.getMessage());
        }
        iamManager   = new IamManager(this, databaseManager);
        auditManager = new AuditManager(this, databaseManager);
        getLogger().info("[IAM] 사용자 " + iamManager.userCount() + "명 로드됨.");

        announcementManager = new AnnouncementManager(this);
        announcementManager.load();

        acmeManager = new AcmeManager(this);

        int port = getConfig().getInt("web.port", 8080);
        String token = getConfig().getString("web.token", "changeme-please");

        if (token.equals("changeme-please")) {
            getLogger().warning("===========================================");
            getLogger().warning(" Please change web.token in config.yml!");
            getLogger().warning("===========================================");
        }

        webServer = new WebServer(this, port, token);
        try {
            webServer.start();
            getLogger().info("Dashboard web server started: http://localhost:" + port);
        } catch (Exception e) {
            getLogger().severe("Failed to start web server: " + e.getMessage());
        }

        if (getConfig().getBoolean("web.https.acme.enabled", false)) {
            long dayTicks = 20L * 60 * 60 * 24;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::autoRenewIfNeeded, dayTicks, dayTicks);
            getLogger().info("[ACME] Auto-renewal scheduler started (renews " + AcmeManager.RENEW_BEFORE_DAYS + " days before expiry)");
        }

        moduleManager = new ModuleManager(this);
        moduleManager.loadAll();
        if (moduleManager.count() > 0)
            getLogger().info("[Modules] " + moduleManager.count() + " module(s) loaded.");

        if (getConfig().getBoolean("chat.enabled", true)) {
            chatManager = new ChatManager(this);
            Bukkit.getPluginManager().registerEvents(chatManager, this);
            getLogger().info("[Chat] 채팅 채널 시스템 활성화됨 (" + chatManager.getChannels().size() + "개 채널)");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        announcementManager.startAll();
        getLogger().info("ServerDashboard plugin enabled.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) moduleManager.unloadAll();
        if (logManager != null) logManager.stop();
        if (webServer != null) webServer.stop();
        if (databaseManager != null) databaseManager.close();
        if (announcementManager != null) {
            announcementManager.stopAll();
            announcementManager.saveAll();
        }
        getLogger().info("ServerDashboard plugin disabled.");
    }

    private void autoRenewIfNeeded() {
        if (!acmeManager.needsRenewal()) return;
        getLogger().info("[ACME] Certificate expiring soon. Starting auto-renewal...");
        String domain        = getConfig().getString("web.https.acme.domain", "");
        String email         = getConfig().getString("web.https.acme.email", "");
        int challengePort    = getConfig().getInt("web.https.acme.challenge-port", 80);
        try {
            acmeManager.issueCertificate(domain, email, challengePort);
            webServer.reloadSsl();
            getLogger().info("[ACME] Auto-renewal complete.");
        } catch (Exception e) {
            getLogger().severe("[ACME] Auto-renewal failed: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ch") || command.getName().equalsIgnoreCase("channel")) {
            if (chatManager != null) chatManager.handleCh(sender, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase("msg") || command.getName().equalsIgnoreCase("tell")) {
            if (chatManager != null) chatManager.handleMsg(sender, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase("r") || command.getName().equalsIgnoreCase("reply")) {
            if (chatManager != null) chatManager.handleReply(sender, args);
            return true;
        }
        if (!command.getName().equalsIgnoreCase("dashboard")) return false;

        if (!sender.hasPermission("serverdashboard.admin")) {
            sender.sendMessage("§cYou do not have permission.");
            return true;
        }

        if (args.length == 0) {
            int port = getConfig().getInt("web.port", 8080);
            String token = getConfig().getString("web.token", "");
            int httpsPort = getConfig().getInt("web.https.port", 8443);
            boolean httpsEnabled = getConfig().getBoolean("web.https.enabled", false);
            sender.sendMessage("§a[Dashboard] §fHTTP:  §bhttp://your-server-ip:" + port);
            if (httpsEnabled)
                sender.sendMessage("§a[Dashboard] §fHTTPS: §bhttps://your-server-ip:" + httpsPort);
            sender.sendMessage("§a[Dashboard] §fAPI Token: §e" + token);
            if (acmeManager.hasCertificate())
                sender.sendMessage("§a[Dashboard] §fCert expiry: §e" + acmeManager.getCertExpiry());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                if (chatManager != null) chatManager.reload();
                sender.sendMessage("§a[Dashboard] §fConfig reloaded.");
            }
            case "reload-ssl" -> {
                if (webServer == null) { sender.sendMessage("§cWeb server is not running."); return true; }
                webServer.reloadSsl();
                sender.sendMessage("§a[Dashboard] §fSSL certificate reloaded.");
            }
            case "cert-issue" -> {
                if (!getConfig().getBoolean("web.https.acme.enabled", false)) {
                    sender.sendMessage("§c[Dashboard] §fSet web.https.acme.enabled: true in config.yml first.");
                    return true;
                }
                String domain     = getConfig().getString("web.https.acme.domain", "");
                String email      = getConfig().getString("web.https.acme.email", "");
                int challengePort = getConfig().getInt("web.https.acme.challenge-port", 80);
                if (domain.isBlank() || email.isBlank()) {
                    sender.sendMessage("§c[Dashboard] §fSet domain and email in config.yml.");
                    return true;
                }
                sender.sendMessage("§a[Dashboard] §fStarting certificate issuance... Check the console.");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        acmeManager.issueCertificate(domain, email, challengePort);
                        webServer.reloadSsl();
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§a[Dashboard] §fCertificate issued and HTTPS applied!")
                        );
                    } catch (Exception e) {
                        getLogger().severe("[ACME] Issuance failed: " + e.getMessage());
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§c[Dashboard] §fIssuance failed: " + e.getMessage())
                        );
                    }
                });
            }
            case "login" -> {
                String publicUrl = getConfig().getString("web.public-url", "").trim();
                if (publicUrl.isBlank()) {
                    int p = getConfig().getInt("web.port", 8080);
                    publicUrl = "http://localhost:" + p;
                }
                String tok = getConfig().getString("web.token", "");
                String loginUrl = publicUrl + "/?token=" + tok;
                Component link = Component.text()
                    .append(Component.text("[Dashboard] ", NamedTextColor.GREEN))
                    .append(Component.text("Click to open: ", NamedTextColor.WHITE))
                    .append(Component.text(loginUrl)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(loginUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Open dashboard and log in"))))
                    .build();
                sender.sendMessage(link);
            }
            case "reload-modules" -> {
                int before = moduleManager.count();
                moduleManager.reloadAll();
                int after = moduleManager.count();
                sender.sendMessage("§a[Dashboard] §fModules reloaded: " + before + " → " + after + " module(s).");
            }
            case "token" -> {
                byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                String newToken = HexFormat.of().formatHex(bytes);
                getConfig().set("web.token", newToken);
                saveConfig();
                sender.sendMessage("§a[Dashboard] §fNew token: §e" + newToken);
                sender.sendMessage("§cRestart the web server for the change to take effect.");
            }
            default -> sender.sendMessage("§cUsage: /dashboard [login|reload|reload-ssl|reload-modules|cert-issue|token]");
        }
        return true;
    }

    @EventHandler
    public void onServerPing(ServerListPingEvent e) {
        String motd = getConfig().getString("web.motd", "");
        if (motd != null && !motd.isBlank()) {
            e.motd(LegacyComponentSerializer.legacyAmpersand().deserialize(motd));
        }
    }

    public static DashboardPlugin getInstance() { return instance; }
    public AnnouncementManager getAnnouncementManager() { return announcementManager; }
    public AcmeManager getAcmeManager() { return acmeManager; }
    public LogManager getLogManager() { return logManager; }
    public ModuleManager getModuleManager() { return moduleManager; }
    public ChatManager getChatManager() { return chatManager; }
    public IamManager getIamManager() { return iamManager; }
    public AuditManager getAuditManager() { return auditManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
}
