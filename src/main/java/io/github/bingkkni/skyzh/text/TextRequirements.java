package io.github.bingkkni.skyzh.text;

import java.util.BitSet;

/** Conservative per-surface disjunction: a letter, or a mandatory character of a symbol-only row. */
final class TextRequirements {
	private final BitSet alternatives = new BitSet();
	private boolean unrestricted;

	void add(TranslationEntry entry) {
		if (entry.requiresLetter()) return;
		String anchor = entry.anchor();
		if (anchor.isEmpty()) { this.unrestricted = true; return; }
		// Prefer distinctive symbols over spaces/digits; every chosen character is mandatory.
		int required = anchor.codePoints().filter(c -> c > 127 && !Character.isWhitespace(c)).findFirst()
			.orElseGet(() -> anchor.codePoints().filter(c -> !Character.isWhitespace(c)).findFirst().orElse(anchor.codePointAt(0)));
		this.alternatives.set(required);
	}

	boolean accepts(int codePoint) {
		return this.unrestricted || Character.isLetter(codePoint) || this.alternatives.get(codePoint);
	}

	boolean unrestricted() { return this.unrestricted; }
}
