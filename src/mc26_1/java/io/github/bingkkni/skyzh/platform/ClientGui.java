package io.github.bingkkni.skyzh.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The two things about the client's GUI object that moved in 26.2 — the 26.1.x side.
 *
 * <p><b>What this package is for.</b> Exactly one copy of each class in {@code platform/} is compiled
 * into a jar: {@code src/mc26_1/} supplies this one, {@code src/mc26_2/} supplies another with the same
 * name and the same methods, and which of the two a target uses is decided in its {@code build.gradle}
 * and nowhere else. Shared code calls these methods and never mentions a version. A class belongs here
 * only when the API it names genuinely differs between targets — everything else, including all the
 * reasoning about <em>why</em> a call is made, stays in {@code src/main} where there is one copy of it.
 * As of 26.1/26.2 that is two methods, and it should not grow without a reason of the same kind.
 *
 * <p>In 26.1.x {@code Gui} <em>is</em> the HUD: it owns the chat component, and the screen stack is
 * Minecraft's own. 26.2 split those apart — the HUD became {@code Hud}, reachable as {@code gui.hud},
 * and {@code setScreen} moved off {@code Minecraft} onto {@code Gui}.
 */
public final class ClientGui {
	private ClientGui() {
	}

	/**
	 * Adds a line to the chat box as a <em>client</em> system message, if there is a HUD to add it to.
	 *
	 * <p>Client rather than server because that is what these lines are: they never came from Hypixel
	 * and must not be filed as though they had. Callers are responsible for being on the client thread
	 * — see the {@code execute} in {@code Feedback#raw} and {@code CaptureAnnouncer#send}, which have
	 * their own reasons to be there.
	 *
	 * <p>A null HUD is not an error. It is the state during startup and teardown, and a message that
	 * arrives then is one nobody could have read anyway.
	 */
	public static void chat(Minecraft minecraft, Component message) {
		Gui gui = minecraft.gui;

		if (gui == null) {
			return;
		}

		gui.getChat().addClientSystemMessage(message);
	}

	/** Shows a screen, or closes the current one when handed {@code null}. */
	public static void setScreen(Minecraft minecraft, Screen screen) {
		minecraft.setScreen(screen);
	}
}
