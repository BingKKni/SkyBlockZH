package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every compiled record, bucketed by the surface it is allowed to answer for, plus the lookup that
 * finds the one matching a given line.
 *
 * <p>Two tiers, because most SkyBlock text is fixed strings and only some of it has values in it.
 * Lines with no placeholder go into a hash map and cost one lookup. Lines with placeholders have to
 * be tried in turn, so the outcome — hit <em>or</em> miss — is remembered against the exact text
 * that was searched for. Chat repeats itself and a tooltip re-renders every frame, so the second
 * time any line is seen it costs a map lookup either way.
 */
public final class TranslationIndex {
	private static final int CACHE_SIZE = 2048;

	private final Map<Surface, Map<String, TranslationEntry>> exact = new EnumMap<>(Surface.class);
	private final Map<Surface, List<TranslationEntry>> patterns = new EnumMap<>(Surface.class);
	private final Map<Surface, Map<String, TranslationEntry>> cache = new EnumMap<>(Surface.class);

	private SkyBlockName skyBlockName = SkyBlockName.DEFAULT;
	private TermTable terms = TermTable.EMPTY;

	public TranslationIndex() {
		for (Surface surface : Surface.values()) {
			this.exact.put(surface, new HashMap<>());
			this.patterns.put(surface, new ArrayList<>());
			this.cache.put(surface, new LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, TranslationEntry> eldest) {
					return size() > CACHE_SIZE;
				}
			});
		}
	}

	/** The gameplay's own name, which is substituted into every surface rather than stored per line. */
	public SkyBlockName skyBlockName() {
		return this.skyBlockName;
	}

	public void skyBlockName(SkyBlockName name) {
		this.skyBlockName = name;
	}

	/** Chinese for the values placeholders capture, shared by every record on every surface. */
	public TermTable terms() {
		return this.terms;
	}

	public void terms(TermTable terms) {
		this.terms = terms;
	}

	/**
	 * @param plainTemplate the record's English with placeholders still in it, used to decide which
	 *                      tier the record belongs to
	 */
	public void add(Surface surface, String plainTemplate, TranslationEntry entry) {
		if (plainTemplate.indexOf('%') < 0) {
			this.exact.get(surface).putIfAbsent(plainTemplate, entry);
		} else {
			this.patterns.get(surface).add(entry);
		}
	}

	/**
	 * The record that matches this exact line, or {@code null} if the corpus has nothing for it.
	 *
	 * <p>An exact record always wins: it spelled the line out in full and cannot be beaten. Among
	 * placeholder records the most specific one wins — the one whose template is the most literal
	 * text rather than the most captures. Several can fit the same line ({@code "Remaining: %s"} and
	 * {@code "Remaining: %s goblin(s)"} both fit "Remaining: 3 goblin(s)"), and the alternative,
	 * taking whichever turned up first, would make the answer depend on the order the corpus files
	 * happened to be walked in — a translation that changes because a file was renamed.
	 */
	public TranslationEntry lookup(Surface surface, String plain) {
		Map<String, TranslationEntry> cached = this.cache.get(surface);

		synchronized (cached) {
			if (cached.containsKey(plain)) {
				// A null value is a remembered miss — worth caching, since most text on screen in a
				// 928-record corpus is text nobody has translated yet.
				return cached.get(plain);
			}
		}

		TranslationEntry found = this.exact.get(surface).get(plain);

		if (found == null) {
			for (TranslationEntry entry : this.patterns.get(surface)) {
				if ((found == null || entry.specificity() > found.specificity())
					&& entry.match(plain) != null) {
					found = entry;
				}
			}
		}

		synchronized (cached) {
			cached.put(plain, found);
		}

		return found;
	}

	/**
	 * Every record filed against one surface, in no particular order.
	 *
	 * <p>Exists for the diagnostic that answers "the corpus clearly has this line, so why is it still
	 * English" — which needs to compare a live line against records that did <em>not</em> match it,
	 * something a lookup by definition cannot do.
	 */
	public List<TranslationEntry> entries(Surface surface) {
		List<TranslationEntry> all = new ArrayList<>(this.exact.get(surface).values());
		all.addAll(this.patterns.get(surface));

		return all;
	}

	public int size() {
		int total = 0;

		for (Surface surface : Surface.values()) {
			total += this.exact.get(surface).size() + this.patterns.get(surface).size();
		}

		return total;
	}

	public int size(Surface surface) {
		return this.exact.get(surface).size() + this.patterns.get(surface).size();
	}
}
