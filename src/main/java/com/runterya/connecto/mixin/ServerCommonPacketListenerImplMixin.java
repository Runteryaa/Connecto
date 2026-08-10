package com.runterya.connecto.mixin;

import com.runterya.connecto.ConnectoConfig;
import com.runterya.connecto.ConnectoMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two-layer protection against keepalive timeouts for whitelisted accounts:
 *
 * Layer 1 (@Inject HEAD + cancel): Cancels keepConnectionAlive() entirely for whitelisted users
 * so keepAlivePending is never set to true and the disconnect branch is never reached.
 *
 * Layer 2 (@Redirect): Even if Layer 1 fails, intercepts the actual disconnect() call
 * within keepConnectionAlive() and skips it for whitelisted users.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {

    @Shadow
    protected Connection connection;

    @Shadow
    protected abstract GameProfile playerProfile();

    @Inject(method = "keepConnectionAlive", at = @At("HEAD"), cancellable = true)
    private void connecto$preventUserTimeout(CallbackInfo ci) {
        GameProfile profile = this.playerProfile();
        if (profile == null || profile.name() == null) return;

        String playerName = profile.name();
        ConnectoMod.LOGGER.debug("[Connecto] keepConnectionAlive called for: {}", playerName);

        if (!ConnectoConfig.getInstance().isWhitelisted(playerName)) return;

        // Suppress keepAlive timeout for whitelisted accounts silently
        ci.cancel();
    }

    /**
     * Backup: if the @Inject cancel somehow doesn't prevent the disconnect,
     * this @Redirect intercepts the actual disconnect() call within keepConnectionAlive()
     * and skips it for the whitelisted user.
     */
    @Redirect(
        method = "keepConnectionAlive",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"
        )
    )
    private void connecto$blockTimeoutDisconnect(ServerCommonPacketListenerImpl instance, Component message) {
        GameProfile profile = ((ServerCommonPacketListenerImplMixin) (Object) instance).playerProfile();
        if (profile != null && profile.name() != null) {
            String playerName = profile.name();
            if (ConnectoConfig.getInstance().isWhitelisted(playerName)) {
                ConnectoMod.LOGGER.warn("[Connecto] Blocked timeout disconnect for whitelisted user: {}", playerName);
                return; // Do NOT disconnect the user
            }
        }
        instance.disconnect(message);
    }
}
