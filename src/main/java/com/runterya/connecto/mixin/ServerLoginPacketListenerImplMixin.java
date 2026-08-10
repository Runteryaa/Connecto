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
 * authentication for whitelisted bot accounts without needing any version-specific
 * shadowed methods.
 *
 * <p>How it works:</p>
 * <ol>
 *   <li>{@code handleHello} starts and receives the player's username.</li>
 *   <li>The mixin captures the username at {@code HEAD}.</li>
 *   <li>When vanilla {@code handleHello} evaluates {@code server.usesAuthentication()},
 *       our {@code @Redirect} checks if the username matches the bot whitelist.</li>
 *   <li>If matched, it returns {@code false}, forcing vanilla to take the offline-mode
 *       branch (creating an offline GameProfile and calling verification natively).</li>
 *   <li>For normal players, it returns {@code server.usesAuthentication()} (true),
 *       running normal Mojang online-mode auth.</li>
 * </ol>
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
    private com.mojang.authlib.GameProfile connecto$forceBotProfileInVerify(com.mojang.authlib.GameProfile profile) {
        if (this.connecto$currentConnectingUser != null && ConnectoConfig.getInstance().isBotUsername(this.connecto$currentConnectingUser)) {
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
    private boolean connecto$bypassOnlineModeForBots(MinecraftServer server) {
        if (this.connecto$currentConnectingUser != null &&
            ConnectoConfig.getInstance().isBotUsername(this.connecto$currentConnectingUser)) {

            ConnectoMod.LOGGER.info(
                "[Connecto] Bot '{}' detected – bypassing Mojang online authentication.",
                this.connecto$currentConnectingUser
            );
            return false; // Force offline-mode auth path for this bot
        }
        return server.usesAuthentication();
    }
}
