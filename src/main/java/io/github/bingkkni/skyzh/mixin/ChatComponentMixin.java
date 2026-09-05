package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
		if (!HypixelServer.canTranslate()) {
			return message.splitLines(font, width);
		}

		Component translated = Translator.translateChatBlock(message.content(), font, width);

		// A throwaway message so vanilla's own indent, tag and wrapping rules still apply. The one
		// in allMessages is untouched. The block helper handles each embedded line independently so a
		// centred banner inside one multi-line server message does not accidentally keep English padding.
		return skyzh$rebuild(message, translated).splitLines(font, width);
	}

	/** The same message with different text in it, for splitting and nothing else. */
	private static GuiMessage skyzh$rebuild(GuiMessage message, Component translated) {
		return new GuiMessage(
			message.addedTime(), translated, message.signature(), message.source(), message.tag()
		);
	}
}
