package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/** Bounded font-free display results. Keys include full live styles, never component identity. */
public final class DisplayTextCache {
	public enum Mode { LINE, ROW, BLOCK, WIDGETS, HOLOGRAM, MOB, TIMER }

	private record Key(StyledText source, Surface surface, Mode mode) {}
	private record Value(Component rendered, boolean unchanged) {}
	private static final int LIMIT = 512;
	private static final Map<Key, Value> CACHE = new LinkedHashMap<>(LIMIT, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
			return size() > LIMIT;
		}
	};
	private static TranslationIndex corpus;
	private static int generation = -1;
	private static boolean skyBlockName;

	private DisplayTextCache() {
	}

	/** Runtime callers enforce server and display switches before reaching this cache. */
	public static Component get(Component source, Surface surface, Mode mode,
		Function<StyledText, Component> render) {
		StyledText styled = StyledText.of(source);
		Key key = new Key(styled, surface, mode);
		TranslationIndex current = Translator.index();
		int currentGeneration = SkyZHConfig.generation();
		boolean currentName = SkyZHConfig.get().translateSkyBlockName;
		synchronized (CACHE) {
			if (corpus != current || generation != currentGeneration || skyBlockName != currentName) {
				CACHE.clear();
				corpus = current;
				generation = currentGeneration;
				skyBlockName = currentName;
			}
			Value value = CACHE.get(key);
			if (value != null) return value.unchanged() ? source : value.rendered();
		}
		Component rendered = render.apply(styled);
		StyledText output = StyledText.of(rendered);
		boolean unchanged = styled.equals(output);
		// Own every component in the cached tree; later mutations of input siblings cannot affect it.
		Value value = new Value(unchanged ? null : output.slice(0, output.length()), unchanged);
		synchronized (CACHE) {
			if (corpus == current && generation == currentGeneration && skyBlockName == currentName) {
				CACHE.put(key, value);
			}
		}
		return unchanged ? source : value.rendered();
	}
}
