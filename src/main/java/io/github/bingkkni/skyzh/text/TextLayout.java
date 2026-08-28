package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Pixel arithmetic — the answer to known issues 1 and 2, both of which come down to the same thing:
 * English and Chinese of the same meaning are not the same number of pixels wide, so any layout the
 * server worked out for the English is wrong the moment the text changes.
 *
 * <p>Two different tools, because the two cases allow different precision. Where the mod controls
 * the draw position — a container title, a boss bar, the action bar — it recomputes the coordinate
 * and the result is exact to the pixel. Where it does not — a chat line, which the chat renderer
 * always starts at the same left margin — the only lever is padding spaces, and a space is 4 pixels,
 * so centring lands within about 2 pixels of true. That is the same accuracy Hypixel's own centring
 * trick achieves in English, so nothing is lost against the original.
 */
public final class TextLayout {
	private TextLayout() {
	}

	/** Where to start drawing {@code text} so it sits centred in a box {@code width} wide. */
	public static int centeredX(Font font, Component text, int width) {
		return Math.max(0, (width - font.width(text)) / 2);
	}

	/**
	 * The same centring for surfaces that only accept a string: spaces on the left, rounded to the
	 * nearest whole space. Leading spaces the server itself added must be gone before this is called
	 * — they were its centring for the English, and they are not ours.
	 */
	public static MutableComponent centeredWithSpaces(Font font, Component text, int width) {
		int spaceWidth = Math.max(1, font.width(" "));
		int slack = width - font.width(text);

		if (slack <= 0) {
			return text.copy();
		}

		// Rounding to the nearest space can ask for up to half a space more room than there is, and
		// a banner that no longer fits gets wrapped onto a second line by the chat renderer — far
		// uglier than sitting two pixels off centre. So the rounded answer is capped at the number
		// of spaces that still leaves the line inside the box.
		int pad = Math.min(Math.round(slack / 2.0f / spaceWidth), slack / spaceWidth);

		if (pad <= 0) {
			return text.copy();
		}

		return Component.literal(" ".repeat(pad)).append(text);
	}

	/**
	 * Breaks a line to fit {@code maxWidth}, keeping every colour.
	 *
	 * <p>Needed because the corpus stores a sentence Hypixel had already broken across two lore
	 * lines as one record — it has to, or the translator would be handed half a sentence. Once the
	 * whole sentence is translated it has to be broken again, and the English break point is no
	 * guide: Chinese has no spaces, and where it fits is a question about pixels.
	 */
	public static List<Component> wrap(Font font, Component text, int maxWidth) {
		List<Component> lines = new ArrayList<>();
		StyledText styled = StyledText.of(text);
		String plain = styled.plain();

		if (plain.isEmpty() || maxWidth <= 0 || font.width(text) <= maxWidth) {
			lines.add(text);
			return lines;
		}

		// A lore line that arrived indented is one item in a list Hypixel laid out, and the rest of
		// it should stay under the first character rather than falling back to the left margin —
		// which is known issue 2 read literally: lines of one block that do not line up. The indent
		// is repeated on every line the sentence is broken onto, unless it is so deep there would be
		// nothing left to write on, in which case a ragged list beats a one-character column.
		String hanging = plain.substring(0, indentOf(plain));
		int hangingWidth = hanging.isEmpty() ? 0 : font.width(hanging);

		if (hangingWidth * 2 > maxWidth) {
			hanging = "";
			hangingWidth = 0;
		}

		int lineStart = 0;
		boolean first = true;

		while (lineStart < plain.length()) {
			int limit = first ? maxWidth : maxWidth - hangingWidth;
			int width = 0;
			int lastBreak = -1;
			int cursor = lineStart;

			while (cursor < plain.length()) {
				if (cursor > lineStart && canBreakBefore(plain, cursor)) {
					lastBreak = cursor;
				}

				// By code point, so a character outside the basic plane is measured as the one glyph
				// it is and never cut in half — half a surrogate pair renders as a replacement box.
				int codePoint = plain.codePointAt(cursor);
				int charWidth = font.width(new String(Character.toChars(codePoint)))
					+ (styled.styleAt(cursor).isBold() ? 1 : 0);

				if (width + charWidth > limit && cursor > lineStart) {
					break;
				}

				width += charWidth;
				cursor += Character.charCount(codePoint);
			}

			if (cursor >= plain.length()) {
				add(lines, styled, lineStart, plain.length(), first ? "" : hanging);
				break;
			}

			// No break opportunity means one unbroken run wider than the box — a long proper noun,
			// usually. Cutting it mid-word beats letting it push the tooltip off the screen.
			int breakAt = lastBreak > lineStart ? lastBreak : cursor;
			add(lines, styled, lineStart, trimEnd(plain, lineStart, breakAt), first ? "" : hanging);
			lineStart = skipSpaces(plain, breakAt);
			first = false;
		}

		// Breaking a line must never lose it. Nothing in the corpus can reach this — a record has to
		// have a word in it to compile at all — but a wrap that silently returns nothing would erase
		// a line of lore, and that is not a failure worth risking to save a branch.
		if (lines.isEmpty()) {
			lines.add(text);
		}

		return lines;
	}

	/** One wrapped line, under its hanging indent, unless the break left nothing to put there. */
	private static void add(List<Component> lines, StyledText styled, int from, int to, String hanging) {
		if (from >= to) {
			return;
		}

		if (hanging.isEmpty()) {
			lines.add(styled.slice(from, to));
			return;
		}

		lines.add(Component.literal(hanging).append(styled.slice(from, to)));
	}

	private static int indentOf(String plain) {
		int indent = 0;

		while (indent < plain.length() && plain.charAt(indent) == ' ') {
			indent++;
		}

		return indent;
	}

	/**
	 * Where a break is allowed. After a space always; between CJK characters almost always, since
	 * Chinese has no word spacing — except immediately before closing punctuation, which may not
	 * start a line.
	 */
	private static boolean canBreakBefore(String plain, int index) {
		char previous = plain.charAt(index - 1);
		char next = plain.charAt(index);

		if (previous == ' ') {
			return true;
		}

		if (isClosing(next) || isOpening(previous)) {
			return false;
		}

		return isCjk(previous) || isCjk(next);
	}

	private static boolean isCjk(char c) {
		return c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFF60;
	}

	private static boolean isClosing(char c) {
		return "，。！？；：、）】」』》’”%".indexOf(c) >= 0;
	}

	private static boolean isOpening(char c) {
		return "（【「『《‘“".indexOf(c) >= 0;
	}

	private static int trimEnd(String plain, int from, int to) {
		int end = to;

		while (end > from && plain.charAt(end - 1) == ' ') {
			end--;
		}

		return end;
	}

	private static int skipSpaces(String plain, int from) {
		int start = from;

		while (start < plain.length() && plain.charAt(start) == ' ') {
			start++;
		}

		return start;
	}
}
