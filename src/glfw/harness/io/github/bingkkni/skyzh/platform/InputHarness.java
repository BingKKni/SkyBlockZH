package io.github.bingkkni.skyzh.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

/** The legacy keyboard backend still supports a separate unrecognised-key scan code. */
public final class InputHarness {
	private InputHarness() {
	}

	public static void check(KeyMapping binding) {
		binding.setKey(InputConstants.Type.SCANCODE.getOrCreate(123));
		if (!binding.matches(new KeyEvent(-1, 123, 0))) {
			throw new AssertionError("无符号键的扫描码绑定失效");
		}
		System.out.println("GLFW 扫描码绑定通过");
	}
}
