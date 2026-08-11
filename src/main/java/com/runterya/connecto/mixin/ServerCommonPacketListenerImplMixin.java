package com.runterya.connecto.mixin;

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

        String uptimeBotName = com.runterya.connecto.ConnectoConfig.getInstance().uptimeBotName;
        
        // ONLY protect the embedded bot.
        // External whitelisted bots (like AFK mineflayer bots) MUST receive KeepAlives 
        // from the server, otherwise they will disconnect themselves with "Timed out".
        if (playerName.equals(uptimeBotName)) {
            // 1. Remove Netty's read timeout handler so the socket never closes from inactivity
            if (((ConnectionAccessor) this.connection).connecto$getChannel().pipeline().get("timeout") != null) {
                ((ConnectionAccessor) this.connection).connecto$getChannel().pipeline().remove("timeout");
            }
            
            // 2. Cancel the server's keepalive challenge so it doesn't wait for a response
            ci.cancel();
        }
    }

    // Backup redirect just for the embedded bot in case the above fails for some reason
    @Redirect(
        method = "keepConnectionAlive",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"
        )
    )
    private void connecto$blockTimeoutDisconnect(ServerCommonPacketListenerImpl instance, Component message) {
        GameProfile profile = ((ServerCommonPacketListenerImplMixin) (Object) instance).playerProfile();
        String uptimeBotName = com.runterya.connecto.ConnectoConfig.getInstance().uptimeBotName;
        if (profile != null && profile.name() != null && profile.name().equals(uptimeBotName)) {
            ConnectoMod.LOGGER.warn("[Connecto] Blocked timeout disconnect for embedded bot.");
            return; // Do NOT disconnect the embedded bot
        }
        instance.disconnect(message);
    }
}
