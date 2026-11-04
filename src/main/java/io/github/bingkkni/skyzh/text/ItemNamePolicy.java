package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.function.Supplier;

/** Display choice scoped separately from corpus compilation and capture coverage. */
public final class ItemNamePolicy {
	private static final ThreadLocal<Boolean> OVERRIDE = new ThreadLocal<>();

	private ItemNamePolicy() {}

	public static boolean enabled() {
		Boolean override = OVERRIDE.get();
		return override == null ? SkyZHConfig.get().translateItemNames : override;
	}

	public static boolean mobNames() {
		Boolean override = OVERRIDE.get();
		return override == null ? SkyZHConfig.get().translateMobNames : override;
	}

	/** Both display choices as one cache key. */
	public static int state() {
		return (enabled() ? 1 : 0) | (mobNames() ? 2 : 0);
	}

	/** Coverage always describes available translations, regardless of the player's display choice. */
	public static <T> T available(Supplier<T> operation) {
		Boolean previous = OVERRIDE.get();
		OVERRIDE.set(true);
		try { return operation.get(); }
		finally {
			if (previous == null) OVERRIDE.remove();
			else OVERRIDE.set(previous);
		}
	}
}
