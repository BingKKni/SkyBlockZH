package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.platform.ClientGui;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Everything this mod says to the player in chat, and the one mark that identifies it as ours.
 *
 * <p><b>Why the prefix is a constant here rather than typed into each message.</b> Three things have
 * to agree about it. Minecraft writes every chat line to {@code logs/latest.log}, client-generated
 * ones included, and {@code auditLog} reads that file looking for SkyBlock text the corpus does not
 * cover — so without a mark it can recognise, this mod's own messages come back as untranslated
 * SkyBlock text and are filed as work to do. {@code capture/CaptureAnnouncer} needs the same mark on
 * its batch announcements, and the command help menu draws it as a heading. One constant, three
 * readers, and renaming the mod stays a one-line change.
 *
 * <p>Messages are written as legacy {@code §}-coded strings rather than assembled out of styled
 * components. That is not laziness: it is the form the rest of this project already speaks — the
 * corpus's {@code raw} fields, {@code Probe}'s input, every capture file — and a literal component
 * renders those codes at draw time exactly as the server's own do. It keeps a message readable as one
 * line in the source, which for text somebody will want to reword is worth more than a builder chain.
 */
public final class Feedback {
	/** The mod's name as it appears in chat. Drawn on its own in the help menu's heading. */
	public static final String NAME = "[SkyZH]";

	/** {@link #NAME} and the space that separates it from a message. What {@code auditLog} matches on. */
	public static final String PREFIX = NAME + " ";

	private Feedback() {
	}

	/**
	 * One line of chat, prefixed and marked as ours.
	 *
	 * @param body a {@code §}-coded message, which opens with its own colour — the aqua of the prefix
	 *             would otherwise bleed into it
	 */
	public static void send(String body) {
		raw(line(body));
	}

	/** Pure form used by the command harness to pin complete player-facing feedback. */
	static String line(String body) {
		return "§b" + PREFIX + body;
	}

	/**
	 * One line of chat exactly as given, with no prefix.
	 *
	 * <p>For the help menu, whose heading carries {@link #NAME} in the middle of a rule of {@code =}
	 * signs and whose rows would be harder to read with a tag repeated down the left edge.
	 */
	public static void raw(String line) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null) {
			return;
		}

		// A *client* system message, which is what this is: the line never came from Hypixel and must
		// not be filed as though it had. The same call CaptureAnnouncer makes, for the same reason —
		// and the reason the runtime capture can never see the mod talking to itself, since capture
		// reads packets and this was never one. Which object holds the chat box moved in 26.2, so the
		// call itself lives in platform/ClientGui.
		minecraft.execute(() -> ClientGui.chat(minecraft, Component.literal(line)));
	}
}
