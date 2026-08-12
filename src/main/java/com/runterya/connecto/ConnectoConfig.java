package com.runterya.connecto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles loading and saving of the connecto.json config file.
 * Config path: config/connecto.json (relative to server root).
 * Handles loading and saving of the connecto.properties config file.
 * Config path: config/connecto.properties (relative to server root).
 */
public class ConnectoConfig {

    public static final Logger LOGGER = LoggerFactory.getLogger("Connecto/Config");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "connecto.properties";

    // ---- Config fields ----
    public boolean enabled = true;
    public List<String> whitelist = new ArrayList<>(List.of("uptime", "Runterya"));
    
    @SerializedName("botUsernames")
    private List<String> legacyBotUsernames;
    
    public String whitelistPrefix = "";
    
    @SerializedName("secretPrefix")
    private String legacySecretPrefix;
    
    public boolean uptimeBot = false;
    @SerializedName("autoConnectBot")
    private Boolean legacyAutoConnectBot;
    
    public String uptimeBotName = "uptime";
    
    // New fields for NAT loopback/external connection & skin fetching
    public String botConnectIp = "127.0.0.1";
    public int botConnectPort = 25565;
    public boolean relayProxy = false;
    public String relayProxyUrl = "connectorelay.onrender.com";
    public boolean fetchOfflineSkins = true;
    public String defaultOfflineSkinUser = "";
    public String uptimeBotMode = "ALWAYS";
    public int reconnectDelaySeconds = 5;
    public boolean securityAlerts = true;

    // ---- Singleton / loading ----

    private static ConnectoConfig instance;

    public static ConnectoConfig getInstance() {
        if (instance == null) {
            instance = new ConnectoConfig();
            instance.sanitize();
        }
        return instance;
    }

    /**
     * Ensures all fields are non-null after deserialization and handles migration from legacy keys.
     */
    public void sanitize() {
        if (whitelist == null) {
            if (legacyBotUsernames != null && !legacyBotUsernames.isEmpty()) {
                whitelist = new ArrayList<>(legacyBotUsernames);
            } else {
                whitelist = new ArrayList<>(List.of("uptime", "Runterya"));
            }
        }
        if (whitelistPrefix == null) {
            if (legacySecretPrefix != null) {
                whitelistPrefix = legacySecretPrefix;
            } else {
                whitelistPrefix = "";
            }
        }
        if (legacyAutoConnectBot != null) {
            uptimeBot = legacyAutoConnectBot;
        }
        if (uptimeBotName == null || uptimeBotName.isBlank()) {
            uptimeBotName = "uptime";
        }
        if (botConnectIp == null || botConnectIp.isBlank()) {
            botConnectIp = "127.0.0.1";
        }
    }

    /**
     * Loads config from {@code configDir/connecto.properties}, migrating from JSON if necessary.
     */
    public static void load(Path configDir) {
        Path oldJson = configDir.resolve("connecto.json");
        if (Files.exists(oldJson)) {
            LOGGER.info("[Connecto] Found legacy connecto.json, migrating to connecto.properties...");
            try (Reader reader = Files.newBufferedReader(oldJson)) {
                instance = GSON.fromJson(reader, ConnectoConfig.class);
                if (instance != null) {
                    instance.sanitize();
                    save(configDir); // Save new properties format
                    Files.move(oldJson, configDir.resolve("connecto.json.old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOGGER.error("[Connecto] Failed to migrate JSON config", e);
            }
        }

        Path file = configDir.resolve(CONFIG_FILE);

        if (!Files.exists(file)) {
            LOGGER.info("[Connecto] Config not found - writing defaults to {}", file);
            if (instance == null) {
                instance = new ConnectoConfig();
                instance.sanitize();
            }
            save(configDir);
            return;
        }

        try {
            if (instance == null) {
                instance = new ConnectoConfig();
            }
            java.util.Properties props = new java.util.Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            }
            
            if (props.containsKey("enabled")) instance.enabled = Boolean.parseBoolean(props.getProperty("enabled"));
            if (props.containsKey("whitelist")) {
                String wl = props.getProperty("whitelist", "");
                instance.whitelist = new ArrayList<>(java.util.Arrays.asList(wl.split(",")));
                instance.whitelist.replaceAll(String::trim);
                instance.whitelist.removeIf(String::isEmpty);
            }
            if (props.containsKey("whitelistPrefix")) instance.whitelistPrefix = props.getProperty("whitelistPrefix");
            if (props.containsKey("uptimeBot")) instance.uptimeBot = Boolean.parseBoolean(props.getProperty("uptimeBot"));
            if (props.containsKey("uptimeBotName")) instance.uptimeBotName = props.getProperty("uptimeBotName");
            else if (props.containsKey("botName")) instance.uptimeBotName = props.getProperty("botName"); // Legacy property support
            if (props.containsKey("botConnectIp")) instance.botConnectIp = props.getProperty("botConnectIp");
            if (props.containsKey("botConnectPort")) {
                try { instance.botConnectPort = Integer.parseInt(props.getProperty("botConnectPort")); } catch (NumberFormatException ignored) {}
            }
            if (props.containsKey("relayProxy")) instance.relayProxy = Boolean.parseBoolean(props.getProperty("relayProxy"));
            if (props.containsKey("relayProxyUrl")) instance.relayProxyUrl = props.getProperty("relayProxyUrl");
            if (props.containsKey("fetchOfflineSkins")) instance.fetchOfflineSkins = Boolean.parseBoolean(props.getProperty("fetchOfflineSkins"));
            if (props.containsKey("defaultOfflineSkinUser")) instance.defaultOfflineSkinUser = props.getProperty("defaultOfflineSkinUser");
            if (props.containsKey("uptimeBotMode")) instance.uptimeBotMode = props.getProperty("uptimeBotMode");
            if (props.containsKey("reconnectDelaySeconds")) {
                try { instance.reconnectDelaySeconds = Integer.parseInt(props.getProperty("reconnectDelaySeconds")); } catch (NumberFormatException ignored) {}
            }
            if (props.containsKey("securityAlerts")) instance.securityAlerts = Boolean.parseBoolean(props.getProperty("securityAlerts"));
            
            instance.sanitize();
            save(configDir); // Auto-update config file on disk with any missing new options
            LOGGER.info("[Connecto] Config loaded from {}. Enabled={}, whitelist={}, prefix='{}', uptimeBot={}",
                    file, instance.enabled, instance.whitelist, instance.whitelistPrefix, instance.uptimeBot);
        } catch (Exception e) {
            LOGGER.error("[Connecto] Failed to read config - using defaults", e);
            if (instance == null) instance = new ConnectoConfig();
            instance.sanitize();
        }
    }

    /** Persists the current config instance to disk with updated comments and organized sections. */
    public static void save(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(configDir);
            String content = """
                    # ====================================================================
                    #                             Connecto
                    # ====================================================================
                    
                    # --------------------------------------------------------------------
                    # [1] Master Toggle & Authentication Bypass Settings
                    # --------------------------------------------------------------------
                    
                    # Master toggle. Set to false to disable all bypass logic instantly.
                    enabled=%s
                    
                    # Exact usernames that are allowed to bypass Mojang auth.
                    # Separate multiple names with commas (e.g. Runterya, Player2).
                    whitelist=%s
                    
                    # Optional: all usernames that START with this prefix are treated as exempt.
                    # Use "*" to allow all usernames. Leave empty to disable.
                    whitelistPrefix=%s
                    
                    # --------------------------------------------------------------------
                    # [2] Offline Player Skin Settings
                    # --------------------------------------------------------------------
                    
                    # Automatically fetch and display official Mojang skins for offline/bypassed players.
                    fetchOfflineSkins=%s
                    
                    # Default Minecraft username to use as fallback skin for offline players.
                    # If fetchOfflineSkins is true, this skin will be used for players without an official Mojang skin.
                    # If fetchOfflineSkins is false, this skin will be used for all offline players. Leave empty to disable.
                    defaultOfflineSkinUser=%s
                    
                    # --------------------------------------------------------------------
                    # [3] Uptime Bot & Network Proxy Settings
                    # --------------------------------------------------------------------
                    
                    # Automatically launch an embedded TCP client when server starts
                    # to keep hosting providers (Play.Hosting, Aternos, etc.) active 24/7.
                    uptimeBot=%s
                    
                    # Operating mode for the uptime bot:
                    # 'ALWAYS' = Bot connects when server starts and stays connected 24/7.
                    # 'SMART'  = Bot connects ONLY when 0 real players are online, and automatically disconnects when a real player joins!
                    uptimeBotMode=%s
                    
                    # Username for the auto-connecting embedded TCP bot.
                    uptimeBotName=%s
                    
                    # Delay (in seconds) before the uptime bot automatically attempts to reconnect after getting kicked or disconnected.
                    reconnectDelaySeconds=%s
                    
                    # The IP address the internal bot uses to connect. Leave as 127.0.0.1 for local connection.
                    # If your host puts the server to sleep, try setting this to your server's PUBLIC IP (e.g. play.hosting.com).
                    botConnectIp=%s
                    
                    # The port the internal bot connects to. -1 means it will automatically detect the server's port.
                    botConnectPort=%s
                    
                    # Set to true to route the bot's traffic through the WebSocket relay proxy.
                    relayProxy=%s
                    
                    # Multi-Tenant Relay Proxy URL (e.g., connectorelay.onrender.com)
                    # If set, the bot will route traffic through this WebSocket proxy to bypass host anti-SSRF protections.
                    relayProxyUrl=%s
                    
                    # --------------------------------------------------------------------
                    # [4] Security Alerts & Audit Settings
                    # --------------------------------------------------------------------
                    
                    # Enable in-game & console security alerts when an unauthorized user attempts to join using a protected bot or whitelisted name.
                    securityAlerts=%s
                    """.formatted(
                    instance.enabled,
                    String.join(",", instance.whitelist),
                    instance.whitelistPrefix,
                    instance.fetchOfflineSkins,
                    instance.defaultOfflineSkinUser == null ? "" : instance.defaultOfflineSkinUser,
                    instance.uptimeBot,
                    instance.uptimeBotMode == null ? "ALWAYS" : instance.uptimeBotMode,
                    instance.uptimeBotName,
                    instance.reconnectDelaySeconds,
                    instance.botConnectIp,
                    instance.botConnectPort,
                    instance.relayProxy,
                    instance.relayProxyUrl == null ? "" : instance.relayProxyUrl,
                    instance.securityAlerts
            );
            Files.writeString(file, content);
        } catch (IOException e) {
            LOGGER.error("[Connecto] Failed to save config", e);
        }
    }

    // ---- Helpers used by Mixins ----

    /**
     * Returns {@code true} if the given username is on the whitelist or matches the prefix and should bypass Mojang auth.
     * Special value "*" in {@code whitelistPrefix} matches ALL usernames.
     *
     * @param username the name provided in the client's login packet
     */
    public boolean isWhitelisted(String username) {
        if (username == null) return false;

        sanitize();

        // Wildcard '*' allows ALL usernames to bypass auth
        if ("*".equals(whitelistPrefix.trim())) return true;

        // Exact-match list
        for (String user : whitelist) {
            if (user != null && user.equalsIgnoreCase(username)) return true;
        }

        // Prefix match (only when a non-empty prefix is configured)
        if (!whitelistPrefix.isBlank() && username.startsWith(whitelistPrefix)) return true;

        return false;
    }
}
