package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import io.github.bingkkni.skyzh.hook.PriorityText;
import io.github.bingkkni.skyzh.hook.SidebarCompatibility;
import io.github.bingkkni.skyzh.text.Surface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** Performance-path regressions without loading a game or making timing assertions. */
final class PriorityPerformanceChecks {
	private static int checked;

	static void run() throws Exception {
		checkScopes();
		checkRawPrefixesAndFrames();
		System.out.println("Priority performance checks=" + checked);
	}

	/** The offline fixture represents one installed renderer whose optional wrapper may be absent. */
	static void sidebarFrame(boolean strict, Runnable extract, Runnable replacement) {
		invokeFrame(ForceSidebar.class, new Class<?>[] {boolean.class, int.class, Runnable.class, Runnable.class},
			strict, ForceSidebar.CUSTOM, extract, replacement);
	}

	private static void displayFrame(boolean verified, Runnable render) {
		invokeFrame(PriorityText.class, new Class<?>[] {boolean.class, Runnable.class}, verified, render);
	}

	private static void invokeFrame(Class<?> owner, Class<?>[] signature, Object... arguments) {
		try {
			Method frame = owner.getDeclaredMethod("withFrame", signature);
			frame.setAccessible(true);
			frame.invoke(null, arguments);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException failure) throw failure;
			if (e.getCause() instanceof Error failure) throw failure;
			throw new AssertionError(e.getCause());
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}

	private static void checkScopes() throws Exception {
		var observed = ForceSidebar.class.getDeclaredField("observedScopes");
		observed.setAccessible(true);
		int previous = observed.getInt(null);
		RuntimeException failure = new RuntimeException("renderer failed");
		try {
			sidebarFrame(true, () -> {
				ForceSidebar.withRenderer(ForceSidebar.CUSTOM, () -> {
					check("injected scope blocks submissions", ForceSidebar.suppressCustomDraw());
					check("nested non-void scope preserves result",
						ForceSidebar.withRendererResult(ForceSidebar.SKYHANNI, () -> 42) == 42);
					sidebarFrame(false, () -> check("non-strict nested frame still passes submissions",
						!ForceSidebar.suppressCustomDraw()), () -> {});
					check("nested frame restores renderer scope", ForceSidebar.suppressCustomDraw());
				});
				check("known wrappers eliminate stack scanning outside their scope", !customDrawBlocked());
				try { ForceSidebar.withRenderer(ForceSidebar.CUSTOM, () -> { throw failure; }); }
				catch (RuntimeException actual) { check("renderer exceptions propagate unchanged", actual == failure); }
				check("exceptions restore renderer depth", !ForceSidebar.suppressCustomDraw());
			}, () -> check("replacement is never suppressed", !ForceSidebar.suppressCustomDraw()));
			observed.setInt(null, 0);
			sidebarFrame(true, () -> check("missing wrapper retains stack fallback", customDrawBlocked()), () -> {});
			check("outside a replacement frame the scope has no display effect",
				ForceSidebar.withRendererResult(ForceSidebar.CUSTOM, ForceSidebar::suppressCustomDraw) == false);
		} finally { observed.setInt(null, previous); }
	}

	private static boolean customDrawBlocked() {
		return me.owdding.customscoreboard.core.CustomScoreboardRenderer.submit(ForceSidebar::suppressCustomDraw);
	}

	@SuppressWarnings("unchecked")
	private static void checkRawPrefixesAndFrames() throws Exception {
		Method prefix = PriorityText.class.getDeclaredMethod("prefixMatches", String.class);
		prefix.setAccessible(true);
		Method put = PriorityText.class.getDeclaredMethod("put", Map.class, Component.class, Surface.class);
		put.setAccessible(true);
		var hud = PriorityText.class.getDeclaredField("HUD");
		hud.setAccessible(true);
		Map<?, ?> sources = (Map<?, ?>) hud.get(null);
		sources.clear();
		PriorityText.translationsChanged();
		put.invoke(null, sources, Component.literal("SKYBLOCK"), Surface.SCOREBOARD);
		put.invoke(null, sources, Component.literal("X"), Surface.SCOREBOARD);
		for (String raw : new String[] {"SKYBLOCK", "  §aSK§qYBLOCK", "X    ", " §bX", "中文", "😀",
			"UNKNOWN עברית", "\u2003X\u2003"})
			check("raw prefilter retains legacy/short/Unicode/bidi text: " + raw, (boolean) prefix.invoke(null, raw));
		check("ordinary ASCII prefix miss is rejected", !(boolean) prefix.invoke(null, "ZZZZ unrelated GUI"));
		DisplayPriority previous = SkyZHConfig.get().displayPriority;
		try {
			SkyZHConfig.get().displayPriority = DisplayPriority.HIGH;
			displayFrame(true, () -> {
				check("frame reuses verified server state", PriorityText.active() && PriorityText.active());
				check("String hooks filter before visual ordering",
					!PriorityText.mayResolve("ZZZZ unrelated GUI") && PriorityText.mayResolve("SKYBLOCK"));
				displayFrame(false, () -> check("nested unverified frame closes the gate", !PriorityText.active()));
				check("nested frame restores outer state", PriorityText.active());
				SkyZHConfig.get().enabled = false;
				check("frame cache never bypasses global toggle", !PriorityText.active());
				SkyZHConfig.get().enabled = true;
				HoldOriginal.setActive(true);
				check("frame cache never bypasses original-text key", !PriorityText.active());
				HoldOriginal.setActive(false);
				SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
				check("default priority keeps global font hooks inactive", !PriorityText.active());
				SidebarCompatibility.display(() -> check("scoped default adapter retains frame verification", PriorityText.active()));
			});
			SkyZHConfig.get().displayPriority = DisplayPriority.HIGH;
			RuntimeException failure = new RuntimeException("frame failed");
			try { displayFrame(true, () -> { throw failure; }); }
			catch (RuntimeException actual) { check("frame exceptions propagate unchanged", actual == failure); }
			check("normal/exceptional frame exit cannot retain verification", !PriorityText.active());
		} finally {
			SkyZHConfig.get().enabled = true;
			SkyZHConfig.get().displayPriority = previous;
			HoldOriginal.setActive(false);
			sources.clear();
			PriorityText.translationsChanged();
		}
	}

	private static void check(String message, boolean condition) {
		if (!condition) throw new AssertionError(message);
		checked++;
	}
}
