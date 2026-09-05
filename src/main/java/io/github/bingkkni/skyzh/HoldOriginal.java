package io.github.bingkkni.skyzh;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.bingkkni.skyzh.platform.ClientGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Hold {@code X} to see the English the server sent, without touching the master switch.
 *
 * <p>Mod Menu's enabled toggle is how a player turns the whole mod off. Capture is a separate
 * switch, but "off" still reads as "the mod is not running". Holding a key must not write that.
 * This flag is consulted only on the render path, so {@code locate} keeps answering for capture
 * and {@code captureUntranslated} stays whatever it was.
 *
 * <p>The key is polled, not bound. This mod has no Fabric API, so there is no
 * {@code KeyBindingHelper}, and stealing vanilla's key map would fight every other client mod that
 * already bound X. Polling GLFW directly means the key does nothing except while it is down, and
 * nothing is stolen from chat, signs or any other focused text field.
 */
public final class HoldOriginal {
	private static volatile boolean held;

	private HoldOriginal() {
	}

	/** Whether render paths should skip translation this frame. */
	public static boolean active() {
		return held;
	}

	/**
	 * Applies or clears the hold. The harness uses this directly; the client tick uses
	 * {@link #poll}. Generation advances so tooltip and title caches drop the other spelling on the
	 * same frame.
	 */
	public static void setActive(boolean value) {
		if (held == value) {
			return;
		}

		held = value;
		SkyZHConfig.bumpGeneration();
	}

	/** Reads the physical X key once per client tick and rebuilds wrapped chat if it flipped. */
	public static void poll(Minecraft minecraft) {
		boolean next = !typing(minecraft) && keyDown(minecraft);

		if (next == held) {
			return;
		}

		setActive(next);
		ClientGui.rescaleChat(minecraft);
	}

	private static boolean typing(Minecraft minecraft) {
		Screen screen = ClientGui.screen(minecraft);

		if (screen == null) {
			return false;
		}

		if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen) {
			return true;
		}

		return screen.getFocused() instanceof EditBox;
	}

	private static boolean keyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_X);
	}
}
