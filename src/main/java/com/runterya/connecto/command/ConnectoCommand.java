package com.runterya.connecto.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.runterya.connecto.ConnectoConfig;
import com.runterya.connecto.ConnectoMod;
import com.runterya.connecto.bot.EmbeddedBotManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public class ConnectoCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("connecto")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // OP level 2
                
                .then(Commands.literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
                
                .then(Commands.literal("reload")
                    .executes(ctx -> reloadConfig(ctx.getSource())))
                
                .then(Commands.literal("whitelist")
                    .then(Commands.literal("list")
                        .executes(ctx -> listWhitelist(ctx.getSource())))
                    .then(Commands.literal("add")
                        .then(Commands.argument("username", StringArgumentType.word())
                            .executes(ctx -> addWhitelist(ctx.getSource(), StringArgumentType.getString(ctx, "username")))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("username", StringArgumentType.word())
                            .executes(ctx -> removeWhitelist(ctx.getSource(), StringArgumentType.getString(ctx, "username")))))
                )
            );
        });
    }

    private static int showStatus(CommandSourceStack source) {
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        boolean botRunning = EmbeddedBotManager.isRunning();
        
        String msg = "§6=== Connecto Status ===\n" +
                "§eAuth Bypass: " + (cfg.enabled ? "§aENABLED" : "§cDISABLED") + "\n" +
                "§eUptime Bot: " + (cfg.uptimeBot ? "§aENABLED" : "§cDISABLED") + 
                " §7(State: " + (botRunning ? "§aRUNNING" : "§cSTOPPED") + " §7- Mode: §b" + cfg.uptimeBotMode + ")\n" +
                "§eBot Name: §b" + cfg.uptimeBotName + "\n" +
                "§eRelay Proxy: " + (cfg.relayProxy ? "§aENABLED" : "§cDISABLED") + " §7(" + cfg.relayProxyUrl + ")\n" +
                "§eOffline Skins: " + (cfg.fetchOfflineSkins ? "§aENABLED" : "§cDISABLED") + 
                (cfg.defaultOfflineSkinUser.isBlank() ? "" : " §7(Default: §b" + cfg.defaultOfflineSkinUser + "§7)") + "\n" +
                "§eWhitelisted Accounts: §b" + cfg.whitelist.size() + " user(s)";

        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        ConnectoConfig.load(FabricLoader.getInstance().getConfigDir());
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        com.runterya.connecto.skin.SkinFetcher.preFetchDefaultSkin(cfg.defaultOfflineSkinUser);
        MinecraftServer server = source.getServer();
        ConnectoMod.checkAndUpdateBotStatus(server);
        source.sendSuccess(() -> Component.literal("§a[Connecto] Configuration reloaded successfully!"), true);
        return 1;
    }

    private static int listWhitelist(CommandSourceStack source) {
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        String list = String.join(", ", cfg.whitelist);
        source.sendSuccess(() -> Component.literal("§e[Connecto] Whitelisted accounts: §b" + (list.isEmpty() ? "None" : list)), false);
        return 1;
    }

    private static int addWhitelist(CommandSourceStack source, String username) {
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        if (!cfg.whitelist.contains(username)) {
            cfg.whitelist.add(username);
            ConnectoConfig.save(FabricLoader.getInstance().getConfigDir());
            source.sendSuccess(() -> Component.literal("§a[Connecto] Added '" + username + "' to whitelist."), true);
        } else {
            source.sendSuccess(() -> Component.literal("§c[Connecto] Username '" + username + "' is already whitelisted."), false);
        }
        return 1;
    }

    private static int removeWhitelist(CommandSourceStack source, String username) {
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        if (cfg.whitelist.removeIf(u -> u.equalsIgnoreCase(username))) {
            ConnectoConfig.save(FabricLoader.getInstance().getConfigDir());
            source.sendSuccess(() -> Component.literal("§a[Connecto] Removed '" + username + "' from whitelist."), true);
        } else {
            source.sendSuccess(() -> Component.literal("§c[Connecto] Username '" + username + "' was not in whitelist."), false);
        }
        return 1;
    }
}
