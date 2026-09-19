package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passively recognises Hypixel from what the server sends, without trusting the address used to connect.
 *
 * <p>The hook is on the packet's own {@code handle}, not on the listener's {@code handleCustomPayload}.
 * Fabric API injects at the HEAD of that listener method with {@code cancellable = true} and cancels it
 * for every payload type some mod has registered — and {@code hypixel-mod-api}, which SkyHanni and
 * SkyBlocker both carry, registers {@code hypixel:hello}. A same-priority HEAD injection of ours lands
 * after Fabric's and is never reached for exactly the payload this mod is looking for. The packet's
 * {@code handle} is the one call every path shares: vanilla, Fabric's network-thread pass and Fabric's
 * rescheduled main-thread pass all go through it, and nobody cancels it.
 *
 * <p>The packet is only observed; vanilla and every registered handler still receive it. The two
 * payloads read here are Hypixel's official hello and the standard {@code minecraft:brand}, which is
 * the same signal SkyBlocker checks for {@code Hypixel BungeeCord}. Observing may happen on the
 * network thread; it only writes connection-keyed evidence and never touches world state.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class CustomPayloadPacketMixin {
	@Inject(
		method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
		at = @At("HEAD"),
		require = 0
	)
	private void skyzh$recognizeHypixel(ClientCommonPacketListener listener, CallbackInfo info) {
		if (!(listener instanceof ClientCommonPacketListenerImpl)) {
			return;
		}

		Connection connection = ((ClientCommonPacketListenerAccessor) listener).skyzh$connection();
		CustomPacketPayload payload = ((ClientboundCustomPayloadPacket) (Object) this).payload();

		if (payload instanceof BrandPayload brand) {
			HypixelServer.observeBrand(connection, brand.brand());
		} else if (payload != null) {
			HypixelServer.observePayload(connection, payload.type().id());
		}
	}
}
