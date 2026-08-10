package com.runterya.connecto.mixin;

import com.runterya.connecto.ConnectoConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin targeting {@link MinecraftServer} to prevent server auto-pausing when empty
 * when the uptime bot or Connecto bypass is active.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "pauseWhenEmpty", at = @At("HEAD"), cancellable = true)
    private void connecto$preventAutoPauseWhenEmpty(CallbackInfoReturnable<Boolean> cir) {
        if (ConnectoConfig.getInstance().enabled && ConnectoConfig.getInstance().uptimeBot) {
            cir.setReturnValue(false); // Never auto-pause server when uptime bot is active!
        }
    }
}
