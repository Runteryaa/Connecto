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
 */
public class ConnectoConfig {

    public static final Logger LOGGER = LoggerFactory.getLogger("Connecto/Config");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "connecto.json";

    // ---- Config fields (public for direct Gson deserialization) ----

    /** Master toggle. Set to false to disable all bypass logic instantly. */
    public boolean enabled = true;

    /**
     * Exact usernames that are allowed to bypass Mojang auth.
     */
    public List<String> whitelist = new ArrayList<>(List.of("uptime", "Runterya"));

    /** Legacy config fallback for backwards compatibility */
    @SerializedName("botUsernames")
    private List<String> legacyBotUsernames;

    /**
     * Optional: all usernames that START with this prefix are treated as exempt
     * accounts regardless of the exact name. Use "*" to allow all usernames. Leave empty ("") to disable.
     */
    public String whitelistPrefix = "";

    /** Legacy prefix fallback for backwards compatibility */
    @SerializedName("secretPrefix")
    private String legacySecretPrefix;

    /**
     * Beta feature: Automatically launch an embedded TCP client when server starts
     * to keep hosting providers (Play.Hosting, Aternos, etc.) active 24/7 without auto-shutdown.
     */
    public boolean uptimeBot = true;

    /** Legacy toggle fallback for backwards compatibility */
    @SerializedName("autoConnectBot")
    private Boolean legacyAutoConnectBot;

    /** Username for the auto-connecting embedded TCP bot */
    public String botName = "uptime";

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
        if (botName == null || botName.isBlank()) {
            botName = "uptime";
        }
    }

    /**
     * Loads config from {@code configDir/connecto.json}, creating a default
     * file if it does not yet exist.
     *
     * @param configDir the Fabric config directory (e.g. {@code ./config})
     */
    public static void load(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE);

        if (!Files.exists(file)) {
            LOGGER.info("[Connecto] Config not found – writing defaults to {}", file);
            instance = new ConnectoConfig();
            instance.sanitize();
            save(configDir);
            return;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            instance = GSON.fromJson(reader, ConnectoConfig.class);
            if (instance == null) {
                LOGGER.warn("[Connecto] Config file was empty – loading default values");
                instance = new ConnectoConfig();
            }
            instance.sanitize();
            LOGGER.info("[Connecto] Config loaded from {}. Enabled={}, whitelist={}, prefix='{}', uptimeBot={}",
                    file, instance.enabled, instance.whitelist, instance.whitelistPrefix, instance.uptimeBot);
        } catch (Exception e) {
            LOGGER.error("[Connecto] Failed to read config – using defaults", e);
            instance = new ConnectoConfig();
            instance.sanitize();
        }
    }

    /** Persists the current config instance to disk. */
    public static void save(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(configDir);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(getInstance(), writer);
            }
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
