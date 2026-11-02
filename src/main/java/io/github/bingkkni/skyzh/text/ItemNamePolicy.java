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
