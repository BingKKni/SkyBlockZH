package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * A {@link Component} flattened into plain characters plus the {@link Style} each character is
 * actually drawn with.
 *
 * <p>This is what makes known-issue 3 (colours drifting or vanishing after translation) tractable.
 * SkyBlock builds a single line out of many differently coloured pieces, and the piece boundaries
 * do not line up with anything a translator can see in the text. Rather than trusting the colour
 * codes recorded in {@code original_text/} — which are a snapshot and go stale on every Hypixel
 * update — the translator reads the colours off the live text at the position each source fragment
 * matched, and paints the Chinese with those. The data file decides <em>where</em> the boundaries
 * are; the server decides what colour they are.
 *
 * <p>Both ways of colouring text are folded in here: the {@code Style} carried by nested components
 * and legacy {@code §} codes sitting inside literal strings. SkyBlock uses both, sometimes in the
 * same line, and {@code Font} renders them identically — so anything that reasons about colour has
 * to see them identically too.
 */
public final class StyledText {
	private final String plain;
	private final Style[] styles;
	private String canonical;

	private StyledText(String plain, Style[] styles) {
		this.plain = plain;
		this.styles = styles;
	}

	public static StyledText of(Component component) {
		StringBuilder text = new StringBuilder();
		List<Style> styles = new ArrayList<>();

		component.visit((style, string) -> {
			Style current = style;

			for (int i = 0; i < string.length(); i++) {
				char c = string.charAt(i);

				// A legacy code and its letter are formatting, not content: they must not end up in
				// the plain text a pattern is matched against, or every template would need to
				// carry Hypixel's exact colour codes to match anything.
				if (c == ChatFormatting.PREFIX_CODE) {
					if (i + 1 < string.length()) {
						ChatFormatting formatting = ChatFormatting.getByCode(string.charAt(i + 1));

						if (formatting != null) {
							// §r returns to the style the enclosing component supplied, not to blank —
							// same as vanilla's StringDecomposer.
							current = formatting == ChatFormatting.RESET ? style : current.applyLegacyFormat(formatting);
						}
					}

					// Both characters are dropped whether or not the letter names a real code, because
					// that is what the game draws: StringDecomposer skips the pair unconditionally and
					// only applies a style when it recognises one. Hypixel builds on that. Its sidebar
					// entries have to be unique strings, so it hides a marker like §q inside them — in
					// the middle of a word, at a different place each line — knowing the renderer will
					// eat it. Reading it as content is what turned "Dwarven Mines" into
					// "Dwarven M§qines": no lookup matched, the corpus looked empty, and a whole
					// session's capture went into the unknown-gameplay folder for want of two
					// characters nobody can see.
					i++;
					continue;
				}

				text.append(c);
				styles.add(current);
			}

			return Optional.empty();
		}, Style.EMPTY);

		return new StyledText(text.toString(), styles.toArray(Style[]::new));
	}

	public String plain() {
		return this.plain;
	}

	/**
	 * The same reading applied to a string that is already flat — {@code Component#getString}, a
	 * sidebar row, a menu title.
	 *
	 * <p>Exists so that "the text with its colour codes taken out" means one thing in this mod.
	 * {@code ChatFormatting#stripFormatting} is the obvious answer and it is subtly the wrong one: its
	 * pattern removes only the codes Minecraft has a name for, and the game's renderer removes
	 * {@code §} and whatever follows it either way. Everything Hypixel hides in that gap — see
	 * {@link #of} — survives the first reading and is invisible in the second.
	 */
	public static String plainOf(String text) {
		if (text == null) {
			return "";
		}

		int mark = text.indexOf(ChatFormatting.PREFIX_CODE);

		if (mark < 0) {
			// The overwhelmingly common case, and worth not allocating for: this runs on every line
			// of every container packet.
			return text;
		}

		StringBuilder plain = new StringBuilder(text.length());
		plain.append(text, 0, mark);

		for (int i = mark; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == ChatFormatting.PREFIX_CODE) {
				i++;
				continue;
			}

			plain.append(c);
		}

		return plain.toString();
	}

	/**
	 * The same characters with the server's icon font folded back onto the symbols the corpus is
	 * written with — see {@link Glyphs}. Everything that <em>compares</em> text uses this; everything
	 * that <em>draws</em> it uses {@link #plain()}, and since the two are the same length, an index
	 * means the same thing in both.
	 */
	public String canonical() {
		if (this.canonical == null) {
			this.canonical = Glyphs.canonical(this.plain);
		}

		return this.canonical;
	}

	public int length() {
		return this.plain.length();
	}

	/**
	 * The style of one character, or {@link Style#EMPTY} outside the text. Callers ask for the style
	 * at a fragment's first character; an out-of-range index means the fragment matched nothing, and
	 * a blank style is the honest answer for text that was not there.
	 */
	public Style styleAt(int index) {
		if (index < 0 || index >= this.styles.length) {
			return Style.EMPTY;
		}

		return this.styles[index];
	}

	/**
	 * The same text restricted to one range, without rebuilding anything.
	 *
	 * <p>The core of a line — what is left once a speaker tag or the server's padding has been
	 * peeled off — has to be matched and re-coloured exactly like a whole line would be, and this is
	 * that line's worth of characters and styles without a round trip through {@link Component}.
	 */
	public StyledText sub(int from, int to) {
		if (from <= 0 && to >= this.plain.length()) {
			return this;
		}

		return new StyledText(
			this.plain.substring(from, to), Arrays.copyOfRange(this.styles, from, to)
		);
	}

	/**
	 * Rebuilds a range of the original as a component, keeping every colour change inside it.
	 *
	 * <p>Used for the values captured by placeholders — player names, item names, numbers — which
	 * are copied across untouched. That is both the rule (proper nouns are not translated) and the
	 * safest thing to do with text no data file ever saw.
	 */
	public MutableComponent slice(int from, int to) {
		MutableComponent result = Component.empty();

		if (from >= to) {
			return result;
		}

		int runStart = from;
		Style runStyle = styleAt(from);

		for (int i = from + 1; i <= to; i++) {
			Style style = i < to ? styleAt(i) : null;

			if (style == null || !style.equals(runStyle)) {
				result.append(Component.literal(this.plain.substring(runStart, i)).setStyle(runStyle));
				runStart = i;
				runStyle = style;
			}
		}

		return result;
	}
}
