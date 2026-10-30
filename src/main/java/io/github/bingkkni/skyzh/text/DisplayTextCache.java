package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.hook.PriorityText;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/**
 * Bounded font-free display results. Keys include full live styles, never component identity.
 *
 * <p>Literal trees are also filed by {@link TextShape}, which finds a repeated frame's entry without
 * flattening it; anything else, and any shape not yet seen, takes the flattened key.
 */
public final class DisplayTextCache {
	public enum Mode { LINE, ROW, BLOCK, WIDGETS, HOLOGRAM, MOB, TIMER }

	private record Key(StyledText source, Surface surface, Mode mode) {}
	/** {@code plain} is the rendered text as the force display priority remembers it. */
	private record Value(Component rendered, boolean unchanged, String plain) {}
	private record Shaped(TextShape shape, Surface surface, Mode mode, Value value, Shaped next) {}
	private static final int LIMIT = 512;
	private static final Map<Key, Value> CACHE = new LinkedHashMap<>(LIMIT, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
			return size() > LIMIT;
		}
	};
	/** Chained by shape hash; cleared when this table fills, independently of CACHE's LRU eviction. */
	private static final Shaped[] SHAPES = new Shaped[LIMIT * 2];
	private static int shaped;
	private static TranslationIndex corpus;
	private static int generation = -1;
	private static boolean skyBlockName;

	private DisplayTextCache() {
	}

	/** Runtime callers enforce server and display switches before reaching this cache. */
	public static Component get(Component source, Surface surface, Mode mode,
		Function<StyledText, Component> render) {
		long shape = TextShape.hash(source);
		TranslationIndex current;
		int currentGeneration;
		boolean currentName;
		synchronized (CACHE) {
			current = Translator.index();
			currentGeneration = SkyZHConfig.generation();
			currentName = SkyZHConfig.get().translateSkyBlockName;
			if (corpus != current || generation != currentGeneration || skyBlockName != currentName) {
				CACHE.clear();
				Arrays.fill(SHAPES, null);
				shaped = 0;
				corpus = current;
				generation = currentGeneration;
				skyBlockName = currentName;
			}
			Value value = shape == TextShape.NONE ? null : shaped(shape, source, surface, mode);
			if (value != null) return shown(value, source);
		}
		StyledText styled = StyledText.of(source);
		Key key = new Key(styled, surface, mode);
		TextShape recorded = TextShape.of(source, shape);
		synchronized (CACHE) {
			Value value = valid(current, currentGeneration, currentName) ? CACHE.get(key) : null;
			if (value != null) {
				if (recorded != null) shape(recorded, surface, mode, value);
				return shown(value, source);
			}
		}
		Component rendered = render.apply(styled);
		StyledText output = StyledText.of(rendered);
		boolean unchanged = styled.equals(output);
		// Own every component in the cached tree; later mutations of input siblings cannot affect it.
		Value value = unchanged ? new Value(null, true, null)
			: new Value(output.slice(0, output.length()), false, output.plain().strip());
		synchronized (CACHE) {
			if (valid(current, currentGeneration, currentName)) {
				CACHE.put(key, value);
				if (recorded != null) shape(recorded, surface, mode, value);
			}
		}
		return shown(value, source);
	}

	/** Flattening and rendering run outside the lock; a reload may publish a new index meanwhile. */
	private static boolean valid(TranslationIndex current, int currentGeneration, boolean currentName) {
		return corpus == current && generation == currentGeneration && skyBlockName == currentName
			&& Translator.index() == current && SkyZHConfig.generation() == currentGeneration
			&& SkyZHConfig.get().translateSkyBlockName == currentName;
	}

	private static Component shown(Value value, Component source) {
		return value.unchanged() ? source : PriorityText.remember(value.rendered(), value.plain());
	}

	private static Value shaped(long hash, Component source, Surface surface, Mode mode) {
		for (Shaped entry = SHAPES[slot(hash)]; entry != null; entry = entry.next()) {
			if (entry.shape().hash() == (int) hash && entry.surface() == surface && entry.mode() == mode
				&& entry.shape().matches(source)) return entry.value();
		}
		return null;
	}

	private static void shape(TextShape shape, Surface surface, Mode mode, Value value) {
		if (shaped >= LIMIT * 2) {
			Arrays.fill(SHAPES, null);
			shaped = 0;
		}
		int slot = slot(shape.hash());
		SHAPES[slot] = new Shaped(shape, surface, mode, value, SHAPES[slot]);
		shaped++;
	}

	private static int slot(long hash) {
		int value = (int) hash;
		return (value ^ value >>> 16) & SHAPES.length - 1;
	}
}
