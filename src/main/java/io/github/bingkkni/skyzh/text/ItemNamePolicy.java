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

	public static boolean maps() { return OVERRIDE.get() != null || SkyZHConfig.get().translateMapNames; }

	/** Private-island labels (including the Rift's quoted spelling) stay readable in Chinese. */
	static boolean islandException(String value) {
		if (value.equalsIgnoreCase("Your Island") || value.equalsIgnoreCase("\"Your\" Island")) return true;
		Possessive.Owned owned = Possessive.split(value);
		return owned != null && owned.thing().equalsIgnoreCase("Island");
	}

	public static long disabledCurrencies() {
		return OVERRIDE.get() != null ? 0 : SkyZHConfig.get().disabledCurrencies();
	}

	/** All name choices as one cache key, including unsaved settings and coverage mode. */
	public static long state() {
		if (OVERRIDE.get() != null) return 7;
		var config = SkyZHConfig.get();
		return (config.translateItemNames ? 1 : 0) | (config.translateMobNames ? 2 : 0)
			| (config.translateMapNames ? 4 : 0) | (config.disabledCurrencies() << 3);
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
