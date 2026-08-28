package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Substitutes the name of the gameplay itself, and repairs the spacing around it.
 *
 * <p>The word is handled here rather than in the corpus because it turns up inside hundreds of
 * lines, including ones nobody has translated yet, and because it is behind a switch — a record
 * cannot hold both "SkyBlock 菜单" and "空岛菜单" at once, so the choice has to be made while
 * drawing.
 *
 * <p>Two things happen at the substitution point that a plain find-and-replace gets wrong.
 *
 * <p><b>Spacing.</b> Chinese typography puts a space between a Latin word and the Chinese around it,
 * so a translator writing "你的 SkyBlock 等级" is writing it correctly. Once the Latin word becomes
 * Chinese those spaces are no longer correct — they are spaces between two Chinese characters, which
 * Chinese does not have — and leaving them in produces the gappy "你的 空岛生存 等级". So a space is
 * dropped when the substitution leaves Chinese on both sides of it, and kept when the other side is
 * a letter or a digit, where it is still right: "+150 空岛经验".
 *
 * <p><b>Abbreviation.</b> Chinese uses the short form of a name inside a compound and the full form
 * standing alone, which is why 空岛生存菜单 reads heavy and 空岛菜单 reads normal. The signal is
 * simply whether a Chinese character follows: if one does, the two have formed a compound and the
 * short name is used. A handful of particles, position words and verb heads are excluded, since those
 * follow a name without compounding with it — "SkyBlock 中" is 空岛生存中, not 空岛中, and "游玩 SkyBlock
 * 获得" is 游玩空岛生存获得, not 游玩空岛获得.
 *
 * <p>Both names and the exclusion list live in {@code _shared/SkyBlock_Name.json}: they are
 * translation decisions, and changing one should not mean changing code.
 */
public final class SkyBlockName {
	private static final String ENGLISH = "SkyBlock";

	/** Used when the corpus has no {@code SkyBlock_Name.json}, so the word is still translated. */
	public static final SkyBlockName DEFAULT = new SkyBlockName("空岛生存", "空岛", "的中里内上下时和与或获");

	private final String full;
	private final String abbreviated;
	private final String stoplist;

	private SkyBlockName(String full, String abbreviated, String stoplist) {
		this.full = full;
		this.abbreviated = abbreviated;
		this.stoplist = stoplist;
	}

	public static SkyBlockName from(JsonObject json) {
		return new SkyBlockName(
			string(json, "full", DEFAULT.full),
			string(json, "short", DEFAULT.abbreviated),
			string(json, "compound_stoplist", DEFAULT.stoplist)
		);
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	/**
	 * Whether every English word on this line is "SkyBlock" — the test that decides whether the word
	 * may be swapped on a line the corpus has not translated.
	 *
	 * <p>The sidebar's shimmering SKYBLOCK passes. "SkyBlock Level 42" does not, and stays English
	 * until a record covers it: turning it into "空岛生存 Level 42" is the kind of half-translation
	 * that makes the whole mod look machine-made. Digits and symbols are not English words, so a
	 * title of "SKYBLOCK" over a number still qualifies.
	 */
	public static boolean isTheOnlyEnglishWord(String plain) {
		int i = 0;

		while (i < plain.length()) {
			if (!isLatinLetter(plain.charAt(i))) {
				i++;
				continue;
			}

			int start = i;

			while (i < plain.length() && isLatinLetter(plain.charAt(i))) {
				i++;
			}

			if (i - start != ENGLISH.length() || !plain.regionMatches(true, start, ENGLISH, 0, ENGLISH.length())) {
				return false;
			}
		}

		return true;
	}

	private static boolean isLatinLetter(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
	}

	/**
	 * @return the line with every occurrence replaced, or {@code null} when the word is not in it —
	 *         which is almost every line, so the caller can skip rebuilding anything
	 */
	public MutableComponent apply(StyledText styled) {
		String plain = styled.plain();
		int at = indexOf(plain, 0);

		if (at < 0) {
			return null;
		}

		MutableComponent result = Component.empty();
		int cursor = 0;

		while (at >= 0) {
			int end = at + ENGLISH.length();

			// One space on either side may belong to the Latin word rather than to the sentence, so
			// the character that decides compounding is the one past it.
			boolean spaceBefore = at > 0 && plain.charAt(at - 1) == ' ';
			boolean spaceAfter = end < plain.length() && plain.charAt(end) == ' ';
			char before = charAt(plain, spaceBefore ? at - 2 : at - 1);
			char after = charAt(plain, spaceAfter ? end + 1 : end);

			boolean compound = isChinese(after) && this.stoplist.indexOf(after) < 0;
			String replacement = compound ? this.abbreviated : this.full;

			// Both forms are Chinese, so a neighbouring Chinese character means the space between
			// them has become a space between two Chinese characters.
			int from = spaceBefore && isChinese(before) ? at - 1 : at;
			int to = spaceAfter && isChinese(after) ? end + 1 : end;

			result.append(styled.slice(cursor, Math.max(cursor, from)));
			appendStyled(result, styled, at, end, replacement);

			cursor = to;
			at = indexOf(plain, end);
		}

		result.append(styled.slice(cursor, plain.length()));

		return result;
	}

	/**
	 * Writes the replacement wearing the colours of the word it replaces, spread across it.
	 *
	 * <p>Taking one colour — the first letter's — would be enough for the ordinary case of a word in
	 * a single colour, but the scoreboard title is not that case: the server re-sends it every tick
	 * with a highlight one letter further along, which is what produces the shimmer over SKYBLOCK.
	 * Collapsing eight letters' worth of colour into one turns a highlight sweeping across the word
	 * into the whole word blinking, so each character of the Chinese instead takes its colour from
	 * the stretch of the English it stands in for.
	 *
	 * <p>Four characters covering eight means each covers two, and a highlight only one letter wide
	 * would fall in the gap half the time — appearing to stutter rather than travel. So within its
	 * stretch a character prefers a colour that is <em>not</em> the word's prevailing one: whatever
	 * the highlight is, it survives the change of alphabet, and a word drawn in one colour is
	 * unaffected because there is nothing else to prefer.
	 */
	private static void appendStyled(MutableComponent result, StyledText styled, int at, int end, String replacement) {
		Style prevailing = prevailingStyle(styled, at, end);
		int sourceLength = end - at;
		int runStart = 0;
		Style runStyle = null;

		for (int i = 0; i <= replacement.length(); i++) {
			Style style = i < replacement.length()
				? styleFor(styled, at + i * sourceLength / replacement.length(),
					at + (i + 1) * sourceLength / replacement.length(), prevailing)
				: null;

			if (runStyle != null && !runStyle.equals(style)) {
				result.append(Component.literal(replacement.substring(runStart, i)).setStyle(runStyle));
				runStart = i;
			}

			if (runStyle == null || !runStyle.equals(style)) {
				runStyle = style;
			}
		}
	}

	private static Style styleFor(StyledText styled, int from, int to, Style prevailing) {
		for (int i = from; i < to; i++) {
			if (!styled.styleAt(i).equals(prevailing)) {
				return styled.styleAt(i);
			}
		}

		return prevailing;
	}

	/** The colour most of the word is drawn in — the background the highlight moves over. */
	private static Style prevailingStyle(StyledText styled, int from, int to) {
		Style best = styled.styleAt(from);
		int bestCount = 0;

		for (int i = from; i < to; i++) {
			int count = 0;

			for (int j = from; j < to; j++) {
				if (styled.styleAt(j).equals(styled.styleAt(i))) {
					count++;
				}
			}

			if (count > bestCount) {
				bestCount = count;
				best = styled.styleAt(i);
			}
		}

		return best;
	}

	private static char charAt(String plain, int index) {
		return index >= 0 && index < plain.length() ? plain.charAt(index) : '\0';
	}

	/** Case-insensitive, because the game shouts the name in scoreboards and titles. */
	private static int indexOf(String plain, int from) {
		for (int i = from; i + ENGLISH.length() <= plain.length(); i++) {
			if (plain.regionMatches(true, i, ENGLISH, 0, ENGLISH.length())) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Han characters and the full-width punctuation that behaves like them for spacing. Deliberately
	 * not "any non-ASCII": the corpus is full of symbols like ❣ and ✦ that sit inside otherwise
	 * English lines, and a space next to one of those is still a real space.
	 */
	private static boolean isChinese(char c) {
		return c >= 0x3400 && c <= 0x9FFF
			|| c >= 0xF900 && c <= 0xFAFF
			|| c >= 0x3000 && c <= 0x303F
			|| c >= 0xFF00 && c <= 0xFF65;
	}
}
