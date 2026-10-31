package io.github.bingkkni.skyzh.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;
import net.fabricmc.loader.api.FabricLoader;

/** Read-only optional integrations. Never modifies another mod's settings or render implementation. */
public final class SidebarCompatibility {
	private SidebarCompatibility() {}

	private static final class Probes {
		static final BooleanSupplier CUSTOM_SCOREBOARD = installed("customscoreboard", SidebarCompatibility::customScoreboard);
		static final BooleanSupplier SKYHANNI = installed("skyhanni", SidebarCompatibility::skyHanni);
		static final BooleanSupplier NOAMM = installed("noammaddons", SidebarCompatibility::noamm);
	}

	public static boolean hasCustomSidebar() {
		return enabled(Probes.CUSTOM_SCOREBOARD) || enabled(Probes.SKYHANNI) || enabled(Probes.NOAMM);
	}

	@FunctionalInterface
	private interface ProbeFactory {
		BooleanSupplier create() throws ReflectiveOperationException;
	}

	private static BooleanSupplier installed(String id, ProbeFactory probe) {
		if (!FabricLoader.getInstance().isModLoaded(id)) return () -> false;
		try { return probe.create(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return () -> true; }
	}

	// Unknown API versions fail safe: leave that mod's HUD alone rather than drawing a second panel.
	static boolean enabled(BooleanSupplier probe) {
		try { return probe.getAsBoolean(); }
		catch (RuntimeException | LinkageError failure) { return true; }
	}

	private static BooleanSupplier customScoreboard() throws ReflectiveOperationException {
		return customScoreboard(Class.forName("me.owdding.customscoreboard.config.Config"));
	}

	private static BooleanSupplier customScoreboard(Class<?> type) throws ReflectiveOperationException {
		Object instance = type.getField("INSTANCE").get(null);
		Method enabled = type.getMethod("getEnabled");
		// Read the feature toggle, not visibility: Tab/chat/F3 hiding and ScoreboardOverhaul
		// delegation still belong to the player's chosen sidebar, not to our fallback panel.
		return () -> {
			try { return !Boolean.FALSE.equals(enabled.invoke(instance)); }
			catch (ReflectiveOperationException failure) { return true; }
		};
	}

	private static BooleanSupplier skyHanni() throws ReflectiveOperationException {
		return skyHanni(Class.forName("at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard"));
	}

	private static BooleanSupplier skyHanni(Class<?> type) throws ReflectiveOperationException {
		Object instance = type.getField("INSTANCE").get(null);
		// Kotlin's public internal getter suffix differs between Minecraft builds.
		Method config = java.util.Arrays.stream(type.getMethods())
			.filter(method -> method.getName().startsWith("getConfig$") && method.getParameterCount() == 0
				&& !Modifier.isStatic(method.getModifiers()))
			.findFirst().orElseThrow(NoSuchMethodException::new);
		Method enabled = config.getReturnType().getMethod("getEnabled");
		Method get = enabled.getReturnType().getMethod("get");
		return () -> {
			try { return !Boolean.FALSE.equals(get.invoke(enabled.invoke(config.invoke(instance)))); }
			catch (ReflectiveOperationException failure) { return true; }
		};
	}

	private static BooleanSupplier noamm() throws ReflectiveOperationException {
		return noamm(Class.forName("com.github.noamm9.features.impl.visual.Scoreboard"));
	}

	private static BooleanSupplier noamm(Class<?> type) throws ReflectiveOperationException {
		Object instance = type.getField("INSTANCE").get(null);
		var enabled = type.getField("enabled");
		return () -> {
			try { return enabled.getBoolean(instance); }
			catch (ReflectiveOperationException failure) { return true; }
		};
	}
}
