package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.platform.ServerSignData;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read initial chunk tags and updates before vanilla installs any block entity in the world. */
@Mixin(value = {ClientboundLevelChunkWithLightPacket.class, ClientboundBlockEntityDataPacket.class},
	priority = Integer.MAX_VALUE)
public abstract class SignCapturePacketMixin {
	@Inject(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V",
		at = @At("HEAD"), require = 0)
	private void skyzh$sign(ClientGamePacketListener listener, CallbackInfo info) {
		if (!TextCapture.ready()) return;
		Object packet = this;
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			ServerSignData.chunk(chunk);
		} else if (packet instanceof ClientboundBlockEntityDataPacket update) {
			TextCapture.sign(update.getPos(), update.getType(), update.getTag());
		}
	}
}
