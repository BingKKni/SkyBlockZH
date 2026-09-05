package io.github.bingkkni.skyzh;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Hold the configurable original-text key (default {@code X}) without touching the master switch.
 *
 * <p>Mod Menu's enabled toggle is how a player turns the whole mod off. Capture is a separate
 * switch, but "off" still reads as "the mod is not running". Holding a key must not write that.
 * This flag is consulted only on the render path, so {@code locate} keeps answering for capture
 * and {@code captureUntranslated} stays whatever it was.
 *
 * <p>A normal vanilla KeyMapping, appended to Options before options.txt is read. Vanilla owns
 * rebinding, conflict display, reset and NONE; no separate key setting or Fabric API is needed.
 * The configured physical key is polled because vanilla releases gameplay mappings while a GUI is
 * open, which is precisely where players compare item lore. Text entry is still left alone.
 */
public final class HoldOriginal {
	private static volatile boolean held;
	private static KeyMapping binding;
	private static InputConstants.Key heldScanCode;

	private HoldOriginal() {
	}

	/** Called from Options.load HEAD, including the constructor's first load. Idempotent on reload. */
	public static KeyMapping[] register(KeyMapping[] mappings) {
		if (binding == null) {
			binding = new KeyMapping("key.skyzh.holdOriginal", InputConstants.KEY_X,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SkyZH.MOD_ID, "main")));
		}

		for (KeyMapping mapping : mappings) {
			if (mapping == binding) {
				return mappings;
			}
		}

		KeyMapping[] result = Arrays.copyOf(mappings, mappings.length + 1);
		result[mappings.length] = binding;
		return result;
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

	/** Reads the assigned key once per client tick and rebuilds wrapped chat if it flipped. */
	public static void poll(Minecraft minecraft) {
		boolean allowed = HypixelServer.isConnected() && minecraft.isWindowActive() && !typing(minecraft);

		if (!allowed) {
			heldScanCode = null;
		}

		boolean next = allowed && keyDown(minecraft);

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

	/** GLFW cannot poll unknown-key scancodes, so those use vanilla's key-event matching instead. */
	public static void keyEvent(long window, int action, KeyEvent event) {
		Minecraft minecraft = Minecraft.getInstance();

		if (binding != null && minecraft != null && window == minecraft.getWindow().handle()
			&& binding.matches(event) && configuredKey().getType() == InputConstants.Type.SCANCODE) {
			heldScanCode = action == GLFW.GLFW_RELEASE ? null : configuredKey();
		}
	}

	private static InputConstants.Key configuredKey() {
		// This is vanilla's saved key name, not a second preference. It changes immediately on rebind.
		return InputConstants.getKey(binding.saveString());
	}

	private static boolean keyDown(Minecraft minecraft) {
		if (binding == null || binding.isUnbound()) {
			return false;
		}

		InputConstants.Key key = configuredKey();
		return switch (key.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(minecraft.getWindow(), key.getValue());
			case MOUSE -> GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
			case SCANCODE -> key.equals(heldScanCode);
		};
	}
}
