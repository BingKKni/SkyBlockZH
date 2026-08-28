package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.capture.CaptureContext;
import io.github.bingkkni.skyzh.capture.TextCapture;
import java.util.List;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runtime capture, read straight off the wire.
 *
 * <p>Every hook here is {@code @Inject} at {@code HEAD} and returns nothing: the packet is observed
 * and handed on untouched, so this class cannot change what the game does even if it wanted to. That
 * is the whole shape of the feature — capture reads, the render hooks in the rest of this package
 * write, and the two never meet.
 *
 * <p><b>Why the packet handler and not the screen.</b> This is the point in the client where the text
 * is provably Hypixel's. One frame later a tooltip has lines four mods contributed, the sidebar may
 * have been replaced wholesale, and chat holds messages mods injected directly — and none of that is
 * distinguishable from server text by looking at it. An earlier attempt at this feature hooked the
 * drawing and drowned in other mods' strings; hooking here means their text is never seen rather than
 * filtered out afterwards.
 *
 * <p>{@code require = 0} like everything else in this mod: a Minecraft update that renames a handler
 * costs a capture surface, never a boot.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCaptureMixin {
	/**
	 * Chat, before Fabric's events and before any mod has read it.
	 *
	 * <p>{@code overlay()} is the action bar arriving down the chat channel, which is how a good deal
	 * of SkyBlock's action-bar text is actually sent; it is filed as the action bar because that is
	 * where a player sees it and which corpus directory has to answer for it.
	 */
	@Inject(method = "handleSystemChat", at = @At("HEAD"), require = 0)
	private void skyzh$captureSystemChat(ClientboundSystemChatPacket packet, CallbackInfo info) {
		if (packet.overlay()) {
			TextCapture.actionBar(packet.content());
		} else {
			TextCapture.chat(packet.content());
		}
	}

	/**
	 * A container opening. The title is captured and also remembered, because it is the name the items
	 * arriving in the next packet get filed under.
	 */
	@Inject(method = "handleOpenScreen", at = @At("HEAD"), require = 0)
	private void skyzh$captureOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo info) {
		CaptureContext.openScreen(packet.getContainerId(), packet.getTitle());
		TextCapture.containerTitle(packet.getTitle());
	}

	/**
	 * Every slot of a menu at once — what arrives the moment a SkyBlock menu opens.
	 *
	 * <p>The packet's size is handed on before anything is read out of it: the player's own backpack
	 * rides along at the end of every container's contents, and this is where that boundary is
	 * written down. See {@link CaptureContext#menu}.
	 */
	@Inject(method = "handleContainerContent", at = @At("HEAD"), require = 0)
	private void skyzh$captureContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo info) {
		List<ItemStack> items = packet.items();
		CaptureContext.contents(packet.containerId(), items.size());

		for (int slot = 0; slot < items.size(); slot++) {
			TextCapture.item(packet.containerId(), slot, items.get(slot));
		}
	}

	/** One slot changing — a menu updating a counter, a page turning, an item being bought. */
	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"), require = 0)
	private void skyzh$captureContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo info) {
		TextCapture.item(packet.getContainerId(), packet.getSlot(), packet.getItem());
	}

	@Inject(method = "setActionBarText", at = @At("HEAD"), require = 0)
	private void skyzh$captureActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo info) {
		TextCapture.actionBar(packet.text());
	}

	@Inject(method = "setTitleText", at = @At("HEAD"), require = 0)
	private void skyzh$captureTitle(ClientboundSetTitleTextPacket packet, CallbackInfo info) {
		TextCapture.misc(packet.text(), "Title");
	}

	@Inject(method = "setSubtitleText", at = @At("HEAD"), require = 0)
	private void skyzh$captureSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo info) {
		TextCapture.misc(packet.text(), "Subtitle");
	}

	/**
	 * The block above and below the player list. Its rows are not here — they hang off player entries
	 * and are read by the tick watcher — but the header and footer arrive whole, exactly once.
	 */
	@Inject(method = "handleTabListCustomisation", at = @At("HEAD"), require = 0)
	private void skyzh$captureTabListEdges(ClientboundTabListPacket packet, CallbackInfo info) {
		TextCapture.tabList(packet.header(), "页眉");
		TextCapture.tabList(packet.footer(), "页脚");
	}
}
