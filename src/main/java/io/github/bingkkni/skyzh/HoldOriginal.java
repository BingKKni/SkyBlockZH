package io.github.bingkkni.skyzh;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Session-only original-text toggle. The class and vanilla mapping ID retain their old names so
 * existing options.txt bindings survive. This never changes the master/capture switches or saves.
 * Toggle before opening chat to inspect history without stealing X from the text input.
 */
public final class HoldOriginal {
	private static volatile boolean active;
	private static KeyMapping binding;
	private static boolean mouseDown;

	private HoldOriginal() {
	}

	/** Appended before options.txt is read, idempotently; vanilla owns rebinding and NONE. */
	public static KeyMapping[] register(KeyMapping[] mappings) {
		if (binding == null) {
			binding = new KeyMapping("key.skyzh.holdOriginal", InputConstants.KEY_X,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SkyZH.MOD_ID, "main")));
		}
		for (KeyMapping mapping : mappings) {
			if (mapping == binding) return mappings;
		}
		KeyMapping[] result = Arrays.copyOf(mappings, mappings.length + 1);
		result[mappings.length] = binding;
		return result;
	}

	public static boolean active() {
		return active;
	}

	/** Bypass gates precede caches; changing display mode must not discard reusable translations. */
	public static void setActive(boolean value) {
		active = value;
	}

	/** Keyboard events catch even short taps; mouse bindings use physical rising edges in GUIs. */
	public static void poll(Minecraft minecraft) {
		boolean bound = binding != null && !binding.isUnbound();
		boolean available = HypixelServer.isSkyBlock() && SkyZHConfig.get().enabled && bound;
		boolean down = bound && configuredKey().getType() == InputConstants.Type.MOUSE
			&& GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), configuredKey().getValue()) == GLFW.GLFW_PRESS;
		updateMouse(available, minecraft.isWindowActive() && !typing(minecraft), down);
	}

	/** Pure edge/state handling, also exercised without a GLFW window. */
	static void updateMouse(boolean available, boolean acceptsPress, boolean down) {
		if (!available) active = false;
		if (available && acceptsPress && down && !mouseDown) active = !active;
		// Track rejected presses too: leaving an input box while holding a button is not a new tap.
		mouseDown = down;
	}

	static void press(boolean available, boolean acceptsPress, int action) {
		if (available && acceptsPress && action == GLFW.GLFW_PRESS) active = !active;
	}

	private static boolean typing(Minecraft minecraft) {
		Screen screen = ClientGui.screen(minecraft);
		if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen
			|| screen instanceof BookEditScreen || screen instanceof KeyBindsScreen) return true;
		GuiEventListener focused = screen;
		// Lists and mod screens may wrap the edit box in another focus container.
		for (int depth = 0; focused != null && depth < 16; depth++) {
			if (focused instanceof EditBox) return true;
			if (!(focused instanceof ContainerEventHandler container)) break;
			GuiEventListener next = container.getFocused();
			if (next == focused) break;
			focused = next;
		}
		return false;
	}

	/** Never consumes input; GLFW repeat/release must not toggle, including scan-code bindings. */
	public static void keyEvent(long window, int action, KeyEvent event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (binding != null && !binding.isUnbound() && minecraft != null
			&& window == minecraft.getWindow().handle() && binding.matches(event)) {
			press(HypixelServer.isSkyBlock() && SkyZHConfig.get().enabled,
				minecraft.isWindowActive() && !typing(minecraft), action);
		}
	}

	/** Null when unbound; the display name follows vanilla keyboard and mouse rebinding. */
	public static String keyName() {
		return binding == null || binding.isUnbound() ? null : binding.getTranslatedKeyMessage().getString();
	}

	private static InputConstants.Key configuredKey() {
		return InputConstants.getKey(binding.saveString());
	}
}
