package com.runterya.connecto.mixin;

import com.runterya.connecto.ConnectoConfig;
import com.runterya.connecto.ConnectoMod;
import com.runterya.connecto.bot.EmbeddedBotManager;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin targeting {@link ServerLoginPacketListenerImpl} to bypass Mojang session
 * authentication for explicitly whitelisted accounts or authenticated internal bots.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Unique
    private String connecto$currentConnectingUser = null;
    @Unique
    private UUID connecto$currentConnectingUuid = null;

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void connecto$captureUsername(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.connecto$currentConnectingUser = packet.name();
        this.connecto$currentConnectingUuid = packet.profileId();
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.jetbrains.annotations.Nullable
    private com.mojang.authlib.GameProfile authenticatedProfile;

    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("RETURN"))
    private void connecto$forceProfileAfterVerify(com.mojang.authlib.GameProfile profile, CallbackInfo ci) {
        // Check if user is either our authenticated embedded bot or explicitly whitelisted
        if (this.connecto$currentConnectingUser != null && isUserExempt()) {
            UUID offlineUuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(this.connecto$currentConnectingUser);
            if (this.authenticatedProfile == null || !this.authenticatedProfile.id().equals(offlineUuid)) {
                this.authenticatedProfile = new com.mojang.authlib.GameProfile(offlineUuid, this.connecto$currentConnectingUser);
                ConnectoMod.LOGGER.info("[Connecto] Forced GameProfile in verifyLoginAndFinishConnectionSetup for exempt user: {} -> {}", this.connecto$currentConnectingUser, offlineUuid);
            }
        }
        if (this.authenticatedProfile != null) {
            ConnectoConfig cfg = ConnectoConfig.getInstance();
            this.authenticatedProfile = com.runterya.connecto.skin.SkinFetcher.processSkin(this.authenticatedProfile, cfg.fetchOfflineSkins, cfg.defaultOfflineSkinUser);
        }
    }

    @org.spongepowered.asm.mixin.Shadow
    MinecraftServer server;

    @Redirect(
        method = "handleHello",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
        )
    )
    private boolean connecto$bypassOnlineModeForWhitelisted(MinecraftServer server) {
        ConnectoConfig cfg = ConnectoConfig.getInstance();
        boolean isExempt = isUserExempt();
        
        if (this.connecto$currentConnectingUser != null) {
            boolean isBotName = this.connecto$currentConnectingUser.equalsIgnoreCase(cfg.uptimeBotName);
            boolean isTokenValid = EmbeddedBotManager.isEmbeddedBotToken(this.connecto$currentConnectingUser, this.connecto$currentConnectingUuid);
            
            // Immediately update bot status when a real player starts logging in
            if (!isBotName && server != null) {
                server.execute(() -> ConnectoMod.checkAndUpdateBotStatus(server));
            }

            // Security audit alert: External player trying to use bot's name
            if (isBotName && !isTokenValid) {
                ConnectoMod.LOGGER.warn("[Connecto Security Audit] External user attempted connection with protected bot name '{}'. Token invalid/missing.", this.connecto$currentConnectingUser);
                if (cfg.securityAlerts) {
                    ConnectoMod.notifyOps(server, net.minecraft.network.chat.Component.literal(
                        "§c[Connecto Alert] §eUnauthorized join attempt using protected bot username '§f" + this.connecto$currentConnectingUser + "§e'!"
                    ));
                }
            }
        }

        if (this.connecto$currentConnectingUser != null && isExempt) {
            ConnectoMod.LOGGER.info(
                "[Connecto] Exempt user '{}' detected – bypassing Mojang online authentication.",
                this.connecto$currentConnectingUser
            );
            return false; // Force offline-mode auth path for this user
        }
        return server.usesAuthentication();
    }

    @Unique
    private boolean isUserExempt() {
        if (this.connecto$currentConnectingUser == null) return false;
        
        // 1. Is it our authenticated embedded bot (verified with secret session token)?
        if (EmbeddedBotManager.isEmbeddedBotToken(this.connecto$currentConnectingUser, this.connecto$currentConnectingUuid)) {
            return true;
        }

        // 2. Is the user explicitly listed in the whitelist by the server owner?
        return ConnectoConfig.getInstance().isWhitelisted(this.connecto$currentConnectingUser);
    }
}
