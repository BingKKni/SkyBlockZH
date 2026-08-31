package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.List;

/**
 * How a line is put together on screen, as opposed to what it says.
 *
 * <p>The corpus stores sentences. The game draws lines, and a line is often a sentence with
 * something structural wrapped around it: {@code [NPC] Bubu: } in front of dialogue, {@code : 450}
 * after a tab-list label. Matching a whole line against a corpus of sentences therefore misses
 * exactly the text the mod exists to translate — which is what made NPC dialogue come out English
 * even though every one of its lines had been translated.
 *
 * <p>This class holds the other half of the answer: the shapes a line can have, so the parts that
 * are not the sentence can be peeled off, the sentence looked up on its own, and the peeled parts
 * put back untouched with the colours they arrived in.
 *
 * <p>Shapes live in code rather than in {@code original_text/} on purpose. Where a speaker's name
 * ends is a fact about Hypixel's chat formatting, not a translation decision, and a translator
 * should never have to paste {@code [NPC] Bubu: } in front of a line to make it match. Each shape
 * is scoped to the surface it belongs to, so a colon in a chat message is never mistaken for the
 * label separator that only tab-list rows have.
 */
public final class LineShape {
	/** A half-open range of a line: the part a corpus record might be responsible for. */
	public record Range(int start, int end) {}

	/**
	 * The tags Hypixel puts in front of something one of its own characters says.
	 *
	 * <p>{@code [NPC] } is the one nearly every speaker wears. {@code [SECURITY] } is worn by exactly
	 * one — Sloth, who stands in the hub warning players about account safety — and it has to be
	 * listed because the shape of that line is indistinguishable from a player's: a bracketed tag, a
	 * one-word name, a colon. Left off the list it is read as somebody's chat message, which means
	 * both that it is never translated and that the capture never records it, so nobody finds out it
	 * is missing. See {@link io.github.bingkkni.skyzh.capture.ChatShape}, which needs the same list
	 * for the same reason.
	 */
	private static final String[] SPEAKER_TAGS = { "[NPC] ", "[SECURITY] " };

	/**
	 * How far past the tag the name's colon may sit. The longest name in the corpus is "Keeper of
	 * the Crystal" at 21 characters; the limit keeps a line that merely opens with {@code [NPC] }
	 * and has a colon two sentences later from being cut in the middle of its own text.
	 */
	private static final int LONGEST_SPEAKER = 48;

	private LineShape() {
	}

	/**
	 * The ranges of {@code plain} worth looking up, whole line first and most-peeled last.
	 *
	 * <p>Order is the whole point: a record that deliberately includes the structure around it wins
	 * over one that only covers the sentence inside, so the corpus can always override a shape by
	 * spelling the whole line out.
	 */
	public static List<Range> candidates(Surface surface, String plain) {
		List<Range> ranges = new ArrayList<>(4);
		int length = plain.length();
		add(ranges, 0, length);

		// Hypixel pads lines with spaces to fake centring and to indent. The corpus stores the
		// sentence, not the padding, so a padded line has to be tried without it too.
		int start = skipSpaces(plain, 0, length);
		int end = trimSpaces(plain, start, length);
		add(ranges, start, end);

		// And the same again without the bullet in front of it. A menu draws the entry the cursor is
		// on as "▶ Power Crystal", the SkyBlock menu bullets its perks with "■", a guide arrows its
		// sections with "⚑" — in every case the mark says where the line sits in a list and the text
		// after it is the same text the corpus already has, under the item's or the section's own
		// name. Without this a translated item is English for exactly as long as it is selected.
		add(ranges, skipSpaces(plain, bullet(plain, start, end), end), end);

		if (surface == Surface.CHAT) {
			int speaker = speakerTagEnd(plain, start, end);

			if (speaker > 0) {
				add(ranges, skipSpaces(plain, speaker, end), end);
			}
		}

		if (surface == Surface.TABLIST) {
			// Rows read "<label>: <value>". Both forms of the label are tried because the corpus has
			// both: the tab widget headings were collected with their colon ("Commissions:"), the
			// stat labels without it ("Mining Speed"), since the same word also appears colon-less
			// in the /stats menu.
			int colon = plain.indexOf(':', start);

			if (colon > start && colon < end) {
				add(ranges, start, colon + 1);
				add(ranges, start, colon);
			}
		}

		return ranges;
	}

	/**
	 * Marks Hypixel puts in front of a line to place it in a list, rather than to say anything.
	 *
	 * <p>Listed rather than guessed at from the character's category, because the distinction is not
	 * one Unicode makes: {@code ⸕} and {@code ❤} open a line just as often and are the sentence — an
	 * attribute's icon, which the corpus writes down and the translation keeps. Getting that wrong
	 * loses the icon off every stat line in the game, so the list only ever holds marks somebody has
	 * seen used this way.
	 *
	 * <p>{@code ✔} and {@code ✖} are on the list for the settings menu, where every row is one
	 * toggle and the mark is the state: green tick when it is on, red cross when it is off. The row
	 * says the same thing either way — "Death Messages" — so stepping over the mark lets one record
	 * answer for both states and leaves each mark in the colour that is carrying the meaning.
	 */
	private static final String BULLETS = "▶▸➤➜■◆•⦾⁍⚑✔✖";

	/** Whether this character is one of those marks. Used by {@link Capture} to refuse it as a name. */
	static boolean isBullet(char c) {
		return BULLETS.indexOf(c) >= 0;
	}

	/**
	 * Where the text starts once a leading bullet is stepped over, or {@code start} when there is
	 * none.
	 *
	 * <p>One mark only. A row of them is decoration — the {@code ✦ ✦ ✦} either side of an
	 * announcement banner — and decoration is part of the line, not a mark in front of it.
	 */
	private static int bullet(String plain, int start, int end) {
		if (start >= end || BULLETS.indexOf(plain.charAt(start)) < 0) {
			return start;
		}

		int after = skipSpaces(plain, start + 1, end);

		// A mark with nothing after it is the whole line, and stepping over it would leave an empty
		// range that says nothing about which record answers.
		return after < end && plain.charAt(after) != plain.charAt(start) ? after : start;
	}

	/**
	 * How many spaces in a row mean "these are two things side by side" rather than one sentence.
	 *
	 * <p>SkyBlock builds the action bar out of widgets and puts five spaces between them; a sentence
	 * puts one. Three is the middle of that gap, and no message in the corpus has three in a row.
	 */
	private static final int WIDGET_GAP = 3;

	/**
	 * The separate things a line is made of, when it is several things side by side rather than one
	 * sentence.
	 *
	 * <p>The action bar is the surface this exists for, and it is not a sentence at all: it is
	 * SkyBlock's own HUD, five or six widgets laid out with wide gaps between them —
	 * {@code 2,610/2,235❤     ⏣ The Lift     469/469☘ 400⸕     104/104✎}. Looked up whole it can
	 * never match anything, because every number in it changes as the player walks; and captured
	 * whole it produced a record per combination of those numbers, eight hundred of them from one
	 * session. Split, it is four short widgets that each say one thing, two of which are worth
	 * translating and two of which are numbers with an icon after them.
	 *
	 * <p>Only the ranges that hold something are returned, so the padding at either end and the gaps
	 * between are left for the caller to put back exactly as they arrived. A line with no wide gap in
	 * it comes back as one range: it was one thing all along.
	 */
	public static List<Range> widgets(String plain) {
		List<Range> widgets = new ArrayList<>(6);
		int start = 0;

		while (start < plain.length()) {
			start = skipSpaces(plain, start, plain.length());
			int end = start;
			int cursor = start;

			// A gap only ends the widget when it is wide; a single space inside one — "400⸕ 104✎" —
			// belongs to it.
			while (cursor < plain.length()) {
				int spaces = skipSpaces(plain, cursor, plain.length());

				if (spaces - cursor >= WIDGET_GAP) {
					break;
				}

				if (spaces == cursor) {
					cursor++;
					end = cursor;
				} else {
					cursor = spaces;
				}
			}

			add(widgets, start, end);
			start = cursor;
		}

		return widgets;
	}

	/**
	 * The enchantments a lore line lists, or an empty list when the line is not such a list.
	 *
	 * <p>An item's enchantments are drawn as one lore line holding several of them:
	 * {@code Critical V, Experience III, First Strike IV}. No record can answer for that line and none
	 * ever will — the list is whichever enchantments this particular item happens to carry, and one
	 * session turned up sixty-five different combinations. What <em>can</em> be written down is a
	 * single enchantment, which is what the corpus holds; this cuts the line into the pieces those
	 * records are about, leaving the {@code ", "} between them for the caller to put back untouched.
	 *
	 * <p>Cutting on a comma needs a reason to believe the line is a list, because prose has commas in
	 * it too and half a sentence looked up on its own can match some short record by accident — a
	 * scrambled line, which is worse than an untranslated one. The reason used here is the shape every
	 * piece has: a name followed by a tier in Roman numerals. A sentence fragment does not end in
	 * {@code VII}, so the test costs nothing to prose and passes every real enchantment line.
	 *
	 * <p>Deliberately <em>not</em> "every piece has a record". That was the first rule here and it was
	 * wrong in practice: an item carrying {@code Power V, Punch II, Snipe III} would be left wholly
	 * English because one of the three has no record yet, and mixed lines are the common case while
	 * only some enchantments are translated. A list is a row of independent things, like the tab list's
	 * label and value — translating the ones the corpus knows and leaving the rest is the same
	 * behaviour that surface already has.
	 */
	public static List<Range> enchantments(String plain) {
		List<Range> items = new ArrayList<>(4);
		int start = 0;

		while (true) {
			int comma = plain.indexOf(", ", start);
			int end = comma < 0 ? plain.length() : comma;
			int from = skipSpaces(plain, start, end);
			int to = trimSpaces(plain, from, end);

			if (!isTiered(plain, from, to)) {
				return List.of();
			}

			add(items, from, to);

			if (comma < 0) {
				// One piece is not a list: a line holding a single enchantment is already looked up
				// whole, and answering here as well would only add a second path to the same result.
				return items.size() > 1 ? items : List.of();
			}

			start = comma + 2;
		}
	}

	/** Roman numerals, as SkyBlock writes an enchantment's tier. */
	private static final String NUMERALS = "IVXLCDM";

	/**
	 * Whether this range reads as {@code <name> <tier>} — at least one word, then a Roman numeral.
	 *
	 * <p>The numeral is the whole of the test's strength, so it is checked strictly: every character
	 * of the last word has to be one, which is what keeps {@code "and I saw it"} out while letting
	 * {@code "Bane of Arthropods VII"} through.
	 */
	private static boolean isTiered(String plain, int from, int to) {
		int space = plain.lastIndexOf(' ', to - 1);

		if (space < from || space + 1 >= to) {
			return false;
		}

		for (int i = space + 1; i < to; i++) {
			if (NUMERALS.indexOf(plain.charAt(i)) < 0) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Where the {@code [NPC] Bubu: } tag ends, or {@code -1} when this line does not have one.
	 *
	 * <p>The name is whatever sits between the tag and the first {@code ": "}, which handles the
	 * two-word names the corpus is full of — Banker Broadjaw, Professor Robot, Keeper of the Crystal
	 * — without needing to know any of them. A colon inside the dialogue itself is harmless: the
	 * first one always belongs to the speaker.
	 */
	private static int speakerTagEnd(String plain, int start, int end) {
		for (String tag : SPEAKER_TAGS) {
			if (!plain.startsWith(tag, start)) {
				continue;
			}

			int from = start + tag.length();
			int colon = plain.indexOf(": ", from);

			if (colon < 0 || colon >= end || colon - from > LONGEST_SPEAKER) {
				return -1;
			}

			return colon + 2;
		}

		return -1;
	}

	/** Adds a range unless it is empty or already in the list. */
	private static void add(List<Range> ranges, int start, int end) {
		if (start >= end) {
			return;
		}

		Range range = new Range(start, end);

		if (!ranges.contains(range)) {
			ranges.add(range);
		}
	}

	private static int skipSpaces(String plain, int from, int to) {
		int start = from;

		while (start < to && plain.charAt(start) == ' ') {
			start++;
		}

		return start;
	}

	private static int trimSpaces(String plain, int from, int to) {
		int end = to;

		while (end > from && plain.charAt(end - 1) == ' ') {
			end--;
		}

		return end;
	}
}
