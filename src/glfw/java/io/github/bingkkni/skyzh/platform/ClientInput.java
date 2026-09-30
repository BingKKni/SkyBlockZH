package io.github.bingkkni.skyzh.platform;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Physical mouse state for 26.1/26.2, including buttons bound while a GUI is open. */
public final class ClientInput {
	private ClientInput() {
	}

	/** GLFW releases its cached buttons on focus loss, so that state cannot prove a physical release. */
	public static Boolean mouseDown(Minecraft minecraft, int button) {
		if (!minecraft.isWindowActive()) return null;
		return GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), button) == GLFW.GLFW_PRESS;
	}
}
