package com.runterya.connecto.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to read/write private fields of ServerCommonPacketListenerImpl.
 * We need keepAlivePending so we can reset it for the bot, preventing disconnects.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonListenerAccessor {

    @Accessor("keepAlivePending")
    boolean connecto$isKeepAlivePending();

    @Accessor("keepAlivePending")
    void connecto$setKeepAlivePending(boolean value);

    @Accessor("keepAliveTime")
    long connecto$getKeepAliveTime();

    @Accessor("keepAliveTime")
    void connecto$setKeepAliveTime(long value);
}
