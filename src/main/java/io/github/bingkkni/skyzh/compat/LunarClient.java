package io.github.bingkkni.skyzh.compat;

import net.minecraft.client.gui.Font;

/** Detect Lunar's public vanilla bridge once, without loading or changing its implementation. */
public final class LunarClient {
	private static final class Detection {
		static final boolean PRESENT = hasFontBridge(Font.class);
	}

	private LunarClient() {}

	public static boolean present() { return Detection.PRESENT; }

	static boolean hasFontBridge(Class<?> font) {
		try {
			return font.getMethod("bridge$getStringWidth", String.class).getReturnType() == float.class;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			return false;
		}
	}
}
