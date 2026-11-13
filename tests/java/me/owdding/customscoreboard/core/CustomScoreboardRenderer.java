package me.owdding.customscoreboard.core;

import java.util.function.BooleanSupplier;

/** Test-only call-site fixture with the verified external renderer's binary name; never packaged. */
public final class CustomScoreboardRenderer {
	public void updateDisplay() {}
	public void onRender(tech.thatgravyboat.skyblockapi.api.events.render.RenderHudEvent event) {}
	public static boolean submit(BooleanSupplier operation) { return operation.getAsBoolean(); }
}
