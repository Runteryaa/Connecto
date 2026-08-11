package com.runterya.connecto;

import com.runterya.connecto.bot.EmbeddedBotManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Main mod initializer for Connecto.
 * Loads configuration on server startup and manages embedded TCP bot lifecycle.
 */
public class ConnectoMod implements ModInitializer {

    public static final String MOD_ID = "connecto";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        ConnectoConfig.load(configDir);

        ConnectoConfig config = ConnectoConfig.getInstance();
        LOGGER.info("[Connecto] Initialized. Auth bypass is {}.",
                config.enabled ? "ENABLED" : "DISABLED");

        // Register Fabric lifecycle events to start/stop embedded TCP bot
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (config.enabled && config.uptimeBot) {
                int port = server.getPort();
                if (port <= 0) port = 25565;
                EmbeddedBotManager.start(port, config.uptimeBotName);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EmbeddedBotManager.stop();
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (config.enabled && config.uptimeBot) {
                for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.getName().getString().equals(config.uptimeBotName)) {
                        player.resetLastActionTime();
                    }
                }
            }
        });
    }
}
