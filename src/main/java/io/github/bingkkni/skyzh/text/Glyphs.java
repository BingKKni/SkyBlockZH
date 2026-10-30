package io.github.bingkkni.skyzh.text;

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
	 * Private-use codepoint to the symbol it replaced, indexed from {@link #FIRST}; zero where none is
	 * known. An array rather than a map: every character of every line passes through it.
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
	private static final char[] SYMBOLS = new char[0x80];

	/** The first codepoint {@link #SYMBOLS} covers; every identified icon lies within its window. */
	private static final char FIRST = '\uE000';

	static {
		map('\uE001', '⚔');  // Attack Speed
		map('\uE002', '๑');  // Ability Damage
		map('\uE003', '✎');  // Intelligence — Sapphire
		map('\uE004', '⚡');  // Mana Regen — Rift item tooltip variants
		map('\uE006', '❄');  // Cold Resistance
		map('\uE007', '☠');  // Crit Damage — Onyx
		map('\uE008', '❈');  // Defense — Amethyst
		map('\uE00B', '⫽');  // Ferocity
		map('\uE00C', '☂');  // Fishing Speed — Aquamarine
		map('\uE00D', '❁');  // Strength — Jasper
		map('\uE00F', '▚');  // Gemstone Spread
		map('\uE010', '❤');  // Health — Ruby
		map('\uE011', '❣');  // Health Regeneration
		map('\uE012', '♨');  // Heat Resistance
		map('\uE013', '♣');  // Pet Luck
		map('\uE014', '☄');  // Mending
		map('\uE015', '⸕');  // Mining Speed — Amber
		map('\uE016', '▚');  // Mining Spread
		map('\uE01A', '✯');  // Magic Find
		map('\uE01C', '✧');  // Pristine — Topaz
		map('\uE020', 'ф');  // Rift Time — retains the actual server glyph on output
		map('\uE021', 'α');  // Sea Creature Chance
		map('\uE022', '✦');  // Speed
		map('\uE027', '❂');  // True Defense — Opal
		map('\uE028', '♨');  // Vitality
		map('\uE02C', '☣');  // Crit Chance
		map('\uE051', '☘');  // Farming Fortune — Peridot
		map('\uE053', '☘');  // Mining Fortune — Jade
		map('\uE054', '☘');  // Foraging Fortune — Citrine
		map('\uE05B', '☘');  // Hunting Fortune
		map('\uE067', '⏣');  // The area mark in front of a zone name
	}

	private Glyphs() {
	}

	private static void map(char glyph, char symbol) {
		SYMBOLS[glyph - FIRST] = symbol;
	}

	/** The symbol a private-use icon stands for, or zero for every other character. */
	private static char symbol(char c) {
		int index = c - FIRST;
		return index >= 0 && index < SYMBOLS.length ? SYMBOLS[index] : 0;
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
			char symbol = symbol(text.charAt(i));

			if (symbol == 0) {
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
			if (symbol(text.charAt(i)) != 0) {
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
	 * than the wrong glyph. Where the line drew one symbol with two glyphs, the first one wins.
	 */
	public static String restore(String chinese, String sourcePlain) {
		if (chinese.isEmpty()) {
			return chinese;
		}

		// Older corpus snapshots may themselves use a private-use spelling. Fold the
		// target too, so an old symbol source cannot accidentally acquire a newer glyph.
		chinese = canonical(chinese);

		// The glyphs this line drew, the first for each symbol, gathered in one pass over the line
		// rather than one pass per character of the Chinese.
		char[] glyphs = null;
		int count = 0;

		for (int i = 0; i < sourcePlain.length(); i++) {
			char glyph = sourcePlain.charAt(i);
			char symbol = symbol(glyph);

			if (symbol == 0 || drawn(glyphs, count, symbol) != 0) {
				continue;
			}

			if (glyphs == null) {
				glyphs = new char[SYMBOLS.length];
			}

			glyphs[count++] = glyph;
		}

		if (count == 0) {
			return chinese;
		}

		char[] restored = null;

		for (int i = 0; i < chinese.length(); i++) {
			char actual = drawn(glyphs, count, chinese.charAt(i));

			if (actual == 0) {
				continue;
			}

			if (restored == null) {
				restored = chinese.toCharArray();
			}

			restored[i] = actual;
		}

		return restored == null ? chinese : new String(restored);
	}

	/**
	 * Every symbol the table folds an icon onto, as the body of a regex character class.
	 *
	 * <p>For {@link Capture#ICON}. Matching runs on the canonical spelling, so an icon placeholder sees
	 * these symbols and never the private-use glyphs — and several of them are not Unicode symbols at
	 * all: ⸕ (Mining Speed) is punctuation, ๑ (Ability Damage) is a Thai digit. A class written as
	 * {@code \\p{S}} silently refused both, and the template that spells the icon slot out then lost to
	 * the one that allows it to be empty, which swallowed the icon into the stat name.
	 */
	public static String symbolClass() {
		java.util.TreeSet<Character> sorted = new java.util.TreeSet<>();

		for (char symbol : SYMBOLS) {
			if (symbol != 0) {
				sorted.add(symbol);
			}
		}

		StringBuilder symbols = new StringBuilder();

		for (char symbol : sorted) {
			symbols.append(symbol);
		}

		return symbols.toString();
	}

	/** The gathered glyph standing for this symbol, or zero if the line drew it with none. */
	private static char drawn(char[] glyphs, int count, char symbol) {
		for (int i = 0; i < count; i++) {
			if (symbol(glyphs[i]) == symbol) {
				return glyphs[i];
			}
		}

		return 0;
	}
}
