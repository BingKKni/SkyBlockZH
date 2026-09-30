package io.github.bingkkni.skyzh.platform;

import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;

/** 26.3 uses SDL's one-based button IDs and a bit mask instead of GLFW's per-window query. */
public final class ClientInput {
	private ClientInput() {
	}

	/** Null outside the game's focus: SDL may report no buttons even while one remains held. */
	public static Boolean mouseDown(Minecraft minecraft, int button) {
		if (!minecraft.isWindowActive() || SDLMouse.SDL_GetMouseFocus() != minecraft.getWindow().handle()) {
			return null;
		}
		return buttonDown(SDLMouse.SDL_GetMouseState((FloatBuffer) null, null), button);
	}

	static boolean buttonDown(int state, int button) {
		// SDL_MouseButtonFlags is a 32-bit mask. Validate before shifting: Java masks shift distances.
		return button >= 1 && button <= 32 && (state & (1 << (button - 1))) != 0;
	}
}
