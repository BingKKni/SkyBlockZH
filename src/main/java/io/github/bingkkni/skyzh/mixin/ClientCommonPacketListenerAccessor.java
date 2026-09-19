package io.github.bingkkni.skyzh.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The network session behind a packet listener, for {@link CustomPayloadPacketMixin}.
 *
 * <p>Only the play-phase listener exposes its connection publicly. The configuration-phase listener —
 * which is what the client is holding during the initial join and again every time Hypixel moves the
 * player to another backend — keeps the same field protected, and the hello or brand can arrive in
 * either phase. The connection object outlives both listeners, which is why the evidence is keyed to
 * it rather than to whichever listener happened to receive the packet.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerAccessor {
	@Accessor("connection")
	Connection skyzh$connection();
}
