package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

/**
 * The non-optional server boundary shared by rendering and capture.
 *
 * <p>Read the live connection at each entry, not a flag left over from the last client tick. A
 * disconnect or a switch to another server must close the boundary before that server's first text
 * arrives. A scoreboard or server brand alone is not evidence of being on Hypixel.
 */
public final class HypixelServer {
	private static boolean lastConnected;

	private HypixelServer() {
	}

	public static boolean isConnected() {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null || minecraft.hasSingleplayerServer()) {
			return false;
		}

		ClientPacketListener connection = minecraft.getConnection();
		ServerData server = minecraft.getCurrentServer();

		return connection != null && connection.getConnection().isConnected()
			&& server != null && matchesAddress(server.ip, "hypixel.net");
	}

	/** Exact domain or a subdomain, never a lookalike such as nothypixel.net or hypixel.net.example. */
	public static boolean matchesAddress(String address, String domain) {
		if (address == null || domain == null || domain.isBlank()) {
			return false;
		}

		String host = address.trim().toLowerCase(Locale.ROOT);
		String expected = domain.trim().toLowerCase(Locale.ROOT);
		int colon = host.indexOf(':');

		if (colon >= 0) {
			String port = host.substring(colon + 1);

			if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
				return false;
			}

			host = host.substring(0, colon);
		}

		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}

		return host.equals(expected) || host.endsWith('.' + expected);
	}

	/** Checked before render caches, term-table fallbacks, wrapping or centring can run. */
	public static boolean canTranslate() {
		return isConnected() && SkyZHConfig.get().enabled && !HoldOriginal.active();
	}

	/** Chat stores wrapped lines; rebuild those too when entering or leaving the allowed server. */
	public static void tick(Minecraft minecraft) {
		boolean connected = isConnected();

		if (connected != lastConnected) {
			lastConnected = connected;
			SkyZHConfig.bumpGeneration();
			ClientGui.rescaleChat(minecraft);
		}
	}
}
