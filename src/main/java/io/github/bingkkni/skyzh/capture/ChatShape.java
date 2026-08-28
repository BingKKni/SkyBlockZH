package io.github.bingkkni.skyzh.capture;

import java.util.regex.Pattern;

/**
 * Telling the three kinds of chat line apart: an NPC talking, the game talking, and a person talking.
 *
 * <p>The third is the one that matters. Everything Hypixel says is worth translating and worth
 * capturing; everything a <em>player</em> types is neither — it is somebody's sentence, it is
 * different every time, and a corpus with a few thousand of them in it is a corpus nobody can read.
 * Worse, the mod would be writing strangers' chat messages to a file on disk, which is not something
 * a translation mod should ever do by accident.
 *
 * <p>So the test is on the shape of the line rather than on its words: what stands in front of the
 * first colon. Hypixel puts a rank tag and a name there and nothing else; its own broadcasts put a
 * whole phrase there or no colon at all. {@code [NPC] Bubu:} has the same shape as a player's line
 * and is checked first, which is also why the two cannot be told apart by looking for brackets.
 */
public final class ChatShape {
	/**
	 * The tags Hypixel puts in front of dialogue one of its own characters speaks.
	 *
	 * <p>The second one is why this is a list. {@code [SECURITY] Sloth: } wears the same shape as a
	 * player's line — bracketed tag, one-word name, colon — so {@link #PLAYER} matches it and the
	 * whole warning was classed as somebody's chat and dropped. Nothing reports that: the line is not
	 * captured, so it never appears as missing, and it is not translated, so it sits in the hub in
	 * English indefinitely. Kept in step with {@link io.github.bingkkni.skyzh.text.LineShape}, which
	 * peels the same tags off before looking a line up.
	 */
	private static final String[] SPEAKER_TAGS = { "[NPC] ", "[SECURITY] " };

	/** How far past the tag a speaker's colon may sit — long enough for "Keeper of the Crystal". */
	private static final int LONGEST_SPEAKER = 48;

	/**
	 * A rank tag and a name, and nothing else, in front of a colon.
	 *
	 * <p>The name has no spaces in it, which is what separates {@code "Someone: hi"} from
	 * {@code "Commission Complete: Mithril Miner"}. Minecraft names are one to sixteen word
	 * characters; Hypixel's level bracket and rank tags sit in front of them.
	 *
	 * <p>So does a guild tag, and that is the ornament this pattern used to miss. Hypixel draws the
	 * guild's icon as a bare symbol between the level bracket and the rank — {@code [195] ⸕ [MVP+]
	 * potfire:} — with no brackets around it, so a pattern that only knew about brackets stopped
	 * matching and three strangers' sentences were written into a capture file as though SkyBlock had
	 * said them. A short run of symbols is therefore allowed wherever a bracketed tag is: symbols
	 * non-ASCII only and only after a bracket has already closed, so {@code "» Reward: 3 coins"} is
	 * still the server talking. Non-ASCII rather than "not a letter", because half of those icons are
	 * letters — {@code ᛝ} is a runic ingwaz, {@code Σ} a sigma — while a Minecraft name never is.
	 */
	private static final Pattern PLAYER = Pattern.compile(
		"^(?:\\[[^\\]]{1,24}\\] ?)*(?:(?<=\\] )[^\\x00-\\x7F\\s]{1,3} ?)?"
			+ "(?:\\[[^\\]]{1,24}\\] ?)*[A-Za-z0-9_]{1,16} ?$"
	);

	/** Channel prefixes Hypixel puts in front of a person's message. */
	private static final String[] CHANNELS = { "Guild >", "Party >", "Co-op >", "Officer >", "To ", "From " };

	private ChatShape() {
	}

	/**
	 * Whether this line is machine talk rather than something a player was meant to read.
	 *
	 * <p>Hypixel answers {@code /locraw} with a line of JSON in the chat channel — every mod that
	 * wants to know which island this is asks for it, so it turns up in any session with SkyHanni or
	 * SkyBlocker installed. It is a sentence in the sense that it has words in it and in no other:
	 * captured, it became a record with four placeholders holding server ids.
	 */
	public static boolean isMachineReadable(String line) {
		String trimmed = line.trim();

		return trimmed.startsWith("{\"") && trimmed.endsWith("}");
	}

	/** Where {@code [NPC] Bubu: } ends, or {@code -1} when this line has no speaker tag. */
	public static int npcTagEnd(String line) {
		int start = 0;

		while (start < line.length() && line.charAt(start) == ' ') {
			start++;
		}

		for (String tag : SPEAKER_TAGS) {
			if (!line.startsWith(tag, start)) {
				continue;
			}

			int from = start + tag.length();
			int colon = line.indexOf(": ", from);

			if (colon < 0 || colon - from > LONGEST_SPEAKER) {
				return -1;
			}

			return colon + 2;
		}

		return -1;
	}

	/** The name between the tag and the colon, or an empty string when there is no tag. */
	public static String npcName(String line) {
		int end = npcTagEnd(line);

		if (end < 0) {
			return "";
		}

		for (String tag : SPEAKER_TAGS) {
			int tagAt = line.indexOf(tag);

			if (tagAt >= 0) {
				return line.substring(tagAt + tag.length(), end - 2).trim();
			}
		}

		return "";
	}

	/**
	 * Whether a person wrote this, rather than the server.
	 *
	 * <p>NPC dialogue is excluded before the shape is looked at, because it wears the same one.
	 */
	public static boolean isPlayerChat(String line) {
		if (npcTagEnd(line) >= 0) {
			return false;
		}

		String trimmed = line.stripLeading();

		for (String channel : CHANNELS) {
			if (trimmed.startsWith(channel)) {
				return true;
			}
		}

		int colon = trimmed.indexOf(':');

		if (colon < 0) {
			return false;
		}

		String head = trimmed.substring(0, colon);

		// A channel prefix anywhere in front of the colon: "Guild > Name [Officer]" and the like.
		if (head.indexOf('>') >= 0) {
			return true;
		}

		return PLAYER.matcher(head).matches();
	}
}
