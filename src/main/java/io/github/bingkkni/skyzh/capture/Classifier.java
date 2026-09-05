package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;

/**
 * Whether a line is worth writing down, and into which pile.
 *
 * <p>Capture is not a log of everything on screen — that is what made it worthless last time. A line
 * the corpus already answers for correctly is not evidence of anything, and a line with no words in
 * it cannot be translated. What is left is three piles, kept apart because each names a different
 * repair:
 *
 * <ul>
 *   <li><b>untranslated</b> — no record answered. Somebody has to write a record.</li>
 *   <li><b>mixed</b> — a record answered and the line still came out half English. Somebody has to
 *       fill in a {@code segments[].zh} or add a line to {@code _shared/Terms.json}. The record
 *       already exists, so this is a much smaller and much more finishable job.</li>
 *   <li><b>colour</b> — a record answered, every word of it is Chinese, and it is drawn in the wrong
 *       colours: the line changes colour partway through and the record recorded it as one flat run,
 *       so the whole translation is painted in whichever colour came first. The fix is a
 *       {@code segments} array split at the real boundaries — and this file already has one, built
 *       from the colours the server actually sent, ready to be pasted in. Which is the whole reason
 *       this pile exists rather than being a warning in the log: the log can say <em>that</em> a line
 *       lost its colours, and only a capture can say <em>where</em> they changed.</li>
 * </ul>
 *
 * <p>English that is <em>meant</em> to survive is in neither pile: an item's name is the Bazaar's
 * search key, a player's name is a player's name, and {@code showOriginal}'s bracketed original is
 * the point of the option. {@link TranslationEntry#mixed} draws that line, not this class.
 */
public final class Classifier {
	public enum Bucket {
		UNTRANSLATED("untranslated"),
		MIXED("mixed"),
		COLOUR("colour");

		private final String directory;

		Bucket(String directory) {
			this.directory = directory;
		}

		public String directory() {
			return this.directory;
		}
	}

	/**
	 * A record that almost answered for this line.
	 *
	 * <p>The most expensive failure this project has is a record that is right in every way a person
	 * can see and wrong in one they cannot: {@code "❤ Health"} against a line drawn with
	 * {@code U+E010}, {@code "ᧅ"} where the server sends {@code "᠅"}, a non-breaking space where a
	 * space was typed. Nothing is logged and nothing is malformed — the sentence just stays English,
	 * and the only way anyone has ever found one of these is by suspecting it.
	 *
	 * <p>So whenever a line goes unanswered, the records for that surface are searched again with
	 * every character that is not a letter or a digit thrown away. A hit means the corpus <em>does</em>
	 * cover this sentence and something invisible is keeping the two apart, and both sides are written
	 * out with their invisible characters spelled as {@code \\uXXXX}.
	 */
	public record NearMiss(String id, String file, String record, String actual) {}

	/**
	 * @param words    fragments of a matched record still showing English, empty for the untranslated pile
	 * @param values   placeholder values {@code Terms.json} has no Chinese for
	 * @param nearMiss a record that covers this sentence but did not match it, when there is one
	 * @param recordId the record that answered, so the mixed and colour piles point at the file to edit
	 */
	public record Verdict(
		Bucket bucket, List<String> words, List<String> values, NearMiss nearMiss,
		String recordId, String recordFile
	) {}

	/**
	 * A tab-list row or a chat line that is nothing but somebody's rank tag and name.
	 *
	 * <p>The ornaments after the name are part of the row and not part of a sentence: Hypixel draws a
	 * guild icon, an AFK marker and a party symbol behind the name, unbracketed and several at a time
	 * — {@code [184] inkkni ᛝ}, {@code [489] CrayolaShokz ᛝ♲}. Without them here, half of one
	 * session's tab-list capture was a list of strangers' usernames: text nobody will ever translate,
	 * in a file nobody should be writing other people's names into. Non-ASCII rather than "not a
	 * letter", because half of those icons are letters — {@code ᛝ} is a runic ingwaz — while a
	 * Minecraft name is only ever {@code [A-Za-z0-9_]}.
	 *
	 * <p>Only asked of the two surfaces a player's row can appear on. A menu item named with one
	 * word — {@code Healer}, {@code Tank}, the five classes in Mort's menu — has exactly this shape,
	 * and for several sessions those five went untranslated <em>and</em> unreported because this test
	 * threw them away before the corpus was ever asked.
	 */
	private static final Pattern PLAYER_ENTRY = Pattern.compile(
		"^(?:\\[[^\\]]{1,24}\\] ?)*[A-Za-z0-9_]{1,16}(?: ?[^\\x00-\\x7F\\s]{1,4})*$"
	);

	/** Below this many letters a skeleton matches by coincidence rather than by being the same sentence. */
	private static final int SHORTEST_SKELETON = 5;

	private static TranslationIndex indexed;
	private static Map<Surface, Map<String, TranslationEntry>> skeletons = Map.of();

	private Classifier() {
	}

	/** The verdict on one line, or {@code null} when there is nothing here worth keeping. */
	public static Verdict of(CaptureSurface surface, StyledText styled) {
		String plain = styled.plain().trim();

		if (!hasEnglishWord(plain) || hasHan(plain)) {
			return null;
		}

		if ((surface.surface() == Surface.CHAT || surface.surface() == Surface.TABLIST)
			&& PLAYER_ENTRY.matcher(plain).matches()) {
			return null;
		}

		Translator.Located located = Translator.locate(styled, surface.surface());

		if (!located.matched()) {
			return new Verdict(Bucket.UNTRANSLATED, List.of(), List.of(), nearMiss(surface.surface(), plain), "", "");
		}

		TranslationEntry.Mixed mixed = located.entry()
			.mixed(located.core(), located.match(), Translator.index().terms());

		if (mixed.any()) {
			return new Verdict(
				Bucket.MIXED, mixed.words(), mixed.values(), null,
				located.entry().id(), located.entry().sourceFile()
			);
		}

		if (surface.surface() == Surface.TABLIST) {
			// A tab row is a label and a value, and a record that answered for the label alone says
			// nothing about the value. "Interest: 47 Hours" matched "Interest:" and was filed as done
			// for several sessions running while the half a player actually reads stayed English.
			String value = englishValue(styled, located.core(), surface.surface());

			if (value != null) {
				return new Verdict(
					Bucket.MIXED, List.of(), List.of(value), null,
					located.entry().id(), located.entry().sourceFile()
				);
			}
		}

		// Fully Chinese and still wrong on screen: the record answered for a line that changes colour
		// partway through and said it was one flat run. See Bucket.COLOUR.
		if (located.entry().losesColour(located.core(), located.match())) {
			return new Verdict(
				Bucket.COLOUR, List.of(), List.of(), null,
				located.entry().id(), located.entry().sourceFile()
			);
		}

		return null;
	}

	/**
	 * Whether there is anything here a translator could translate: two Latin letters in a row.
	 *
	 * <p>Latin specifically, not {@code isLetter}. This mod turns English into Chinese, so a line with
	 * no English word in it has nothing to offer — and {@code isLetter} is true of Han characters, so
	 * the loose test quietly filed every Chinese line the server sends as "untranslated".
	 */
	private static boolean hasEnglishWord(String plain) {
		int run = 0;

		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);

			if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
				if (++run >= 2) {
					return true;
				}
			} else {
				run = 0;
			}
		}

		return false;
	}

	/**
	 * The value half of a tab-list row that nothing translates, or {@code null} when there is no
	 * such half — the record covered the whole row, the value has no English in it, or the term
	 * table or a record answers for it the way {@link Translator#translateRow} will draw it.
	 */
	private static String englishValue(StyledText styled, StyledText core, Surface surface) {
		String plain = styled.canonical();
		String matched = core.canonical();
		int start = plain.indexOf(matched);

		if (start < 0) {
			return null;
		}

		int from = start + matched.length();
		int to = plain.length();

		while (from < to && (plain.charAt(from) == ':' || plain.charAt(from) == ' ')) {
			from++;
		}

		while (to > from && plain.charAt(to - 1) == ' ') {
			to--;
		}

		if (from >= to) {
			return null;
		}

		String value = plain.substring(from, to);

		if (!hasEnglishWord(value) || Translator.index().terms().translate("raw", value) != null) {
			return null;
		}

		return Translator.locate(styled.sub(from, to), surface).matched() ? null : value;
	}

	/**
	 * Whether the server already said this in Chinese.
	 *
	 * <p>Hypixel translates some of its own text — lobby transfers, join announcements, the level-up
	 * banner — for a player whose Hypixel language is set to Chinese, and those arrive here looking
	 * exactly like anything else. They are not this mod's to translate and not worth a record, and a
	 * line that mixes a Han character in is the server's mixture, not one this mod introduced.
	 */
	private static boolean hasHan(String plain) {
		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);

			if (c >= 0x3400 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF) {
				return true;
			}
		}

		return false;
	}

	/** @see NearMiss */
	private static NearMiss nearMiss(Surface surface, String plain) {
		String skeleton = skeleton(plain);

		if (skeleton.length() < SHORTEST_SKELETON) {
			return null;
		}

		TranslationEntry entry = skeletons(surface).get(skeleton);

		if (entry == null) {
			return null;
		}

		return new NearMiss(
			entry.id(), entry.sourceFile(), LegacyText.escape(entry.template()), LegacyText.escape(plain)
		);
	}

	/**
	 * Letters and digits only, folded to lower case.
	 *
	 * <p>Everything a record and a line can disagree about while looking identical is punctuation,
	 * spacing or an icon, and all three are gone by the time two skeletons are compared. What is left
	 * is the sentence, which is the thing that has to be the same for this to be a near miss rather
	 * than a coincidence.
	 */
	private static String skeleton(String text) {
		StringBuilder skeleton = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				skeleton.append(Character.toLowerCase(c));
			}
		}

		return skeleton.toString();
	}

	/**
	 * Records indexed by skeleton, rebuilt whenever the corpus is reloaded.
	 *
	 * <p>Only records with no placeholder in them. A template's skeleton has its {@code %1$s} folded
	 * into the sentence and would collide with anything, and "this record nearly matched" is a claim
	 * worth making only when it can be made precisely.
	 */
	private static synchronized Map<String, TranslationEntry> skeletons(Surface surface) {
		TranslationIndex index = Translator.index();

		if (index != indexed) {
			Map<Surface, Map<String, TranslationEntry>> built = new HashMap<>();

			for (Surface each : Surface.values()) {
				Map<String, TranslationEntry> bySkeleton = new HashMap<>();

				for (TranslationEntry entry : index.entries(each)) {
					if (entry.template().indexOf('%') < 0) {
						bySkeleton.putIfAbsent(skeleton(entry.template()), entry);
					}
				}

				built.put(each, Map.copyOf(bySkeleton));
			}

			skeletons = Map.copyOf(built);
			indexed = index;
		}

		return skeletons.getOrDefault(surface, Map.of());
	}

	/**
	 * A file-system-safe version of a name taken from the game.
	 *
	 * <p>Colour codes go first and whole: a {@code §a} reduced to its characters would put a stray
	 * {@code a} at the front of every file named after a green menu title. Read the same way every
	 * other piece of text in the mod is — {@code §} and whatever follows it, named code or not — so a
	 * menu title carrying one of Hypixel's invisible markers does not open a second file beside the
	 * one it belongs in.
	 */
	public static String fileName(String name) {
		String cleaned = StyledText.plainOf(name).trim();
		StringBuilder safe = new StringBuilder(cleaned.length());
		boolean underscore = false;

		for (int i = 0; i < cleaned.length(); i++) {
			char c = cleaned.charAt(i);

			if (Character.isLetterOrDigit(c) || c == '-') {
				safe.append(c);
				underscore = false;
			} else if (!underscore && !safe.isEmpty()) {
				safe.append('_');
				underscore = true;
			}
		}

		while (!safe.isEmpty() && safe.charAt(safe.length() - 1) == '_') {
			safe.setLength(safe.length() - 1);
		}

		return safe.isEmpty() ? "_Unknown_Name" : safe.substring(0, Math.min(safe.length(), 64));
	}

	/** A stable, readable {@code id} for a record, from the words of its own English. */
	public static String id(String text) {
		List<String> words = new ArrayList<>();

		for (String word : Tokens.of(text).words()) {
			String lower = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

			if (!lower.isEmpty()) {
				words.add(lower);
			}

			if (words.size() == 8) {
				break;
			}
		}

		return words.isEmpty() ? "line" : String.join("_", words);
	}
}
