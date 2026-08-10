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

    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), argsOnly = true)
    private com.mojang.authlib.GameProfile connecto$forceWhitelistedProfileInVerify(com.mojang.authlib.GameProfile profile) {
        if (this.connecto$currentConnectingUser != null && ConnectoConfig.getInstance().isWhitelisted(this.connecto$currentConnectingUser)) {
            java.util.UUID offlineUuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(this.connecto$currentConnectingUser);
            com.mojang.authlib.GameProfile correctProfile = new com.mojang.authlib.GameProfile(offlineUuid, this.connecto$currentConnectingUser);
            ConnectoMod.LOGGER.info("[Connecto] verify INTERCEPTED! Original Profile: {} -> Forced Profile: {}", profile != null ? profile.id() : "null", correctProfile.id());
            this.authenticatedProfile = correctProfile; // Overwrite the field too!
            return correctProfile;
        }
        return profile;
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

    /**
     * Prevents vanilla 30-second "Took too long to log in" timeout for whitelisted accounts / uptime bot.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void connecto$preventSlowLoginTimeout(CallbackInfo ci) {
        if (this.connecto$currentConnectingUser != null &&
            ConnectoConfig.getInstance().isWhitelisted(this.connecto$currentConnectingUser)) {
            // Cancel vanilla login tick timeout for whitelisted accounts
            ci.cancel();
        }
    }
}
