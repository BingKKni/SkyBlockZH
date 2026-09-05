package io.github.bingkkni.skyzh.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The things about the client's GUI object that moved in 26.2 — the 26.2 side.
 *
 * <p><b>What this package is for.</b> Exactly one copy of each class in {@code platform/} is compiled
 * into a jar: {@code src/mc26_2/} supplies this one, {@code src/mc26_1/} supplies another with the same
 * name and the same methods, and which of the two a target uses is decided in its {@code build.gradle}
 * and nowhere else. Shared code calls these methods and never mentions a version. A class belongs here
 * only when the API it names genuinely differs between targets — everything else, including all the
 * reasoning about <em>why</em> a call is made, stays in {@code src/main} where there is one copy of it.
 * As of 26.1/26.2 that is four methods, and it should not grow without a reason of the same kind.
 *
 * <p>26.2 split {@code Gui} in two: the HUD it used to be became {@code Hud}, reachable as
 * {@code gui.hud}, and what is left owns the screen stack — so {@code setScreen}, which used to be
 * Minecraft's, is now the {@code Gui}'s.
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

		gui.hud.getChat().addClientSystemMessage(message);
	}

	/**
	 * Rebuilds the wrapped chat lines from the English history.
	 *
	 * <p>Chat is translated at wrap time, not when the packet arrives. Holding X therefore does
	 * nothing to already-wrapped lines unless this is called; vanilla already does the same rebuild
	 * on a resize.
	 */
	public static void rescaleChat(Minecraft minecraft) {
		Gui gui = minecraft.gui;

		if (gui == null) {
			return;
		}

		gui.hud.getChat().rescaleChat();
	}

	/** Shows a screen, or closes the current one when handed {@code null}. */
	public static void setScreen(Minecraft minecraft, Screen screen) {
		minecraft.gui.setScreen(screen);
	}

	/** The screen currently covering the world, or {@code null} when none is. */
	public static Screen screen(Minecraft minecraft) {
		Gui gui = minecraft.gui;

		return gui == null ? null : gui.screen();
	}
}
