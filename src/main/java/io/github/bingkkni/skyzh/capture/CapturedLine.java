package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One line the corpus has no answer for, and every later sighting of the same sentence folded into
 * it.
 *
 * <p>This is where the objection that stopped the last attempt at runtime capture gets its answer.
 * The objection was that a capture cannot tell a fixed word from a value the server fills in —
 * {@code "You got 3 coins"}, is the 3 the sentence or the number? — and it is right that one sighting
 * cannot. Several can: a word that has been seen to change is a value, and a word that has never
 * changed is not evidence of anything and stays a word. So a sentence is generalised only where the
 * game has actually been observed to vary it, and every value ever seen in that position is written
 * out beside the record, so the decision can be checked rather than trusted.
 *
 * <p>Two rules keep the guessing honest. Merging only happens between lines with the same words in
 * the same places and identical punctuation between them, so nothing is generalised across two
 * genuinely different sentences. And a placeholder's declared {@code type} is only ever
 * {@code number} — when every value seen there is one — or {@code raw}, the loosest kind the engine
 * has: guessing {@code item_name} would tighten what the placeholder is allowed to capture on
 * evidence this class does not have.
 */
public final class CapturedLine {
	/** Beyond this many varying words, two lines are different sentences rather than one template. */
	private static final int MAX_SLOTS = 4;

	/** Enough examples to see what a placeholder holds; past this the list is noise. */
	private static final int MAX_OBSERVED = 40;

	/** Enough other menus to see that a line is a shared one; past this the list is noise. */
	private static final int MAX_ALSO_SEEN = 20;

	/** Enough places to see where a line really comes from; past this it is a line seen everywhere. */
	private static final int MAX_SEEN_IN = 8;

	private final CaptureSurface surface;
	private final StyledText sample;
	private final Tokens.Split split;
	private final List<Set<String>> observed;
	private final Set<Integer> slots = new TreeSet<>();
	private final Set<String> alsoSeen = new LinkedHashSet<>();
	private final Set<String> seenIn = new LinkedHashSet<>();
	private final String note;
	private final Classifier.Verdict verdict;
	private final long firstSeen;

	private long lastSeen;
	private int count;
	private String id = "";
	private CaptureWriter.Meta meta;

	public CapturedLine(
		CaptureSurface surface, StyledText sample, String note, Classifier.Verdict verdict, String area, long when
	) {
		this.surface = surface;
		this.sample = sample;
		this.split = Tokens.of(sample.plain());
		this.observed = new ArrayList<>(this.split.size());
		this.note = note;
		this.verdict = verdict;
		this.firstSeen = when;
		this.lastSeen = when;
		this.count = 1;

		place(area);

		for (int i = 0; i < this.split.size(); i++) {
			this.observed.add(new LinkedHashSet<>());
		}
	}

	/**
	 * Folds another sighting in, or refuses it.
	 *
	 * @return true when this record now covers that line too, false when it is a different sentence
	 */
	public boolean merge(StyledText other, String note, String area, long when) {
		Tokens.Split candidate = Tokens.of(other.plain());

		if (!sameShape(candidate) || !this.note.equals(note)) {
			return false;
		}

		Set<Integer> differing = new TreeSet<>(this.slots);

		for (int i = 0; i < candidate.size(); i++) {
			if (!candidate.words().get(i).equals(this.split.words().get(i))) {
				differing.add(i);
			}
		}

		if (differing.size() > MAX_SLOTS) {
			return false;
		}

		List<Set<String>> tentative = withValues(differing, candidate);

		// A template must be mostly sentence. "You collected 3 Coins!" and "You claimed 2 Gifts!"
		// have the same shape and are the same shape as almost anything, and merging them produces
		// "You %1$sed %2$s %3$s!" — a template that is a grammar rather than a sentence, matches half
		// the chat, and cannot be translated as a whole. The words that stay have to outnumber, or at
		// least match, the words that move.
		//
		// A position that has only ever held digits does not count against that budget, because a
		// number is never one of the sentence's words. Without that exemption "Damage: +187 (+661.3)"
		// and "Damage: +500 (+1,895)" — one label and two numbers — were refused as a grammar, and a
		// menu full of stat rows became a record per row.
		if (wordy(differing, tentative) > candidate.size() - differing.size()) {
			return false;
		}

		// And the template that comes out has to be about something, by the same rule the engine
		// applies when it compiles one. This is the whole test now: counting literal words missed
		// that the sentence's only word can be inside the varying one — the sidebar clock varies
		// "3:30pm" into "3:40pm", which is the one word on the line, and the "pm" every sighting
		// shares is what the template is about. Refusing that made a record per ten in-game minutes,
		// a hundred and forty of them in one session.
		if (!keepsAWord(holes(differing, tentative))) {
			return false;
		}

		this.slots.clear();
		this.slots.addAll(differing);

		for (int slot : this.slots) {
			this.observed.set(slot, tentative.get(slot));
		}

		this.lastSeen = when;
		this.count++;
		place(area);

		return true;
	}

	/**
	 * What each position would have been seen holding, if this sighting were folded in.
	 *
	 * <p>Built before anything is committed, because the two guards below have to be answered about
	 * the record this merge <em>would</em> produce rather than about the one it came from.
	 */
	private List<Set<String>> withValues(Set<Integer> differing, Tokens.Split candidate) {
		List<Set<String>> values = new ArrayList<>(this.observed.size());

		for (int i = 0; i < this.observed.size(); i++) {
			Set<String> seen = new LinkedHashSet<>(this.observed.get(i));

			if (differing.contains(i) && seen.size() < MAX_OBSERVED) {
				seen.add(this.split.words().get(i));
				seen.add(candidate.words().get(i));
			}

			values.add(seen);
		}

		return values;
	}

	/** How many of the varying positions have held something that is not a number. */
	private static int wordy(Set<Integer> differing, List<Set<String>> observed) {
		int wordy = 0;

		for (int slot : differing) {
			for (String value : observed.get(slot)) {
				if (!Tokens.hasDigit(value)) {
					wordy++;
					break;
				}
			}
		}

		return wordy;
	}

	/**
	 * Whether anything outside the placeholders is a word of its own — the engine's own test for a
	 * record that is about a particular sentence rather than a shape.
	 */
	private boolean keepsAWord(List<Hole> holes) {
		String source = this.sample.plain();
		int cursor = 0;

		for (Hole hole : holes) {
			if (hasWord(source, cursor, hole.start())) {
				return true;
			}

			cursor = hole.end();
		}

		return hasWord(source, cursor, source.length());
	}

	private static boolean hasWord(String text, int from, int to) {
		return text.substring(from, to).chars().anyMatch(TranslationEntry::isWordOfItsOwn);
	}

	/**
	 * One placeholder-to-be: the stretch of the sample it covers and the values seen there, with the
	 * literal head and tail every value shares handed back to the sentence.
	 *
	 * <p>That last part is what turns {@code "%1$s"} holding {@code x23} and {@code x24} into
	 * {@code "x%1$s"} holding {@code 23} and {@code 24}. The {@code x} was never a value; it only
	 * looked like one because it happened to be glued to the number by the tokenizer.
	 */
	private record Hole(int start, int end, List<String> values) {}

	/** Where the placeholders would sit, given what each position has been seen holding. */
	private List<Hole> holes(Set<Integer> slots, List<Set<String>> observed) {
		List<Hole> holes = new ArrayList<>();

		for (int slot : slots) {
			List<String> values = new ArrayList<>(observed.get(slot));
			int prefix = Tokens.literalPrefix(values);
			int suffix = Tokens.literalSuffix(values, prefix);
			int[] at = this.split.at().get(slot);
			int start = at[0] + prefix;
			int end = at[1] - suffix;

			// Every sighting agreed after all: the words differ only in characters that turned out to
			// be shared, which means there is nothing variable here to record.
			if (start >= end) {
				continue;
			}

			List<String> trimmed = new ArrayList<>(values.size());

			for (String value : values) {
				trimmed.add(value.substring(prefix, value.length() - suffix));
			}

			holes.add(new Hole(start, end, trimmed));
		}

		return holes;
	}

	/** Same word count, same punctuation between them — the precondition for calling two lines one. */
	private boolean sameShape(Tokens.Split candidate) {
		return candidate.size() == this.split.size() && candidate.gaps().equals(this.split.gaps());
	}

	/** Another sighting of exactly the text already recorded. */
	public void again(long when) {
		this.lastSeen = when;
		this.count++;
	}

	/**
	 * The place the sidebar named when this line was seen.
	 *
	 * <p>Not the same fact as the gameplay category the file is filed under, and the difference is the
	 * point. {@code Your Island}, {@code Hub} and {@code Museum} all file into {@code Hub_General},
	 * because what they have in common is that the text there is the game's general furniture — so a
	 * line captured on somebody's private island reads back as a line from the Hub, and the file gives
	 * no way to tell. Whoever finishes the record needs to know which: an NPC on a private island and
	 * an NPC in the Hub say different things.
	 */
	private void place(String area) {
		if (area != null && !area.isEmpty() && this.seenIn.size() < MAX_SEEN_IN) {
			this.seenIn.add(area);
		}
	}

	/** The places this line was seen in, which may be several and may be none. @see #place */
	public Set<String> seenIn() {
		return this.seenIn;
	}

	/**
	 * The same, for a sighting that turned up somewhere else — another menu, or the same menu's lore
	 * rather than its item names.
	 *
	 * <p>The other place is remembered rather than filed separately. A navigation button, a rarity
	 * line, the paragraph at the top of every page of a guide: those are one record and the corpus
	 * keeps one copy of them in {@code _shared/}, so writing a copy per menu is work for whoever has
	 * to translate them and nothing else. Where else it was seen is worth keeping, though — it is how
	 * you tell a line that belongs to this menu from one that belongs to all of them.
	 */
	public void again(long when, String area, String where) {
		again(when);
		place(area);

		if (!where.isEmpty() && !where.equals(this.note) && this.alsoSeen.size() < MAX_ALSO_SEEN) {
			this.alsoSeen.add(where);
		}
	}

	/** The other places this same line turned up. Empty for the great majority of records. */
	public Set<String> alsoSeen() {
		return this.alsoSeen;
	}

	/** The finished record: what varies pulled out into placeholders. @see Hole */
	public Rendered render() {
		List<Hole> holes = holes(this.slots, this.observed);
		List<int[]> ranges = new ArrayList<>(holes.size());
		List<String> tokens = new ArrayList<>(holes.size());
		List<Placeholder> placeholders = new ArrayList<>(holes.size());

		for (int i = 0; i < holes.size(); i++) {
			Hole hole = holes.get(i);
			String token = "%" + (i + 1) + "$s";

			ranges.add(new int[] { hole.start(), hole.end() });
			tokens.add(token);
			placeholders.add(new Placeholder(token, hole.values()));
		}

		LegacyText.Encoded encoded = LegacyText.encode(this.sample, ranges, tokens);

		return new Rendered(encoded, plain(ranges, tokens), placeholders);
	}

	/** The template as plain text: the sample with each hole swapped for its token. */
	private String plain(List<int[]> holes, List<String> tokens) {
		StringBuilder text = new StringBuilder();
		String source = this.sample.plain();
		int cursor = 0;

		for (int i = 0; i < holes.size(); i++) {
			text.append(source, cursor, holes.get(i)[0]).append(tokens.get(i));
			cursor = holes.get(i)[1];
		}

		return text.append(source, cursor, source.length()).toString();
	}

	/** A placeholder and every value the game has been seen to put in it. */
	public record Placeholder(String token, List<String> observed) {
		public String type() {
			for (String value : this.observed) {
				if (!Tokens.isNumeric(value)) {
					return "raw";
				}
			}

			return "number";
		}
	}

	public record Rendered(LegacyText.Encoded encoded, String text, List<Placeholder> placeholders) {}

	public CaptureSurface surface() {
		return this.surface;
	}

	public StyledText sample() {
		return this.sample;
	}

	public String note() {
		return this.note;
	}

	/** Why this line was kept — which pile, and what the corpus already knows about it. */
	public Classifier.Verdict verdict() {
		return this.verdict;
	}

	public int count() {
		return this.count;
	}

	public long firstSeen() {
		return this.firstSeen;
	}

	public long lastSeen() {
		return this.lastSeen;
	}

	public String id() {
		return this.id;
	}

	public void id(String id) {
		this.id = id;
	}

	/** The file this line was filed into, so a later sighting of it can mark that file dirty. */
	CaptureWriter.Meta meta() {
		return this.meta;
	}

	void meta(CaptureWriter.Meta meta) {
		this.meta = meta;
	}
}
