package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.text.ChatDisplayCache;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NPC dialogue and system messages, translated on their way to the screen and nowhere else.
 *
 * <p>The hook is the line-splitting step, which is the last thing that happens to a message before
 * it becomes pixels, and it is deliberately not one step earlier. {@code allMessages} — the chat
 * history, what the clipboard copies, what any mod inspecting the chat log reads — keeps the
 * original English {@link GuiMessage}; only {@code trimmedMessages}, the wrapped lines the renderer
 * walks, are built from the translation. Every mod that parses chat does so in the packet handler
 * or through Fabric's chat events, both of which have long since run by the time this is reached,
 * so SkyHanni and SkyBlocker still see precisely what Hypixel sent.
 *
 * <p>It also means the translation survives a window resize for free: vanilla rebuilds the wrapped
 * lines by calling this same method again for every stored message.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Unique private final ChatDisplayCache skyzh$displayCache = new ChatDisplayCache();
	@Shadow @Final private List<GuiMessage.Line> trimmedMessages;
	@Shadow private int chatScrollbarPos;
	@Shadow private boolean newMessageSinceScroll;
	@Shadow private void refreshTrimmedMessages() { throw new AssertionError("Mixin shadow"); }
	@Shadow public abstract int getLinesPerPage();

	// Observe the effective policy at the last point before drawing: includes master switch changes
	// in an open settings screen, the session toggle, and server changes, without clearing history.
	@Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
	private void skyzh$refreshPolicy(CallbackInfo info) {
		if (!this.skyzh$displayCache.needsRefresh(HypixelServer.canTranslate(),
			SkyZHConfig.get().translateSkyBlockName, Translator.index())) return;
		int scroll = this.chatScrollbarPos;
		boolean unread = this.newMessageSinceScroll;
		refreshTrimmedMessages();
		this.chatScrollbarPos = Math.min(scroll, Math.max(0, this.trimmedMessages.size() - getLinesPerPage()));
		this.newMessageSinceScroll = unread;
	}

	@Inject(method = {"rescaleChat", "clearMessages"}, at = @At("HEAD"), require = 0)
	private void skyzh$invalidateLayout(CallbackInfo info) {
		this.skyzh$displayCache.clear();
		// Vanilla also rescales on font/resource changes. Other cached layouts must follow suit;
		// the original-text toggle intentionally never invokes this expensive invalidation path.
		SkyZHConfig.bumpGeneration();
	}

	/**
	 * {@code require = 0}, here and on every other hook in this mod. Another mod redirecting the
	 * same instruction would make this one impossible to apply, and refusing to boot somebody's
	 * modpack over a translation is the wrong trade — the game should start and the text should stay
	 * English. The startup log line reporting how many records loaded is how a user tells the
	 * difference between "not translated yet" and "not working".
	 */
	@Redirect(
		method = "addMessageToDisplayQueue",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;"
		),
		require = 0
	)
	private List<FormattedCharSequence> skyzh$translateBeforeSplit(GuiMessage message, Font font, int width) {
		boolean translate = HypixelServer.canTranslate();
		return this.skyzh$displayCache.get(message, font, width, translate,
			SkyZHConfig.get().translateSkyBlockName, Translator.index(), () -> {
				if (!translate) return message.splitLines(font, width);
				Component translated = Translator.translateChatBlock(message.content(), font, width);
				// Only the throwaway message is translated; vanilla retains indent, tags and wrapping.
				return skyzh$rebuild(message, translated).splitLines(font, width);
			});
	}

	/** The same message with different text in it, for splitting and nothing else. */
	private static GuiMessage skyzh$rebuild(GuiMessage message, Component translated) {
		return new GuiMessage(
			message.addedTime(), translated, message.signature(), message.source(), message.tag()
		);
	}
}
