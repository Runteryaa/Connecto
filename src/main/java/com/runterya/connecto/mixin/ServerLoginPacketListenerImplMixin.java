package com.runterya.connecto.mixin;

import com.runterya.connecto.ConnectoConfig;
import com.runterya.connecto.ConnectoMod;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin targeting {@link ServerLoginPacketListenerImpl} to bypass Mojang session
 * authentication for whitelisted accounts without needing any version-specific
 * shadowed methods.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Unique
    private String connecto$currentConnectingUser = null;

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void connecto$captureUsername(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.connecto$currentConnectingUser = packet.name();
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.jetbrains.annotations.Nullable
    private com.mojang.authlib.GameProfile authenticatedProfile;

    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("RETURN"))
    private void connecto$forceProfileAfterVerify(com.mojang.authlib.GameProfile profile, CallbackInfo ci) {
        // Run after all other verify logic to ensure Floodgate hasn't hijacked the UUID.
        // We force the authenticatedProfile to have the correct deterministic offline UUID and name.
        if (this.connecto$currentConnectingUser != null && ConnectoConfig.getInstance().isWhitelisted(this.connecto$currentConnectingUser)) {
            java.util.UUID offlineUuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(this.connecto$currentConnectingUser);
            if (this.authenticatedProfile == null || !this.authenticatedProfile.id().equals(offlineUuid)) {
                this.authenticatedProfile = new com.mojang.authlib.GameProfile(offlineUuid, this.connecto$currentConnectingUser);
                ConnectoMod.LOGGER.info("[Connecto] Forced GameProfile in verifyLoginAndFinishConnectionSetup for whitelisted bot: {} -> {}", this.connecto$currentConnectingUser, offlineUuid);
            }
        }
        if (ConnectoConfig.getInstance().fetchOfflineSkins && this.authenticatedProfile != null) {
            com.runterya.connecto.skin.SkinFetcher.applySkinIfMissing(this.authenticatedProfile);
        }
    }

    @Redirect(
        method = "handleHello",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
        )
    )
    private boolean connecto$bypassOnlineModeForWhitelisted(MinecraftServer server) {
        if (this.connecto$currentConnectingUser != null &&
            ConnectoConfig.getInstance().isWhitelisted(this.connecto$currentConnectingUser)) {

            ConnectoMod.LOGGER.info(
                "[Connecto] Whitelisted user '{}' detected – bypassing Mojang online authentication.",
                this.connecto$currentConnectingUser
            );
            return false; // Force offline-mode auth path for this user
        }
        return server.usesAuthentication();
    }
}
