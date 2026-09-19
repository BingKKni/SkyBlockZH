package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passively recognises Hypixel's official hello packet without trusting the address used to connect.
 *
 * <p>This is intentionally below Fabric's optional networking API. The packet identifier is visible
 * here whether another mod registered a typed codec for it or vanilla decoded it as an unknown
 * payload, so SkyZH does not need Fabric API or {@code hypixel-mod-api} merely to recognise the
 * server. The packet is only observed; vanilla and every registered handler still receive it.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	@Shadow
	protected Connection connection;

	@Inject(
		method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
		at = @At("HEAD"),
		require = 0
	)
	private void skyzh$recognizeHypixel(ClientboundCustomPayloadPacket packet, CallbackInfo info) {
		HypixelServer.observePayload(this.connection, packet.payload().type().id());
	}
}
