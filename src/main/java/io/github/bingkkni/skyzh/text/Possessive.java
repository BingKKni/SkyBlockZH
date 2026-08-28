package io.github.bingkkni.skyzh.text;

/**
 * Names of the form {@code inkkni's Museum} — a place or a thing that belongs to somebody.
 *
 * <p>These cannot be written down. The owner is a player name, so the string is different for every
 * player alive and no table in {@code original_text/} or {@code areas.json} can ever hold the one a
 * given session will see. What can be written down is the other half: a museum is a museum whoever
 * owns it, which is enough both to file the text under a gameplay and to put the words on screen in
 * Chinese.
 *
 * <p>Splitting rather than matching, so the two callers can do different things with the halves.
 * {@link io.github.bingkkni.skyzh.capture.Areas} throws the owner away — the folder a capture goes
 * in does not depend on whose museum it was — while {@link TermTable} keeps it, because the player
 * reading {@code inkkni 的博物馆} wants to know it is theirs.
 *
 * <p>The apostrophe is matched in both spellings SkyBlock uses. Hypixel writes the typewriter one
 * today, but the right single quotation mark turns up in hand-written strings across the game, and a
 * reading that knows only one of them fails in a way that looks like a missing table entry.
 */
public final class Possessive {
	/** The shortest owner worth splitting on, so a name that merely ends in {@code s} is left alone. */
	private static final int SHORTEST_OWNER = 1;

	private Possessive() {
	}

	/** The two halves of {@code Owner's Thing}. */
	public record Owned(String owner, String thing) {}

	/**
	 * Splits a name at its possessive, or {@code null} when it does not have one.
	 *
	 * <p>The <em>last</em> apostrophe is the split point, because the owner is what varies: a table
	 * entry ends up being looked up for the shortest tail, which is the most generic reading and the
	 * one most likely to be written down. {@code Goblin Queen's Den} splits into a "Goblin Queen"
	 * nobody has listed and a "Den" nobody has listed either, and comes back unchanged — exactly as
	 * it should, since that name is in the tables in full and this is only reached once the direct
	 * lookups have failed.
	 */
	public static Owned split(String name) {
		if (name == null) {
			return null;
		}

		int mark = Math.max(name.lastIndexOf("'s "), name.lastIndexOf("’s "));

		if (mark < SHORTEST_OWNER) {
			return null;
		}

		String thing = name.substring(mark + 3).trim();

		if (thing.isEmpty()) {
			return null;
		}

		return new Owned(name.substring(0, mark), thing);
	}
}
