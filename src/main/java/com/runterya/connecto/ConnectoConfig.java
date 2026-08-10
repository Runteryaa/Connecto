package com.runterya.connecto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
     * These must also match what the Mineflayer bot sends as its username.
     */
    public List<String> botUsernames = new ArrayList<>(List.of("Secret_AFK_Bot"));

    /**
     * Optional: all usernames that START with this prefix are treated as bot
     * accounts regardless of the exact name. Leave empty ("") to disable.
     */
    public String secretPrefix = "";

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
     * Ensures all fields are non-null after deserialization.
     */
    public void sanitize() {
        if (botUsernames == null) {
            botUsernames = new ArrayList<>(List.of("Secret_AFK_Bot"));
        }
        if (secretPrefix == null) {
            secretPrefix = "";
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
            LOGGER.info("[Connecto] Config loaded from {}. Enabled={}, bots={}",
                    file, instance.enabled, instance.botUsernames);
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

    // ---- Helpers used by the Mixin ----

    /**
     * Returns {@code true} if the given username should bypass Mojang auth.
     *
     * @param username the name provided in the client's login-hello packet
     */
    public boolean isBotUsername(String username) {
        if (username == null) return false;
        // Note: intentionally NOT checking 'enabled' here – we always allow listed bots.

        sanitize();

        // Exact-match list
        for (String bot : botUsernames) {
            if (bot != null && bot.equalsIgnoreCase(username)) return true;
        }

        // Prefix match (only when a non-empty prefix is configured)
        if (!secretPrefix.isBlank() && username.startsWith(secretPrefix)) return true;

        return false;
    }
}
