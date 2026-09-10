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

	public record Match(int lines, Component source, TranslationEntry entry, StyledText core,
		Matcher matcher, Component head, Component tail) {
		public MutableComponent render(TermTable terms, boolean originals) {
			MutableComponent result = Component.empty();
			if (head != null) result.append(head);
			result.append(entry.render(core, matcher, terms, originals));
			if (tail != null) result.append(tail);
			return result;
		}
	}

	private LoreMatcher() {}

	/** start refers to lore, not a tooltip's name. No global config: capture works with translation off. */
	public static Match find(TranslationIndex index, List<Component> lines, int start) {
		if (start < 0 || start >= lines.size() || index.size(Surface.LORE) == 0) return null;
		MutableComponent joined = Component.empty();
		Component head = null;
		Match best = null;
		int length = 0;
		for (int i = start; i < lines.size() && i - start < MAX_LINES; i++) {
			StyledText line = StyledText.of(lines.get(i));
			String plain = line.plain();
			int from = 0, to = plain.length();
			while (from < to && plain.charAt(from) == ' ') from++;
			while (to > from && plain.charAt(to - 1) == ' ') to--;
			if (from == to) break;
			length += to - from + (i == start ? 0 : 1);
			if (length > MAX_CHARS) break;
			if (i == start && from > 0) head = line.slice(0, from);
			if (i > start) joined.append(Component.literal(" ").setStyle(line.styleAt(from)));
			joined.append(line.slice(from, to));
			StyledText core = StyledText.of(joined);
			TranslationEntry entry = index.lookup(Surface.LORE, core.canonical());
			if (entry == null || entry.continuation()) continue;
			Matcher matcher = entry.match(core.canonical());
			if (matcher == null) continue;
			Component tail = to < plain.length() ? line.slice(to, plain.length()) : null;
			best = new Match(i - start + 1, joined.copy(), entry, core, matcher, head, tail);
		}
		return best;
	}
}
