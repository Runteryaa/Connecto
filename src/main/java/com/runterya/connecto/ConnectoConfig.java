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
    
    // New fields for NAT loopback/external connection
    public String botConnectIp = "127.0.0.1";
    public int botConnectPort = 25565;
    public String relayProxyUrl = "";

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
            if (props.containsKey("relayProxyUrl")) instance.relayProxyUrl = props.getProperty("relayProxyUrl");
            
            instance.sanitize();
            LOGGER.info("[Connecto] Config loaded from {}. Enabled={}, whitelist={}, prefix='{}', uptimeBot={}",
                    file, instance.enabled, instance.whitelist, instance.whitelistPrefix, instance.uptimeBot);
        } catch (Exception e) {
            LOGGER.error("[Connecto] Failed to read config - using defaults", e);
            if (instance == null) instance = new ConnectoConfig();
            instance.sanitize();
        }
    }

    /** Persists the current config instance to disk. */
    public static void save(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(configDir);
            String content = """
                    # ==========================================
                    # Connecto Configuration File
                    # ==========================================
                    
                    # Master toggle. Set to false to disable all bypass logic instantly.
                    enabled=%s
                    
                    # Exact usernames that are allowed to bypass Mojang auth.
                    # Separate multiple names with commas.
                    whitelist=%s
                    
                    # Optional: all usernames that START with this prefix are treated as exempt.
                    # Use "*" to allow all usernames. Leave empty to disable.
                    whitelistPrefix=%s
                    
                    # Beta feature: Automatically launch an embedded TCP client when server starts
                    # to keep hosting providers (Play.Hosting, Aternos, etc.) active 24/7.
                    # Note: Depending on the host's prevention systems, this internal bot might not work on every server.
                    uptimeBot=%s
                    
                    # Username for the auto-connecting embedded TCP bot.
                    uptimeBotName=%s
                    
                    # The IP address the internal bot uses to connect. Leave as 127.0.0.1 for local connection.
                    # If your host puts the server to sleep, try setting this to your server's PUBLIC IP (e.g. play.hosting.com).
                    # This will route the bot's traffic through the internet (NAT Loopback) and trick the host into thinking there is external traffic.
                    botConnectIp=%s
                    
                    # The port the internal bot connects to. -1 means it will automatically detect the server's port.
                    botConnectPort=%s
                    
                    # Multi-Tenant Relay Proxy URL (e.g., wss://connecto-relay.onrender.com)
                    # If set, the bot will route traffic through this WebSocket proxy to bypass host anti-SSRF protections.
                    relayProxyUrl=%s
                    """.formatted(
                    instance.enabled,
                    String.join(",", instance.whitelist),
                    instance.whitelistPrefix,
                    instance.uptimeBot,
                    instance.uptimeBotName,
                    instance.botConnectIp,
                    instance.botConnectPort,
                    instance.relayProxyUrl == null ? "" : instance.relayProxyUrl
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

        // Auto-whitelist the embedded bot if enabled
        if (uptimeBot && uptimeBotName != null && uptimeBotName.equalsIgnoreCase(username)) return true;

        // Exact-match list
        for (String user : whitelist) {
            if (user != null && user.equalsIgnoreCase(username)) return true;
        }

        // Prefix match (only when a non-empty prefix is configured)
        if (!whitelistPrefix.isBlank() && username.startsWith(whitelistPrefix)) return true;

        return false;
    }
}
