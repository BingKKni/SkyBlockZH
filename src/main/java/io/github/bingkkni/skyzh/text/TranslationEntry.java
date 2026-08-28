package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * One record from {@code original_text/}, compiled into something that can be matched against live
 * text and rendered back out in Chinese.
 *
 * <p>A record is a list of <em>fragments</em>. A line with no {@code segments} array is one
 * fragment; a line that changes colour mid-sentence is one fragment per colour run, which is what
 * the {@code segments} array in the data files describes. Each fragment carries its own Chinese and,
 * where the two languages disagree about order, its own position in the finished sentence — so a
 * sentence whose word order is reversed still ends up with the right words in the right colours. The
 * fragment is the unit that keeps a colour, a placeholder and the words between them tied together.
 *
 * <p>Placeholders ({@code %s}, {@code %1$s}) become capture groups. Whatever they capture is copied
 * from the live text verbatim, with its own colours: those are player names, item names and
 * numbers, none of which are ever translated.
 */
public final class TranslationEntry {
	/**
	 * A run of the source line that shares one colour, together with the Chinese for that run.
	 *
	 * @param group     the capture group covering the whole run
	 * @param argGroups the capture groups of this run's own placeholders, whose contents are the
	 *                  server's values rather than the record's words
	 */
	private record Fragment(List<Piece> pieces, boolean translated, boolean omitted, int group, int[] argGroups) {}

	private sealed interface Piece {}

	private record Literal(String text) implements Piece {}

	private record Arg(int index) implements Piece {}

	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?[sd]|%%");

	private final String id;
	private final String sourceFile;
	private final Pattern pattern;
	private final List<Fragment> fragments;
	/** The same fragments in the order the Chinese reads them. @see #compile */
	private final List<Fragment> rendered;
	private final int[] argGroups;
	/** What each capture group is allowed to hold, so a value cannot swallow a sentence. */
	private final Map<Integer, Capture> captures;
	/** The declared {@code placeholders[].type} of each argument, for {@link TermTable}. */
	private final Map<Integer, String> argTypes;
	private final boolean continuation;
	private final String layout;
	private final int specificity;
	/** The record's English with its fragments joined and its placeholders still in it. */
	private final String template;

	private TranslationEntry(
		String id, String sourceFile, Pattern pattern, List<Fragment> fragments, List<Fragment> rendered,
		int[] argGroups, Map<Integer, Capture> captures, Map<Integer, String> argTypes,
		boolean continuation, String layout, int specificity, String template
	) {
		this.id = id;
		this.sourceFile = sourceFile;
		this.pattern = pattern;
		this.fragments = fragments;
		this.rendered = rendered;
		this.argGroups = argGroups;
		this.captures = captures;
		this.argTypes = argTypes;
		this.continuation = continuation;
		this.layout = layout;
		this.specificity = specificity;
		this.template = template;
	}

	/**
	 * Compiles one record, or returns {@code null} for a record that cannot usefully translate
	 * anything.
	 *
	 * <p>Records are dropped rather than half-applied when nothing has been translated yet — the
	 * corpus is largely unfilled and an empty {@code zh} means "still English", never "erase this
	 * line" — or when the template has no word of its own outside its placeholders.
	 *
	 * <p>A {@code null} target is the corpus's {@code "omit": true} on a segment. English puts the
	 * head of a phrase first and Chinese puts it last, so "Long live the <b>Fallen Star</b>!!!!" is
	 * "<b>陨落之星</b>万岁!!!!" — three colour runs in the English, but the first one has nothing of its
	 * own to say in Chinese; its words moved into the run after it. Without a way to say that, the
	 * choice is to leave the run in English ("Long live the 陨落之星万岁!!!!") or to flatten the line to
	 * one colour and lose the highlight the server put on the name. Both are worse.
	 *
	 * <p>That second rule applies only to records that have a placeholder, and is stricter than it
	 * looks. A template like {@code "%s: %s"} does have literal text, the colon, but it matches
	 * <em>every</em> line on its surface that contains one, so it answers for lines it knows nothing
	 * about and shadows the records that were written for them. Requiring a letter is requiring the
	 * record to be about some particular sentence; punctuation alone is a shape, and shapes belong in
	 * {@link LineShape}. A record with no placeholder cannot shadow anything — it matches its own text
	 * and nothing else — so Dalir's {@code "✆ ..."} is allowed to become {@code "✆ ……"}.
	 *
	 * @param sources  the English of each fragment, in order
	 * @param targets  the Chinese of each fragment, in order. An empty entry means "leave this run in
	 *                 English"; a {@code null} entry means "draw nothing for this run", which is how a
	 *                 line whose Chinese word order differs from the English is expressed — see below
	 * @param order    where each fragment sits in the finished Chinese, or empty for "same order as the
	 *                 English". See {@link #reorder}
	 * @param argTypes the {@code placeholders[].type} of each argument, keyed by its number, deciding
	 *                 both what that placeholder may capture and whether {@link TermTable} applies to it
	 */
	public static TranslationEntry compile(
		String id, String sourceFile, List<String> sources, List<String> targets, boolean continuation,
		String layout, Map<Integer, String> argTypes
	) {
		return compile(id, sourceFile, sources, targets, List.of(), continuation, layout, argTypes);
	}

	/** @see #compile(String, String, List, List, boolean, String, Map) */
	public static TranslationEntry compile(
		String id, String sourceFile, List<String> sources, List<String> targets, List<Integer> order,
		boolean continuation, String layout, Map<Integer, String> argTypes
	) {
		if (sources.isEmpty() || sources.size() != targets.size()) {
			return null;
		}

		// The record's English is folded onto the same spelling the live line will be folded onto, so
		// a wiki-collected "❤ Health" and an NEU-collected "\uE010 Health" are one and the same
		// template — see Glyphs.
		sources = sources.stream().map(Glyphs::canonical).toList();

		boolean anyTranslated = false;
		boolean anyWordOfItsOwn = false;
		boolean anyPlaceholder = false;
		int literals = 0;

		for (int i = 0; i < sources.size(); i++) {
			String target = targets.get(i);

			if (target != null && !target.isEmpty()) {
				anyTranslated = true;
			}

			for (Piece piece : parse(sources.get(i), new int[] { 1 })) {
				if (piece instanceof Literal literal) {
					literals += literal.text().length();
					anyWordOfItsOwn |= literal.text().chars().anyMatch(TranslationEntry::isWordOfItsOwn);
				} else {
					anyPlaceholder = true;
				}
			}
		}

		// A continuation line has no Chinese of its own by design — its sentence was folded into the
		// line above — so "nothing translated" is exactly what it should look like, and it still has
		// to be compiled so the tooltip can recognise and remove it.
		if ((!anyTranslated && !continuation) || (anyPlaceholder && !anyWordOfItsOwn)) {
			return null;
		}

		StringBuilder regex = new StringBuilder("^");
		List<Fragment> fragments = new ArrayList<>();
		int[] sourceArg = { 1 };
		int[] targetArg = { 1 };
		int[] argGroups = new int[64];
		Map<Integer, Capture> captures = new HashMap<>();
		Map<Integer, String> typeByGroup = new HashMap<>();
		int group = 0;

		for (int i = 0; i < sources.size(); i++) {
			group++;
			int fragmentGroup = group;
			List<Integer> fragmentArgs = new ArrayList<>();
			regex.append('(');

			for (Piece piece : parse(sources.get(i), sourceArg)) {
				if (piece instanceof Literal literal) {
					regex.append(anyPlaceholder ? eitherNumber(literal.text()) : Pattern.quote(literal.text()));
				} else if (piece instanceof Arg arg) {
					group++;
					fragmentArgs.add(group);

					if (arg.index() < argGroups.length && argGroups[arg.index()] == 0) {
						argGroups[arg.index()] = group;
					}

					// Lazy, so the literals around it decide where the value ends rather than the
					// value swallowing the rest of the line, and bounded to the kind of value the
					// corpus said sits here — see Capture for what went wrong when it was not.
					String type = argTypes.get(arg.index());
					Capture capture = Capture.of(type);
					captures.put(group, capture);
					typeByGroup.put(group, type);
					regex.append('(').append(capture.regex()).append(')');
				}
			}

			regex.append(')');

			String target = targets.get(i);
			boolean omitted = target == null;
			boolean rendered = !omitted && !target.isEmpty();
			fragments.add(new Fragment(
				rendered ? parse(target, targetArg) : List.of(), rendered, omitted, fragmentGroup,
				fragmentArgs.stream().mapToInt(Integer::intValue).toArray()
			));

			if (!rendered) {
				// A run left in English still consumes its placeholders' numbering, so the fragment
				// after it lines up with the right captures.
				parse(sources.get(i), targetArg);
			}
		}

		regex.append('$');

		return new TranslationEntry(
			id, sourceFile, Pattern.compile(regex.toString(), Pattern.DOTALL), List.copyOf(fragments),
			reorder(fragments, order), argGroups, Map.copyOf(captures), Map.copyOf(typeByGroup),
			continuation, layout, literals, String.join("", sources)
		);
	}

	/**
	 * The fragments in the order the Chinese reads them.
	 *
	 * <p>{@code omit} already lets a run's words move <em>forwards</em> into the run after it, which
	 * covers the common case of English naming a thing before the verb that acts on it. It cannot move
	 * them backwards, and plenty of sentences need exactly that: "You received <b>750 Mithril
	 * Powder</b> from killing a <b>Golden Goblin</b>!" reads in Chinese as "你通过击杀<b>黄金哥布林</b>获得了
	 * <b>750 秘银粉末</b>！" — the two highlighted runs swap places, and each has to keep its own colour
	 * and its own placeholder on the way. Writing that with {@code omit} alone would mean giving one
	 * run both translations and drawing them in one colour, which is the thing {@code segments} exists
	 * to avoid.
	 *
	 * <p>So a segment may say where it lands: {@code "order": 3} is "this run is the fourth thing the
	 * Chinese says". Matching is unaffected — the pattern is still built in the order the server sends
	 * the words — and so is every diagnostic, which walks the source order. Only the drawing changes.
	 *
	 * @param order one position per fragment, already checked to be a permutation by
	 *              {@link TranslationLoader}; empty means the English order, which is almost every record
	 */
	private static List<Fragment> reorder(List<Fragment> fragments, List<Integer> order) {
		if (order.size() != fragments.size()) {
			return List.copyOf(fragments);
		}

		Fragment[] rendered = new Fragment[fragments.size()];

		for (int i = 0; i < fragments.size(); i++) {
			int at = order.get(i);

			if (at < 0 || at >= rendered.length || rendered[at] != null) {
				// Not a permutation. The loader checks and complains; drawing the line in the order it
				// arrived is the harmless answer, and the alternative is dropping the record entirely.
				return List.copyOf(fragments);
			}

			rendered[at] = fragments.get(i);
		}

		return List.of(rendered);
	}

	/**
	 * Words a plural {@code s} may not be taken off, because it was never a plural.
	 *
	 * <p>Words ending in a doubled {@code s} are handled by the rule itself — Boss, Class, Progress,
	 * Success — so what is left here is the short ones that happen to end in one.
	 */
	private static final Set<String> NOT_PLURAL = Set.of(
		"is", "was", "has", "this", "his", "its", "us", "as", "yes", "gas", "plus", "minus", "versus",
		"bonus", "status", "always", "perhaps", "towards", "upwards", "downwards"
	);

	/**
	 * A template's literal text as a regex that accepts the singular of its plural words too.
	 *
	 * <p>Hypixel writes the count and the noun in agreement: {@code Bits: 53,998} on one profile and
	 * {@code Bit: 1} on a new one, {@code +3,598 items} in one second and {@code +1 item} in the next.
	 * Those are two different strings and a record only ever spells one of them, so the line the count
	 * happens to be 1 on stays English — which is the sort of bug nobody reports because it is gone by
	 * the time you look again.
	 *
	 * <p>Chinese has no plural, so both spellings have the same translation and there is nothing for a
	 * translator to decide: the {@code s} is simply made optional. Only on records that have a
	 * placeholder, because a record without one is found by exact text in a hash map and a looser
	 * pattern could not be reached anyway.
	 *
	 * <p>The loosening is deliberately allowed to be broad rather than only next to the number,
	 * because {@code "Bits: %s"} puts the noun on the far side of a colon and any rule about adjacency
	 * would have to guess how far. What keeps it honest is the corpus check: {@code checkTranslations}
	 * feeds every record its own text back and insists that record still wins, so a pair of records
	 * that differ only by one {@code s} fails the build instead of silently answering for each other.
	 */
	private static String eitherNumber(String literal) {
		StringBuilder regex = new StringBuilder(literal.length() + 8);
		int i = 0;

		while (i < literal.length()) {
			int start = i;

			while (i < literal.length() && isAsciiLetter(literal.charAt(i))) {
				i++;
			}

			if (i > start) {
				String word = literal.substring(start, i);

				if (isPlural(word)) {
					regex.append(Pattern.quote(word.substring(0, word.length() - 1))).append("[sS]?");
				} else {
					regex.append(Pattern.quote(word));
				}

				continue;
			}

			// Not a letter, so it cannot be part of a word: punctuation, a space, an icon.
			regex.append(Pattern.quote(literal.substring(i, i + 1)));
			i++;
		}

		return regex.toString();
	}

	private static boolean isPlural(String word) {
		char last = word.charAt(word.length() - 1);

		return word.length() >= 3
			&& (last == 's' || last == 'S')
			&& Character.toLowerCase(word.charAt(word.length() - 2)) != 's'
			&& !NOT_PLURAL.contains(word.toLowerCase(Locale.ROOT));
	}

	private static boolean isAsciiLetter(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
	}

	/**
	 * Whether this character makes a template about some particular line rather than about a shape.
	 *
	 * <p>A letter obviously does. So does a symbol: the sidebar's zone row is {@code "⏣ %s"} and
	 * nothing else on that surface begins with ⏣, which makes the mark every bit as identifying as a
	 * word — and there is no word to be had, since the whole row is the place name. What must not
	 * qualify is ASCII punctuation and spacing, because {@code "%s: %s"} is a shape that fits every
	 * labelled row on a surface and would answer for all of them.
	 */
	/**
	 * Whether this character makes a template about some particular sentence.
	 *
	 * <p>Public because the runtime capture asks the same question before it generalises two lines
	 * into one template: a merge that leaves nothing but placeholders and ASCII punctuation produces
	 * a record this method would then refuse to compile, which is a capture file nobody can use. One
	 * definition, so the two cannot drift apart.
	 */
	public static boolean isWordOfItsOwn(int c) {
		return Character.isLetter(c) || (c > 0x7F && !Character.isDigit(c) && !Character.isWhitespace(c));
	}

	private static List<Piece> parse(String template, int[] nextArg) {
		List<Piece> pieces = new ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		int cursor = 0;

		while (matcher.find()) {
			if (matcher.start() > cursor) {
				pieces.add(new Literal(template.substring(cursor, matcher.start())));
			}

			if (matcher.group().equals("%%")) {
				pieces.add(new Literal("%"));
			} else {
				String explicit = matcher.group(1);
				pieces.add(new Arg(explicit != null ? Integer.parseInt(explicit) : nextArg[0]++));
			}

			cursor = matcher.end();
		}

		if (cursor < template.length()) {
			pieces.add(new Literal(template.substring(cursor)));
		}

		return pieces;
	}

	public String id() {
		return this.id;
	}

	public String sourceFile() {
		return this.sourceFile;
	}

	/**
	 * The record's English, fragments joined, placeholders still in it — the text this record was
	 * filed under. Reported rather than re-derived so a diagnostic can say which record <em>nearly</em>
	 * answered for a line: a corpus entry that differs from the live text by one invisible character
	 * is the single hardest failure in this project to see, and naming both sides is the whole fix.
	 */
	public String template() {
		return this.template;
	}

	/** Whether this line is the tail of a sentence that was folded into the line before it. */
	public boolean continuation() {
		return this.continuation;
	}

	/** {@code "center_chat_banner"} for the handful of announcements the server centres by hand. */
	public String layout() {
		return this.layout;
	}

	/**
	 * How much of this record's template is its own words rather than placeholders, in characters.
	 *
	 * <p>Two records can both match one line — {@code "Remaining: %s"} and
	 * {@code "Remaining: %s goblin(s)"} both fit "Remaining: 3 goblin(s)" — and the one that spelled
	 * more of the line out is the one that was written for it. See {@link TranslationIndex#lookup}.
	 */
	public int specificity() {
		return this.specificity;
	}

	public Matcher matcher(String plain) {
		return this.pattern.matcher(plain);
	}

	/**
	 * The match for this line, or {@code null} when this record does not answer for it.
	 *
	 * <p>Two conditions, not one. The pattern has to fit, and every value it caught has to look like
	 * the kind of value the record said sits there — see {@link Capture}. A record that fits the
	 * characters but not the meaning is worse than no record at all, because it silently replaces a
	 * sentence with the translation of something else.
	 */
	public Matcher match(String plain) {
		Matcher match = this.pattern.matcher(plain);

		return match.matches() && accepts(match) ? match : null;
	}

	/** Whether every placeholder caught something of the kind it was declared to hold. */
	public boolean accepts(Matcher match) {
		for (Map.Entry<Integer, Capture> capture : this.captures.entrySet()) {
			int group = capture.getKey();

			if (match.start(group) >= 0 && !capture.getValue().accepts(match.group(group))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Builds the Chinese line, taking its colours from {@code source} at the position each fragment
	 * matched.
	 *
	 * <p>Reading colour off the live text rather than out of the data file is deliberate. Hypixel
	 * recolours things between updates, and a {@code raw} field recorded in July is a guess by
	 * August; the text on screen right now never is.
	 *
	 * <p>Fragments are written out in the order the <em>Chinese</em> reads them, which is the English
	 * order for all but a handful of records — see {@link #reorder}. Where each fragment matched, and
	 * therefore what colour it takes, does not depend on that order at all.
	 */
	public MutableComponent render(StyledText source, Matcher match, TermTable terms) {
		MutableComponent result = Component.empty();
		Style style = Style.EMPTY;
		Seam seam = new Seam(result);

		for (Fragment fragment : this.rendered) {
			int start = match.start(fragment.group());
			int end = match.end(fragment.group());

			if (start >= 0 && start < source.length()) {
				style = wordStyle(source, match, fragment, start, end);
			}

			if (fragment.omitted()) {
				// This run's words moved into another run when the sentence was reordered. Drawing
				// nothing is the point; the placeholders it held, if any, belong to the run they
				// moved to and are written out there.
				continue;
			}

			if (!fragment.translated()) {
				// Untranslated run: the English stays, with the colours it arrived in.
				seam.append(source.slice(start, end), source.plain().substring(start, end), style);
				continue;
			}

			for (Piece piece : fragment.pieces()) {
				if (piece instanceof Literal literal) {
					// The translator typed "⸕ 挖掘速度"; this line was drawn with the server's own icon
					// font, so the icon goes back the way the server drew it. See Glyphs.
					String text = Glyphs.restore(literal.text(), source.plain());

					seam.append(Component.literal(text).setStyle(style), text, style);
				} else if (piece instanceof Arg arg) {
					int group = arg.index() < this.argGroups.length ? this.argGroups[arg.index()] : 0;

					if (group > 0 && match.start(group) >= 0) {
						append(seam, source, match, group, terms, style);
					}
				}
			}
		}

		return result;
	}

	/**
	 * Writes out one placeholder's value: its Chinese if the term table has some, otherwise the
	 * server's own text with the server's own colours.
	 *
	 * <p>Two things can change a value on its way to the screen, and they are asked in this order: the
	 * term table, which knows that {@code Royal Mines} is 皇家矿区; and the kind of value it is, which
	 * knows that the {@code th} of {@code 27th} is English grammar and does not come across — see
	 * {@link Capture#renderValue}. Everything else is copied through untouched, character for
	 * character, because a name is a name.
	 */
	private void append(Seam seam, StyledText source, Matcher match, int group, TermTable terms, Style style) {
		int start = match.start(group);
		int end = match.end(group);
		String value = source.plain().substring(start, end);
		String type = this.argTypes.get(group);
		String translated = terms.translate(type, value);
		String written = translated != null ? translated : Capture.of(type).renderValue(value);

		if (written.equals(value)) {
			seam.append(source.slice(start, end), value, style);
			return;
		}

		// The value's own colour, not the sentence's: SkyBlock draws the value in a colour of its
		// own far more often than not, and translating the word is no reason to repaint it.
		Style valueStyle = start < source.length() ? source.styleAt(start) : style;

		seam.append(Component.literal(written).setStyle(valueStyle), written, valueStyle);
	}

	/**
	 * The joins between the pieces of a rendered line, which is where Chinese meets English.
	 *
	 * <p>Chinese typography puts a space between a Latin word and the characters around it, and the
	 * pieces on either side of a join come from different places — the record's own words on one
	 * side, whatever the server filled a placeholder with on the other — so neither the translator
	 * nor Hypixel is in a position to have written that space. Without it the screen reads
	 * "Amber宝石收集员" and "Royal Mines钛".
	 *
	 * <p>Only joins are considered, never the inside of a piece. A translator who wrote "2倍秘银粉末"
	 * meant exactly that, and a rule clever enough to space out the digit in the middle of it would
	 * be a rule rewriting somebody's finished translation.
	 *
	 * <p>Digits are deliberately not Latin here. "剩余: 3只哥布林" is how Chinese counts, and putting
	 * air around every number the server sends would space out the whole corpus.
	 */
	private static final class Seam {
		private final MutableComponent target;
		private char previous;

		private Seam(MutableComponent target) {
			this.target = target;
		}

		private void append(Component piece, String plain, Style style) {
			if (plain.isEmpty()) {
				return;
			}

			if (needsSpace(this.previous, plain.charAt(0))) {
				this.target.append(Component.literal(" ").setStyle(style));
			}

			this.target.append(piece);
			this.previous = plain.charAt(plain.length() - 1);
		}

		private static boolean needsSpace(char before, char after) {
			return isHan(before) && isLatin(after) || isLatin(before) && isHan(after);
		}

		private static boolean isLatin(char c) {
			return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
		}

		/**
		 * Han characters only. Full-width punctuation is excluded on purpose: the glyphs for 。and ，
		 * already carry their own trailing space, so a real one after them reads as a double space.
		 */
		private static boolean isHan(char c) {
			return c >= 0x3400 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF;
		}
	}

	/**
	 * The colour this fragment's <em>words</em> are drawn in, which is not always the colour of its
	 * first character.
	 *
	 * <p>SkyBlock colours a value differently from the sentence holding it far more often than not —
	 * {@code §7Fossil Dust: §a1,234}, {@code §bLapis §6CORPSE LOOT!} — and a fragment that begins
	 * with a placeholder begins inside that value. Taking the first character's colour would paint
	 * the Chinese in the number's colour and leave the label's behind, so the search skips whatever
	 * the placeholders captured: those keep their own colours, copied across verbatim, and what is
	 * wanted here is the colour of the record's own text.
	 *
	 * <p>Spaces are skipped for the same reason. A space has no colour anybody can see, and the
	 * server switches colour on the space between two words as readily as on the word itself, so the
	 * space in front of {@code CORPSE LOOT!} is usually still wearing the previous run's colour.
	 */
	private static Style wordStyle(StyledText source, Matcher match, Fragment fragment, int start, int end) {
		for (int i = start; i < end; i++) {
			if (coloursWords(source, fragment, match, i)) {
				return source.styleAt(i);
			}
		}

		return source.styleAt(start);
	}

	/** Whether this position of the source line says anything about the colour of the record's words. */
	private static boolean coloursWords(StyledText source, Fragment fragment, Matcher match, int index) {
		if (source.plain().charAt(index) == ' ') {
			return false;
		}

		for (int group : fragment.argGroups()) {
			if (match.start(group) >= 0 && index >= match.start(group) && index < match.end(group)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * What this record leaves in English after it has rendered, and why — the two ways a finished
	 * line can still come out half Chinese.
	 *
	 * <p>Both are fixable in the data, which is the only reason they are worth telling apart from the
	 * English a record deliberately keeps. An item's name, an NPC's name and a player's name are
	 * <em>meant</em> to survive in English (see {@link TermTable}), and so is the bracketed original
	 * under {@code showOriginal}; neither is reported here.
	 *
	 * @param words  fragments whose {@code zh} is still empty, so half the sentence is English while
	 *               the other half is Chinese. The fix is to fill that {@code segments[].zh}
	 * @param values placeholder values the term table was asked about and had no Chinese for — the
	 *               "Royal Mines钛" case. The fix is one line in {@code _shared/Terms.json}
	 */
	public record Mixed(List<String> words, List<String> values) {
		public boolean any() {
			return !this.words.isEmpty() || !this.values.isEmpty();
		}
	}

	/** @see Mixed */
	public Mixed mixed(StyledText source, Matcher match, TermTable terms) {
		List<String> words = new ArrayList<>();
		List<String> values = new ArrayList<>();
		boolean anyChinese = false;

		for (Fragment fragment : this.fragments) {
			if (fragment.translated()) {
				anyChinese = true;
			}
		}

		if (!anyChinese) {
			// Nothing was translated, so nothing is mixed — the whole line is still English and
			// belongs in the untranslated bucket, not this one.
			return new Mixed(List.of(), List.of());
		}

		for (Fragment fragment : this.fragments) {
			if (fragment.translated() || fragment.omitted()) {
				continue;
			}

			int start = match.start(fragment.group());
			int end = match.end(fragment.group());

			if (start >= 0 && end > start) {
				String english = source.plain().substring(start, end).trim();

				if (hasLetter(english)) {
					words.add(english);
				}
			}
		}

		for (Map.Entry<Integer, String> arg : this.argTypes.entrySet()) {
			int group = arg.getKey();

			if (!terms.applies(arg.getValue()) || match.start(group) < 0) {
				continue;
			}

			String value = source.plain().substring(match.start(group), match.end(group));

			// A value the table already answers for came out Chinese; one it does not is sitting on
			// screen in English next to Chinese, which is exactly what the table exists to remove.
			if (isWords(value) && terms.translate(arg.getValue(), value) == null) {
				values.add(value);
			}
		}

		return new Mixed(List.copyOf(words), List.copyOf(values));
	}

	private static boolean hasLetter(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (Character.isLetter(text.charAt(i))) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether a captured value is words rather than an identifier.
	 *
	 * <p>Only words can be missing from the term table in any useful sense. A profile UUID, a lobby
	 * number and an item id are all English by the same test that says "Royal Mines" is, and reporting
	 * them as translations somebody forgot to write buries the handful that were.
	 */
	private static boolean isWords(String value) {
		if (!hasLetter(value)) {
			return false;
		}

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (!Character.isLetter(c) && c != ' ' && c != '\'' && c != '-') {
				return false;
			}
		}

		return true;
	}

	/**
	 * True when the record's own words change colour partway through a fragment — meaning the data
	 * file recorded one flat line where the game actually draws several colours, and the translation
	 * is about to be painted in the first of them. Reported once per record so the file can gain a
	 * {@code segments} array; harmless enough to keep rendering.
	 *
	 * <p>Placeholders are excluded for the same reason as in {@link #wordStyle}: a value in its own
	 * colour is the normal case, is reproduced exactly, and is not a loss of anything.
	 */
	public boolean losesColour(StyledText source, Matcher match) {
		for (Fragment fragment : this.fragments) {
			if (!fragment.translated()) {
				continue;
			}

			int start = match.start(fragment.group());
			int end = match.end(fragment.group());
			Style words = null;

			for (int i = start; i < end; i++) {
				if (!coloursWords(source, fragment, match, i)) {
					continue;
				}

				Style style = source.styleAt(i);

				if (words == null) {
					words = style;
				} else if (!style.equals(words)) {
					return true;
				}
			}
		}

		return false;
	}
}
