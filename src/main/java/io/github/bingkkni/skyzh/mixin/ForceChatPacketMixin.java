package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.ForceChat;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;

/** Observe the packet without replacing its contents or suppressing anyone else's handler. */
@Mixin(value = ClientboundSystemChatPacket.class, priority = Integer.MAX_VALUE)
public abstract class ForceChatPacketMixin {
	@WrapMethod(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V", require = 0)
	private void skyzh$serverSource(ClientGamePacketListener listener, Operation<Void> original) {
		ClientboundSystemChatPacket packet = (ClientboundSystemChatPacket) (Object) this;
		if (packet.overlay()) original.call(listener);
		else ForceChat.server(packet.content(), () -> original.call(listener));
	}
}
