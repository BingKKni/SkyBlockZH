package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.capture.TextCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet dispatch precedes listener events and screen/inventory modifications by other mods. */
@Mixin(value = {ClientboundContainerSetContentPacket.class, ClientboundContainerSetSlotPacket.class,
	ClientboundSetPlayerInventoryPacket.class, ClientboundSetCursorItemPacket.class}, priority = Integer.MAX_VALUE)
public abstract class BookCapturePacketMixin {
	@Inject(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V",
		at = @At("HEAD"), require = 0)
	private void skyzh$book(ClientGamePacketListener listener, CallbackInfo info) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread() || client.getConnection() != listener) return;
		Object packet = this;
		if (packet instanceof ClientboundContainerSetContentPacket content) {
			for (int slot = 0; slot < content.items().size(); slot++) {
				TextCapture.book(content.items().get(slot), content.containerId(), slot);
			}
			TextCapture.book(content.carriedItem(), -1, -1);
		} else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
			TextCapture.book(slot.getItem(), slot.getContainerId(), slot.getSlot());
		} else if (packet instanceof ClientboundSetPlayerInventoryPacket inventory) {
			TextCapture.book(inventory.contents(), 0, inventory.slot());
		} else if (packet instanceof ClientboundSetCursorItemPacket cursor) {
			TextCapture.book(cursor.contents(), -1, -1);
		}
	}
}
