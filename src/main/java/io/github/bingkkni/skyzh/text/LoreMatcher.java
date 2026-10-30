package io.github.bingkkni.skyzh.text;

import java.util.List;
import java.util.regex.Matcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Matches authored complete sentences across server wrap boundaries. Only GUI_Lore records are
 * searched: an ITEM name or a legacy half-sentence cannot accidentally consume neighbouring lines.
 * There is no guessing from punctuation or colour. All words must match one bounded template;
 * blanks are hard boundaries and unmatched input is left to the existing line translator.
 *
 * <p>Shared by rendering, capture and offline coverage tests. Inputs are never mutated. Captured
 * values and translated fragments still take their styles from the original components.
 */
public final class LoreMatcher {
	public static final int MAX_LINES = 12;
	public static final int MAX_CHARS = 2048;

	public record Match(
		int lines, Component source, TranslationEntry entry, StyledText core,
		Matcher matcher, Component head, Component tail
	) {
		public MutableComponent render(TermTable terms) {
			MutableComponent result = Component.empty();

			if (head != null) {
				result.append(head);
			}

			result.append(entry.render(core, matcher, terms));

			if (tail != null) {
				result.append(tail);
			}

			return result;
		}
	}

	private LoreMatcher() {
	}

	/** start refers to lore, not a tooltip's name. No global config: capture works with translation off. */
	public static Match find(TranslationIndex index, List<Component> lines, int start) {
		return new Search(index, lines).find(start);
	}

	/** One plan reuses flattened lines and both successful and failed searches at each start. */
	static final class Search {
		private final TranslationIndex index;
		private final List<Component> lines;
		private final StyledText[] snapshots;
		private final Match[] matches;
		private final boolean[] searched;

		Search(TranslationIndex index, List<Component> lines) {
			this.index = index;
			this.lines = lines;
			this.snapshots = new StyledText[lines.size()];
			this.matches = new Match[lines.size()];
			this.searched = new boolean[lines.size()];
		}

		StyledText line(int at) {
			if (this.snapshots[at] == null) this.snapshots[at] = StyledText.of(this.lines.get(at));
			return this.snapshots[at];
		}

		Match find(int start) {
			if (start < 0 || start >= this.lines.size() || this.index.size(Surface.LORE) == 0) return null;
			if (!this.searched[start]) {
				this.matches[start] = search(this, start);
				this.searched[start] = true;
			}
			return this.matches[start];
		}
	}

	private static Match search(Search search, int start) {
		StyledText.Builder joined = new StyledText.Builder();
		Component head = null;
		Match best = null;
		int length = 0;
		int firstContentLength = 0;

		for (int i = start; i < search.lines.size() && i - start < MAX_LINES; i++) {
			StyledText line = search.line(i);
			String plain = line.plain();
			int from = 0;
			int to = plain.length();

			while (from < to && plain.charAt(from) == ' ') {
				from++;
			}

			while (to > from && plain.charAt(to - 1) == ' ') {
				to--;
			}

			if (from == to) {
				break;
			}

			length += to - from + (i == start ? 0 : 1);

			if (length > MAX_CHARS) {
				break;
			}

			if (i == start) {
				firstContentLength = to - from;
				if (from > 0) head = line.slice(0, from);
			}

			if (i > start) {
				joined.append(" ", 0, 1, line.styleAt(from));
			}

			joined.append(line, from, to);
			StyledText whole = joined.build();
			// Reuse the same closed structural prefixes as single-line translation. Only the
			// first line may carry a peeled bullet; an exact record including it still wins.
			for (LineShape.Range range : LineShape.candidates(Surface.LORE, whole.canonical())) {
				if (range.start() >= firstContentLength) continue;
				StyledText core = whole.sub(range.start(), range.end());
				TranslationIndex.Match found = search.index.match(Surface.LORE, core.canonical());
				if (found == null || found.entry().continuation()) continue;
				TranslationEntry entry = found.entry();
				Matcher matcher = found.matcher();
				Component leading = head;
				if (range.start() > 0) {
					MutableComponent prefix = Component.empty();
					if (head != null) prefix.append(head);
					leading = prefix.append(whole.slice(0, range.start()));
				}
				Component tail = to < plain.length() ? line.slice(to, plain.length()) : null;
				best = new Match(i - start + 1, whole.slice(0, whole.length()), entry, core, matcher, leading, tail);
				break;
			}
		}

		return best;
	}
}
