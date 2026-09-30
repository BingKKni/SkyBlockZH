package io.github.bingkkni.skyzh.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLMouse;

/** Exercises SDL button numbering without loading a native library or opening a window. */
public final class InputHarness {
	private InputHarness() {
	}

	public static void check(KeyMapping binding) {
		String[] names = {"left", "middle", "right", "4", "5"};
		int[] masks = {SDLMouse.SDL_BUTTON_LMASK, SDLMouse.SDL_BUTTON_MMASK,
			SDLMouse.SDL_BUTTON_RMASK, SDLMouse.SDL_BUTTON_X1MASK, SDLMouse.SDL_BUTTON_X2MASK};
		for (int pressed = 0; pressed < names.length; pressed++) {
			for (int bound = 0; bound < names.length; bound++) {
				int button = InputConstants.getKey("key.mouse." + names[bound]).getValue();
				expect(ClientInput.buttonDown(masks[pressed], button), pressed == bound);
				expect(ClientInput.buttonDown(0, button), false);
			}
		}
		expect(ClientInput.buttonDown(SDLMouse.SDL_BUTTON_LMASK | SDLMouse.SDL_BUTTON_X1MASK,
			InputConstants.MOUSE_BUTTON_4), true);
		expect(ClientInput.buttonDown(Integer.MIN_VALUE, 32), true);
		for (int invalid : new int[] {-1, 0, 33, 64}) {
			expect(ClientInput.buttonDown(-1, invalid), false);
		}

		// 26.3 keys are physical SDL scancodes; keycode is no longer a fallback binding type.
		binding.setKey(InputConstants.getKey("key.keyboard.x"));
		expect(binding.matches(new KeyEvent(InputConstants.KEY_X, InputConstants.KEYCODE_Y, 0)), true);
		expect(binding.matches(new KeyEvent(InputConstants.KEY_Y, InputConstants.KEYCODE_X, 0)), false);
		System.out.println("SDL 鼠标主键/侧键、组合状态、编号边界和物理键绑定通过");
	}

	private static void expect(boolean actual, boolean expected) {
		if (actual != expected) throw new AssertionError("SDL 输入状态或键位不匹配");
	}
}
