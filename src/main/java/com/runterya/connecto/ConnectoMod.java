package com.runterya.connecto;

import com.runterya.connecto.bot.EmbeddedBotManager;
import com.runterya.connecto.command.ConnectoCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

        // Register in-game commands
        ConnectoCommand.register();

        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            checkAndUpdateBotStatus(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EmbeddedBotManager.stop();
        });

        // Player join & disconnect listeners for SMART / EMPTY_ONLY uptime bot mode
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            checkAndUpdateBotStatus(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Run on next server tick after player leaves
            server.execute(() -> checkAndUpdateBotStatus(server));
        });

        // Heartbeat tick to prevent timeout & AFK kicks for the internal bot
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (config.enabled && config.uptimeBot) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.getName().getString().equalsIgnoreCase(config.uptimeBotName)) {
                        player.resetLastActionTime();
                        // Nudge head rotation every 10 seconds to bypass strict Anti-AFK plugins
                        if (config.antiAfk && server.getTickCount() % 200 == 0) {
                            player.setYRot((player.getYRot() + 1.0f) % 360.0f);
                        }
                    }
                }
            }
        });
    }

    public static void notifyOps(MinecraftServer server, net.minecraft.network.chat.Component message) {
        if (server == null || message == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
    }

    public static void checkAndUpdateBotStatus(MinecraftServer server) {
        ConnectoConfig config = ConnectoConfig.getInstance();
        if (!config.enabled || !config.uptimeBot) {
            if (EmbeddedBotManager.isRunning()) {
                EmbeddedBotManager.stop();
            }
            return;
        }

        String mode = config.uptimeBotMode != null ? config.uptimeBotMode.toUpperCase().trim() : "ALWAYS";
        boolean isSmart = "SMART".equals(mode) || "EMPTY_ONLY".equals(mode);

        if (isSmart) {
            long realPlayerCount = server.getPlayerList().getPlayers().stream()
                    .filter(p -> !p.getGameProfile().name().equalsIgnoreCase(config.uptimeBotName))
                    .count();

            if (realPlayerCount > 0) {
                if (EmbeddedBotManager.isRunning()) {
                    LOGGER.info("[Connecto] Real player(s) online ({}), stopping Smart Uptime Bot.", realPlayerCount);
                    EmbeddedBotManager.stop();
                }
            } else {
                if (!EmbeddedBotManager.isRunning()) {
                    LOGGER.info("[Connecto] 0 real players online, starting Smart Uptime Bot.");
                    startBot(server);
                }
            }
        } else {
            // ALWAYS mode
            if (!EmbeddedBotManager.isRunning()) {
                startBot(server);
            }
        }
    }

    private static void startBot(MinecraftServer server) {
        ConnectoConfig config = ConnectoConfig.getInstance();
        int port = config.botConnectPort;
        if (port <= 0) port = server.getPort();
        if (port <= 0) port = 25565;
        String ip = config.botConnectIp;
        if (ip == null || ip.isBlank()) ip = "127.0.0.1";

        EmbeddedBotManager.start(ip, port, config.uptimeBotName);
    }
}
