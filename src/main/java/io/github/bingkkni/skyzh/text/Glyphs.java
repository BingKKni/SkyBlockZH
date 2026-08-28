package io.github.bingkkni.skyzh.text;

import java.util.HashMap;
import java.util.Map;

/**
 * The stat icons, which SkyBlock draws twice: once as a Unicode symbol and once as a glyph of its
 * own.
 *
 * <p>Hypixel used to write its icons as ordinary characters — {@code ❤ Health}, {@code ☘ Mining
 * Fortune}, {@code ⸕} for Amber — and now sends private-use codepoints instead ({@code U+E010},
 * {@code U+E053}, {@code U+E015}), rendered from a font shipped with the server resource pack. Both
 * spellings are alive at once in the sources this corpus is collected from: the wiki records the old
 * symbols, NotEnoughUpdates-REPO carries whatever Hypixel sent the day it was scraped, and the game
 * itself now sends the new ones.
 *
 * <p>The symptom was invisible from the data: a record whose English reads
 * {@code "For example, adding Ruby to armor will increase its ❤ Health"} never matches the line on
 * screen, because that line holds {@code U+E010} where the record holds {@code ❤}. Nothing is
 * logged, nothing is malformed — the sentence simply stays English.
 *
 * <p>So both sides are folded onto one spelling before anything is compared. The mapping runs
 * private-use → symbol rather than the other way round, because the symbol is what a translator can
 * read and type; and because it is one character to one character, every index into the text keeps
 * its meaning, which is what lets the renderer go on reading colours off the live line by position.
 *
 * <p>Drawing then goes back the other way. A record's Chinese is written with the symbol in it, but
 * the line it is replacing was drawn with the server's glyph, so {@link #restore} puts back the
 * exact character that line used. The icon on screen stays the icon SkyBlock drew; only the words
 * around it change.
 */
public final class Glyphs {
	/**
	 * Private-use codepoint to the symbol it replaced.
	 *
	 * <p>Taken from SkyHanni's stat table, which is maintained against the live game, and checked
	 * against the icons seen in a real client log. Gemstone icons are not listed separately: a
	 * gemstone is drawn with the icon of the stat it grants, so Ruby is the Health heart, Sapphire
	 * the Intelligence pen, Amber the Mining Speed mark, and they fall out of the same table.
	 *
	 * <p>A codepoint nobody has identified is deliberately left alone rather than guessed at. It then
	 * appears unchanged on both sides of a comparison — a record collected from NEU-REPO holds the
	 * same character the game sends — so an unmapped icon costs nothing; a wrongly mapped one would
	 * quietly merge two different lines.
	 */
	private static final Map<Character, Character> SYMBOLS = new HashMap<>();

	static {
		SYMBOLS.put('\uE001', '⚔');  // Attack Speed
		SYMBOLS.put('\uE002', '๑');  // Ability Damage
		SYMBOLS.put('\uE003', '✎');  // Intelligence — Sapphire
		SYMBOLS.put('\uE006', '❄');  // Cold Resistance
		SYMBOLS.put('\uE007', '☠');  // Crit Damage — Onyx
		SYMBOLS.put('\uE008', '❈');  // Defense — Amethyst
		SYMBOLS.put('\uE00B', '⫽');  // Ferocity
		SYMBOLS.put('\uE00C', '☂');  // Fishing Speed — Aquamarine
		SYMBOLS.put('\uE00D', '❁');  // Strength — Jasper
		SYMBOLS.put('\uE00F', '▚');  // Gemstone Spread
		SYMBOLS.put('\uE010', '❤');  // Health — Ruby
		SYMBOLS.put('\uE011', '❣');  // Health Regeneration
		SYMBOLS.put('\uE012', '♨');  // Heat Resistance
		SYMBOLS.put('\uE013', '♣');  // Pet Luck
		SYMBOLS.put('\uE014', '☄');  // Mending
		SYMBOLS.put('\uE015', '⸕');  // Mining Speed — Amber
		SYMBOLS.put('\uE016', '▚');  // Mining Spread
		SYMBOLS.put('\uE01A', '✯');  // Magic Find
		SYMBOLS.put('\uE01C', '✧');  // Pristine — Topaz
		SYMBOLS.put('\uE021', 'α');  // Sea Creature Chance
		SYMBOLS.put('\uE022', '✦');  // Speed
		SYMBOLS.put('\uE027', '❂');  // True Defense — Opal
		SYMBOLS.put('\uE028', '♨');  // Vitality
		SYMBOLS.put('\uE02C', '☣');  // Crit Chance
		SYMBOLS.put('\uE051', '☘');  // Farming Fortune — Peridot
		SYMBOLS.put('\uE053', '☘');  // Mining Fortune — Jade
		SYMBOLS.put('\uE054', '☘');  // Foraging Fortune — Citrine
		SYMBOLS.put('\uE05B', '☘');  // Hunting Fortune
		SYMBOLS.put('\uE067', '⏣');  // The area mark in front of a zone name
	}

	private Glyphs() {
	}

	/**
	 * The same text with every private-use icon written as the symbol it stands for. Same length,
	 * character for character, so positions taken in one spelling mean the same thing in the other.
	 *
	 * <p>Returns the argument itself when there is nothing to change, which is nearly every line.
	 */
	public static String canonical(String text) {
		char[] canonical = null;

		for (int i = 0; i < text.length(); i++) {
			Character symbol = SYMBOLS.get(text.charAt(i));

			if (symbol == null) {
				continue;
			}

			if (canonical == null) {
				canonical = text.toCharArray();
			}

			canonical[i] = symbol;
		}

		return canonical == null ? text : new String(canonical);
	}

	/** Whether any character of this text is drawn from the server's icon font. */
	public static boolean hasGlyphs(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (SYMBOLS.containsKey(text.charAt(i))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Rewrites a piece of finished Chinese so its icons are the ones this line was actually drawn
	 * with, given the line as the server sent it.
	 *
	 * <p>Only characters this very line used are put back — an icon the translator typed that the
	 * server did not send stays as typed, since a symbol nobody can read as a glyph is still better
	 * than the wrong glyph.
	 */
	public static String restore(String chinese, String sourcePlain) {
		if (chinese.isEmpty() || !hasGlyphs(sourcePlain)) {
			return chinese;
		}

		char[] restored = null;

		for (int i = 0; i < chinese.length(); i++) {
			char wanted = chinese.charAt(i);
			char actual = glyphFor(wanted, sourcePlain);

			if (actual == wanted) {
				continue;
			}

			if (restored == null) {
				restored = chinese.toCharArray();
			}

			restored[i] = actual;
		}

		return restored == null ? chinese : new String(restored);
	}

	/** The character this line used for a given symbol, or the symbol itself if it used none. */
	private static char glyphFor(char symbol, String sourcePlain) {
		for (int i = 0; i < sourcePlain.length(); i++) {
			char candidate = sourcePlain.charAt(i);
			Character mapped = SYMBOLS.get(candidate);

			if (mapped != null && mapped == symbol) {
				return candidate;
			}
		}

		return symbol;
	}
}
