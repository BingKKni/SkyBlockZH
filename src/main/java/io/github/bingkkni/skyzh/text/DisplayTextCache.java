package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.hook.PriorityText;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/** Bounded, per-surface display results keyed by full live styles, never component identity. */
public final class DisplayTextCache {
	public enum Mode { LINE, ROW, BLOCK, WIDGETS, HOLOGRAM, MOB, TIMER, ITEM_NAME }

	private record Key(StyledText source, Mode mode) {}
	private record Value(Component rendered, boolean unchanged, String plain) {}
	private record Shaped(TextShape shape, Key key, Value value, Shaped next) {}
	private static final int LIMIT = 512;
	private static final Object LOCK = new Object();
	private static final Bucket[] BUCKETS = new Bucket[Surface.values().length];
	private static TranslationIndex corpus;
	private static int generation = -1;
	private static boolean skyBlockName;
	private static int names;

	private static final class Bucket {
		final Map<Key, Value> cache = new LinkedHashMap<>(LIMIT, 0.75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
				return size() > LIMIT;
			}
		};
		Shaped[] recent = new Shaped[LIMIT * 2], previous = new Shaped[LIMIT * 2];
		int shaped;

		Shaped find(long hash, Component source, Mode mode) {
			Shaped entry = find(this.recent, hash, source, mode);
			if (entry == null) {
				entry = find(this.previous, hash, source, mode);
				if (entry != null) shape(entry.shape(), entry.key(), entry.value());
			}
			// Structural hits must refresh the flattened LRU too, including re-admitting an evicted key.
			if (entry != null) this.cache.put(entry.key(), entry.value());
			return entry;
		}

		private static Shaped find(Shaped[] table, long hash, Component source, Mode mode) {
			for (Shaped entry = table[slot(hash)]; entry != null; entry = entry.next()) {
				if (entry.shape().hash() == (int) hash && entry.key().mode() == mode
					&& entry.shape().matches(source)) return entry;
			}
			return null;
		}

		void shape(TextShape shape, Key key, Value value) {
			if (this.shaped >= LIMIT) {
				Shaped[] retired = this.previous;
				this.previous = this.recent;
				this.recent = retired;
				Arrays.fill(this.recent, null);
				this.shaped = 0;
			}
			int slot = slot(shape.hash());
			this.recent[slot] = new Shaped(shape, key, value, this.recent[slot]);
			this.shaped++;
		}
	}

	private DisplayTextCache() {}

	/** Runtime callers enforce server and display switches before reaching this cache. */
	public static Component get(Component source, Surface surface, Mode mode,
		Function<StyledText, Component> render) {
		long shape = TextShape.hash(source);
		TranslationIndex current;
		int currentGeneration;
		boolean currentName;
		Bucket bucket;
		synchronized (LOCK) {
			current = Translator.index();
			currentGeneration = SkyZHConfig.generation();
			currentName = SkyZHConfig.get().translateSkyBlockName;
			if (corpus != current || generation != currentGeneration || skyBlockName != currentName
				|| names != ItemNamePolicy.state()) {
				Arrays.fill(BUCKETS, null);
				corpus = current;
				generation = currentGeneration;
				skyBlockName = currentName;
				names = ItemNamePolicy.state();
			}
			bucket = BUCKETS[surface.ordinal()];
			if (bucket == null) BUCKETS[surface.ordinal()] = bucket = new Bucket();
			Shaped hit = shape == TextShape.NONE ? null : bucket.find(shape, source, mode);
			if (hit != null) return shown(hit.value(), source);
		}
		StyledText styled = StyledText.of(source);
		Key key = new Key(styled, mode);
		TextShape recorded = TextShape.of(source, shape);
		synchronized (LOCK) {
			Value value = valid(current, currentGeneration, currentName) ? bucket.cache.get(key) : null;
			if (value != null) {
				if (recorded != null) bucket.shape(recorded, key, value);
				return shown(value, source);
			}
		}
		Component rendered = render.apply(styled);
		StyledText output = StyledText.of(rendered);
		boolean unchanged = styled.equals(output);
		// Own every component in the cached tree; input mutations cannot affect a cached result.
		Value value = unchanged ? new Value(null, true, null)
			: new Value(output.slice(0, output.length()), false, output.plain().strip());
		synchronized (LOCK) {
			if (valid(current, currentGeneration, currentName)) {
				bucket.cache.put(key, value);
				if (recorded != null) bucket.shape(recorded, key, value);
			}
		}
		return shown(value, source);
	}

	/** Flattening and rendering run outside the lock; a reload may publish a new index meanwhile. */
	private static boolean valid(TranslationIndex current, int currentGeneration, boolean currentName) {
		return corpus == current && generation == currentGeneration && skyBlockName == currentName
			&& Translator.index() == current && SkyZHConfig.generation() == currentGeneration
			&& SkyZHConfig.get().translateSkyBlockName == currentName && names == ItemNamePolicy.state();
	}

	private static Component shown(Value value, Component source) {
		return value.unchanged() ? source : PriorityText.remember(value.rendered(), value.plain());
	}

	private static int slot(long hash) {
		int value = (int) hash;
		return (value ^ value >>> 16) & (LIMIT * 2 - 1);
	}
}
