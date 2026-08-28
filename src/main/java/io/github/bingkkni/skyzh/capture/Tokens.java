package io.github.bingkkni.skyzh.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * A line split into the words it is made of and the punctuation between them.
 *
 * <p>This is the unit the "same sentence, different numbers" merge works in. Comparing two lines
 * character by character says they differ somewhere in the middle and nothing useful about where;
 * comparing them word by word says which words are the sentence and which are the values, which is
 * the whole question a record's {@code placeholders} array answers.
 *
 * <p>Punctuation inside a word stays in it — {@code 1,234}, {@code SX-R226}, {@code Zealot's} — and
 * punctuation that ends one does not, so {@code "Ends!"} and {@code "Ends."} are the same word with
 * different gaps around it rather than two different words. SkyBlock's icons are not letters, so they
 * land in the gaps and stay literal, which is what should happen to them: an icon is part of the
 * sentence's shape, never part of a value.
 */
public final class Tokens {
	/**
	 * @param words the sentence's words, in order
	 * @param gaps  what sits before each word and after the last, so {@code gaps.size() == words.size() + 1}
	 * @param at    where each word starts in the original text, for turning a word back into a range
	 */
	public record Split(List<String> words, List<String> gaps, List<int[]> at) {
		public int size() {
			return this.words.size();
		}
	}

	private Tokens() {
	}

	public static Split of(String text) {
		List<String> words = new ArrayList<>();
		List<String> gaps = new ArrayList<>();
		List<int[]> at = new ArrayList<>();
		StringBuilder gap = new StringBuilder();
		int i = 0;

		while (i < text.length()) {
			if (!isWord(text.charAt(i))) {
				gap.append(text.charAt(i));
				i++;
				continue;
			}

			int start = i;

			while (i < text.length()) {
				if (isWord(text.charAt(i))) {
					i++;
				} else if (isInner(text.charAt(i)) && i + 1 < text.length() && isWord(text.charAt(i + 1))) {
					i++;
				} else {
					break;
				}
			}

			gaps.add(gap.toString());
			gap.setLength(0);
			words.add(text.substring(start, i));
			at.add(new int[] { start, i });
		}

		gaps.add(gap.toString());

		return new Split(List.copyOf(words), List.copyOf(gaps), List.copyOf(at));
	}

	private static boolean isWord(char c) {
		return Character.isLetterOrDigit(c) || c == '%' || c == '+' || c == '_' || c == '\'';
	}

	/** Punctuation that only counts as part of a word when there is more word after it. */
	private static boolean isInner(char c) {
		return c == ',' || c == '.' || c == '-' || c == '/' || c == ':';
	}

	/**
	 * Whether a value has a digit anywhere in it.
	 *
	 * <p>Looser than {@link #isNumeric} and used for a different question. That one decides what a
	 * placeholder's declared {@code type} is, and has to be sure; this one decides whether a varying
	 * position counts as one of the sentence's words when the merge weighs sentence against value,
	 * and there {@code 3:30pm} and {@code 65,321/100} are as much "a number the server filled in" as
	 * {@code 1,234} is, while {@code Coins} is not.
	 */
	public static boolean hasDigit(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isDigit(value.charAt(i))) {
				return true;
			}
		}

		return false;
	}

	/** Whether a value is a number as {@code placeholders[].type} means it: {@code 1,234}, {@code +75}, {@code 20%}. */
	public static boolean isNumeric(String value) {
		boolean digit = false;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (Character.isDigit(c)) {
				digit = true;
			} else if (!(c == ',' || c == '.' || c == '+' || c == '-' || c == '%'
				|| (digit && (c == 'k' || c == 'M' || c == 'B' || c == 'm' || c == 'b')))) {
				return false;
			}
		}

		return digit;
	}

	/**
	 * How much of a common prefix may be treated as part of the sentence rather than the value.
	 *
	 * <p>Two rules, both learned from output that looked wrong on the page.
	 *
	 * <p><b>Never across a digit.</b> Two sightings of {@code x23} and {@code x24} share the three
	 * characters {@code x2}, and taking all three would write {@code "x2%s"} and be wrong the first
	 * time a 19 turned up. The {@code x} was never going to vary; the {@code 2} is a digit and varying
	 * is the only thing digits do.
	 *
	 * <p><b>Never mid-word.</b> {@code Force} and {@code Axe} share a final {@code e}, and taking it
	 * writes {@code "%s"e is now available"} with the values {@code Forc} and {@code Ax} — a template
	 * cut through the middle of two words. So the cut has to land on a boundary: a letter may not end
	 * up on both sides of it.
	 */
	public static int literalPrefix(List<String> values) {
		int common = commonPrefix(values);
		String first = values.isEmpty() ? "" : values.getFirst();

		for (int i = 0; i < common; i++) {
			if (Character.isDigit(first.charAt(i))) {
				common = i;
				break;
			}
		}

		if (common > 0 && common < first.length()
			&& Character.isLetter(first.charAt(common - 1)) && Character.isLetter(first.charAt(common))) {
			return 0;
		}

		return common;
	}

	/** The same for the end. @see #literalPrefix */
	public static int literalSuffix(List<String> values, int prefix) {
		int common = commonSuffix(values, prefix);
		String first = values.isEmpty() ? "" : values.getFirst();

		for (int i = 0; i < common; i++) {
			if (Character.isDigit(first.charAt(first.length() - 1 - i))) {
				common = i;
				break;
			}
		}

		int cut = first.length() - common;

		if (common > 0 && cut > prefix
			&& Character.isLetter(first.charAt(cut - 1)) && Character.isLetter(first.charAt(cut))) {
			return 0;
		}

		return common;
	}

	/** How many characters the start of every one of these strings has in common. */
	public static int commonPrefix(List<String> values) {
		if (values.isEmpty()) {
			return 0;
		}

		int shortest = Integer.MAX_VALUE;

		for (String value : values) {
			shortest = Math.min(shortest, value.length());
		}

		int common = 0;

		while (common < shortest) {
			char c = values.getFirst().charAt(common);

			for (String value : values) {
				if (value.charAt(common) != c) {
					return common;
				}
			}

			common++;
		}

		return common;
	}

	/** The same for the end, never overlapping the prefix that was already taken. */
	public static int commonSuffix(List<String> values, int prefix) {
		if (values.isEmpty()) {
			return 0;
		}

		int room = Integer.MAX_VALUE;

		for (String value : values) {
			room = Math.min(room, value.length() - prefix);
		}

		int common = 0;

		while (common < room) {
			char c = values.getFirst().charAt(values.getFirst().length() - 1 - common);

			for (String value : values) {
				if (value.charAt(value.length() - 1 - common) != c) {
					return common;
				}
			}

			common++;
		}

		return common;
	}
}
