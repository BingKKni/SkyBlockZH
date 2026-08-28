package io.github.bingkkni.skyzh.text;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a placeholder is allowed to swallow.
 *
 * <p>A record's {@code %s} used to compile to {@code (.*?)}, which reads as "the value goes here"
 * but means "anything at all goes here". That is wrong in a way that only shows up in game, and it
 * showed up like this: the commission board draws
 *
 * <pre>
 *   Slay 1 Boss Corleone in the Mithril
 *   Deposits.
 * </pre>
 *
 * because Hypixel wrapped one sentence over two lore lines. The record {@code "%s Mithril"} — the
 * commission <em>name</em> "Royal Mines Mithril" — fits the first of those lines perfectly, with
 * {@code %s} capturing "Slay 1 Boss Corleone in the". So a sentence about killing a boss was drawn
 * as "Slay 1 Boss Corleone in the秘银" with an English "Deposits." underneath it.
 *
 * <p>The fix is to say what kind of thing each placeholder holds, which the corpus already records
 * in {@code placeholders[].type} for the translator's benefit. A location name is one to five
 * capitalised words; it is not a clause with a verb in it. Nothing else about the engine had to
 * change — the template was never wrong, it was just being asked a question it could not refuse.
 *
 * <p>Both halves matter. The regex keeps the match cheap and stops a capture running past the end of
 * a value; {@link #accepts} then judges the shape of what was caught, which is the part a regex
 * expresses badly. A record whose capture is rejected simply does not match that line, and the line
 * is left in English — the same outcome as never having written the record, which is the right way
 * for a guess to fail.
 */
public enum Capture {
	/** A quantity: {@code 1,234}, {@code +75}, {@code 20%}, {@code 3k}. */
	NUMBER("[+\\-]?[0-9][0-9,.]*[a-zA-Z%]{0,2}"),

	/**
	 * A name: an item, a place, an NPC, a mob, a rarity. One to five words, each of which looks like
	 * part of a name rather than part of a sentence.
	 */
	NAME("[^\\n]{1,48}?"),

	/** A player's name, which Minecraft limits to sixteen word characters. */
	PLAYER("[A-Za-z0-9_]{1,16}"),

	/**
	 * A tier written as a Roman numeral: an enchantment level, a minion tier, a cookie buff's rank.
	 *
	 * <p>Worth its own kind because of where it sits. SkyBlock writes {@code 19x Hard Stone XII}, and
	 * a template of two {@code raw} placeholders side by side splits that at the first space it can —
	 * "Hard" and "Stone XII" — because a lazy capture takes the shortest thing that lets the rest of
	 * the pattern match. The result is not an untranslated line, which would be fine, but a scrambled
	 * one. Saying that the second half is a numeral is what makes the first half stop in the right
	 * place.
	 */
	TIER("[IVXLCDM]{1,8}"),

	/**
	 * An English ordinal: {@code 27th}, {@code 1st}, {@code 88th}. The count of something that has
	 * happened before — the 27th Spooky Festival, the 88th election, the 20th of Early Spring.
	 *
	 * <p>Its own kind because the two letters on the end are English grammar rather than part of the
	 * value, and Chinese has nothing to do with them: 第 27 届 carries the ordinal in the words around
	 * the number, not in the number itself. So {@link #renderValue} hands back the digits alone and a
	 * record writes one template rather than the four the suffixes would otherwise force —
	 * {@code "%s Spooky Festival"} instead of one record each for {@code st}, {@code nd}, {@code rd}
	 * and {@code th}, all four with the same Chinese.
	 */
	ORDINAL("[0-9]{1,4}(?:st|nd|rd|th|ST|ND|RD|TH)"),

	/** A compact duration used in the tab list: {@code 35d+}, {@code 2h 14m}, {@code 30s}. */
	DURATION("(?:[0-9][0-9,.]*[dDhHmMsS][+]?(?: ?[0-9][0-9,.]*[dDhHmMsS][+]?)*|[0-9][0-9,.]*[+]?)"),

	/**
	 * Anything the corpus has not pinned down — {@code type: raw}, or no {@code placeholders} entry
	 * at all. Still bounded: whatever the value is, it is a value and not a sentence.
	 */
	PHRASE("[^\\n]{1,64}?");

	/** Lowercase words that belong inside a name rather than marking the start of a sentence. */
	private static final Set<String> CONNECTIVES = Set.of("of", "the", "and", "in", "on", "at", "to", "for", "a");

	private final String regex;

	Capture(String regex) {
		this.regex = regex;
	}

	/**
	 * The kind a {@code placeholders[].type} names. An unknown or absent type is {@link #PHRASE},
	 * which is the loosest kind — a corpus that has not said what a value is should not have the
	 * engine inventing a stricter answer than the translator gave.
	 */
	public static Capture of(String type) {
		return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
			case "number", "percentage", "coins" -> NUMBER;
			case "time", "duration" -> DURATION;
			// category_name is the name of a menu section or a feature — "Bags", "Other Crystals",
			// "Recipe Book". Shaped like a name, and it has to be said so: templates that hold one are
			// a single word plus a placeholder ("Your %s", "%s Settings", "%s Pet"), which under the
			// looser PHRASE rule matched any lore line that happened to start or end that way and drew
			// the rest of the sentence in its place.
			case "item_name", "npc_name", "location_name", "mob_name", "rarity", "category_name" -> NAME;
			case "player_name" -> PLAYER;
			case "tier" -> TIER;
			case "ordinal" -> ORDINAL;
			default -> PHRASE;
		};
	}

	/** The body of this placeholder's capture group, lazy so surrounding literals decide where it ends. */
	public String regex() {
		return "(?:" + this.regex + ")??";
	}

	/** Whether what was captured looks like the kind of value this placeholder was said to hold. */
	public boolean accepts(String value) {
		if (value.isEmpty()) {
			// Hypixel does draw empty values — an unset drill part, a zero-length prefix — and a
			// record that spelled the rest of the line out is still the record for that line.
			return true;
		}

		return switch (this) {
			// The regex is the whole of the rule for these: a numeral is a numeral, and a player's
			// name is whatever sixteen word characters somebody chose.
			case NUMBER, PLAYER, TIER, ORDINAL, DURATION -> true;
			case NAME -> isName(value);
			case PHRASE -> isValue(value);
		};
	}

	/**
	 * The value as the Chinese should show it, which for every kind but one is the value the server
	 * sent, verbatim and in its own colours.
	 *
	 * <p>An ordinal loses its suffix: the server writes {@code 27th} and the record around it writes
	 * 第 %s 届, so the {@code th} would come out as two stray English letters in the middle of a
	 * Chinese phrase. Nothing else is touched here — a number, a name and a player's name are all
	 * copied across exactly as they arrived, which is the whole point of their being placeholders.
	 */
	public String renderValue(String value) {
		if (this == ORDINAL && value.length() >= 3) {
			return value.substring(0, value.length() - 2);
		}

		if (this != DURATION) {
			return value;
		}

		Matcher unit = DURATION_UNIT.matcher(value);
		StringBuilder translated = new StringBuilder(value.length() + 4);
		int cursor = 0;

		while (unit.find()) {
			String between = value.substring(cursor, unit.start());

			// English separates its units with a space — "2h 14m" — because "2h14m" would run two
			// Latin tokens together. Chinese units are characters and 2小时 14分 reads as two separate
			// readings of the clock rather than one duration, so the gap between two translated units
			// closes up. Anything else between them is not a gap and is kept: a value that is not a
			// duration at all comes back exactly as it arrived.
			if (!(cursor > 0 && !between.isEmpty() && between.isBlank())) {
				translated.append(between);
			}

			translated.append(unit.group(1))
				.append(switch (Character.toLowerCase(unit.group(2).charAt(0))) {
					case 'd' -> "天";
					case 'h' -> "小时";
					case 'm' -> "分";
					default -> "秒";
				})
				.append(unit.group(3).isEmpty() ? "" : "以上");

			cursor = unit.end();
		}

		return translated.append(value, cursor, value.length()).toString();
	}

	/**
	 * One {@code 35d+} in a duration: the count, the unit letter, and the {@code +} Hypixel uses to
	 * mean "at least this long". Applied one unit at a time so {@code 1d 16h 21m 14s} comes out whole
	 * and anything that is not a unit — a bare number, a word — is left exactly as it arrived.
	 */
	private static final Pattern DURATION_UNIT = Pattern.compile("([0-9][0-9,.]*)([dDhHmMsS])(\\+?)");

	/**
	 * Whether this reads as a name: at most five words, none of them sentence punctuation, and with
	 * a capitalised word at each end.
	 *
	 * <p>The ends are what does the work. Lowercase words do appear inside names — Keeper of the
	 * Crystal, Mines of Divan — but a name does not <em>begin</em> or <em>end</em> on one, whereas a
	 * clause chopped off mid-sentence very often does ("...in the").
	 */
	private static boolean isName(String value) {
		Possessive.Owned owned = Possessive.split(value);

		if (owned != null && isPlayerName(owned.owner())) {
			// "inkkni's Museum". The owner is a player name and players pick their own capitalisation,
			// so judging the whole string by the rules below throws away every place that belongs to
			// somebody — which on the sidebar is the museum and the private island, two of the
			// handful of rows that are on screen the entire time a player is there.
			return isName(owned.thing());
		}

		String[] words = value.split(" ");

		if (words.length > 5) {
			return false;
		}

		for (int i = 0; i < words.length; i++) {
			String word = words[i];

			if (word.isEmpty() || containsSentencePunctuation(word)) {
				return false;
			}

			boolean edge = i == 0 || i == words.length - 1;

			if (!startsName(word) && (edge || !CONNECTIVES.contains(word))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether this reads as a value of some sort rather than as prose.
	 *
	 * <p>Padding is not part of a value. Hypixel centres its announcement banners by putting spaces
	 * in front of them, and a template like {@code "%s STARTED!"} will happily swallow those spaces
	 * into the capture if allowed to — which looks harmless and is not: the value stops matching the
	 * term table ({@code "  GONE WITH THE WIND"} is not a term), and the line is no longer recognised
	 * as one the server centred, so the mod cannot re-centre it for the Chinese either. Refusing the
	 * space makes the whole-line attempt fail and the trimmed one — where the padding is peeled off
	 * and handed back separately — succeed.
	 */
	private static boolean isValue(String value) {
		return !value.startsWith(" ") && !value.endsWith(" ")
			&& value.split(" ").length <= 8 && !containsSentencePunctuation(value);
	}

	/** Whether this could be somebody's name: Minecraft's own sixteen word characters. */
	private static boolean isPlayerName(String value) {
		if (value.isEmpty() || value.length() > 16) {
			return false;
		}

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (!Character.isLetterOrDigit(c) && c != '_' || c > 0x7F) {
				return false;
			}
		}

		return true;
	}

	private static boolean startsName(String word) {
		char first = word.charAt(0);

		return Character.isUpperCase(first) || Character.isDigit(first) || first > 0x7F;
	}

	/**
	 * Punctuation that only turns up between sentences. Commas are not on the list: they separate
	 * the digit groups of every large number SkyBlock prints.
	 *
	 * <p>Neither is a full stop with digits on both sides, for the same reason — it is a decimal
	 * point. The action bar's skill readout says {@code +10.9 Combat (20.34%)} once a player is at
	 * max level and {@code +112 Combat (138,556,517/0)} before that; one record covers both, and
	 * reading the decimal point as the end of a sentence made it refuse the first of them and leave
	 * a line of the HUD in English for exactly the players who had played longest.
	 */
	private static boolean containsSentencePunctuation(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (".!?;".indexOf(c) < 0) {
				continue;
			}

			boolean decimalPoint = c == '.'
				&& i > 0 && Character.isDigit(value.charAt(i - 1))
				&& i + 1 < value.length() && Character.isDigit(value.charAt(i + 1));

			if (!decimalPoint) {
				return true;
			}
		}

		return false;
	}
}
