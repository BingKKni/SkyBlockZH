package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.platform.ClientGui;
import io.github.bingkkni.skyzh.text.StyledText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The non-optional server boundary shared by rendering and capture.
 *
 * <p>An address is not identity. Chinese players commonly reach Hypixel through an accelerator whose
 * address is an IP or a local relay, so comparing {@code ServerData.ip} to {@code hypixel.net} turns a
 * valid connection into a silent false negative. Instead, this class combines two facts received from
 * the server itself:
 *
 * <ol>
 *   <li>Hypixel sends the official {@code hypixel:hello} custom payload on every join.</li>
 *   <li>SkyBlock exposes an exact {@code SKYBLOCK} sidebar while that game is active.</li>
 * </ol>
 *
 * <p>Both are required. A random server with a line that happens to match the corpus has neither; a
 * SkyBlock-style server may copy the sidebar but does not accidentally send Hypixel's protocol hello;
 * and another Hypixel game receives the hello but does not have the SkyBlock sidebar. A malicious
 * server can impersonate any unauthenticated game protocol, but accidental translation is fail-closed
 * without rejecting accelerators, direct IPs or local relays.
 */
public final class HypixelServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String HELLO_PAYLOAD = "hypixel:hello";
	private static final int WARNING_DELAY_TICKS = 100;

	/** The exact network session the evidence below belongs to. Never carry it across reconnects. */
	private static volatile Connection sessionConnection;
	private static volatile boolean receivedHello;
	private static volatile boolean skyBlock;

	/** The state for which wrapped chat was last built. Kept separate so a connection reset is observed. */
	private static boolean displayedSkyBlock;
	private static int unverifiedSidebarTicks;
	private static boolean warnedUnverifiedSidebar;

	private HypixelServer() {
	}

	/**
	 * Observes one inbound custom payload without reading or changing its body.
	 *
	 * <p>The mixin calls this before vanilla/Fabric dispatches the payload. It works whether
	 * {@code hypixel-mod-api} is installed and recognises the typed payload or vanilla represents it as
	 * an unknown payload, because the identifier is present in both cases. Merely reading a packet the
	 * server already sent does not register an event or send anything back.
	 */
	public static void observePayload(Connection source, Identifier identifier) {
		if (source == null || identifier == null || !isHypixelHello(identifier.toString())) {
			return;
		}

		boolean announce = source != sessionConnection || !receivedHello;

		if (source != sessionConnection) {
			resetFor(source);
		} else {
			// Hypixel sends another hello when the proxy moves this connection to another backend.
			// Close the old game's half of the gate immediately; the next tick must see a current
			// SkyBlock sidebar before rendering can resume.
			skyBlock = false;
			unverifiedSidebarTicks = 0;
			warnedUnverifiedSidebar = false;
		}

		receivedHello = true;

		if (announce) {
			LOGGER.info("SkyZH 已通过 hypixel:hello 确认当前连接属于 Hypixel；连接地址不参与判断。");
		}
	}

	/** Pure identifier check, exposed for the no-client regression harness. */
	public static boolean isHypixelHello(String identifier) {
		return HELLO_PAYLOAD.equals(identifier);
	}

	/** Whether the live multiplayer connection has supplied Hypixel's own identity payload. */
	public static boolean isHypixel() {
		Minecraft minecraft = Minecraft.getInstance();
		return syncConnection(minecraft) && receivedHello;
	}

	/** Whether this exact live connection is both Hypixel and currently in SkyBlock. */
	public static boolean isSkyBlock() {
		Minecraft minecraft = Minecraft.getInstance();
		return syncConnection(minecraft) && receivedHello && skyBlock;
	}

	/** Checked before render caches, term-table fallbacks, wrapping or centring can run. */
	public static boolean canTranslate() {
		return isSkyBlock() && SkyZHConfig.get().enabled && !HoldOriginal.active();
	}

	/**
	 * Refreshes the SkyBlock half of the boundary and rebuilds wrapped chat when it changes.
	 *
	 * <p>The sidebar is deliberately live state, not a sticky "seen once" flag. Leaving SkyBlock for a
	 * Hypixel lobby closes the gate on the next tick; changing to another connection clears the hello
	 * as well. That prevents either half of an old decision leaking into the next server.
	 */
	public static void tick(Minecraft minecraft) {
		boolean connected = syncConnection(minecraft);
		boolean sidebar = connected && hasSkyBlockSidebar(minecraft);

		if (sidebar && !receivedHello) {
			if (++unverifiedSidebarTicks == WARNING_DELAY_TICKS && !warnedUnverifiedSidebar) {
				warnedUnverifiedSidebar = true;
				LOGGER.warn(
					"SkyZH 检测到 SKYBLOCK 侧边栏，但尚未收到 Hypixel 的 hypixel:hello。"
						+ "为避免在其他服务器误译，本次连接暂不启用翻译；服务器地址不会用于兜底判断。"
				);
			}
		} else {
			unverifiedSidebarTicks = 0;
		}

		skyBlock = connected && receivedHello && sidebar;

		if (skyBlock != displayedSkyBlock) {
			displayedSkyBlock = skyBlock;
			SkyZHConfig.bumpGeneration();
			ClientGui.rescaleChat(minecraft);
		}
	}

	/**
	 * The exact sidebar titles Hypixel SkyBlock uses, ignoring colours, punctuation and its icon.
	 *
	 * <p>Exact accepted forms are intentional. A substring check would admit titles such as
	 * {@code MY SKYBLOCK SERVER}; the official hello still protects the boundary, but there is no
	 * reason to weaken its independent second half.
	 */
	public static boolean isSkyBlockTitle(String title) {
		String plain = StyledText.plainOf(title == null ? "" : title);
		StringBuilder letters = new StringBuilder();

		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);

			if (c >= 'a' && c <= 'z') {
				letters.append((char) (c - ('a' - 'A')));
			} else if (c >= 'A' && c <= 'Z') {
				letters.append(c);
			}
		}

		return switch (letters.toString()) {
			case "SKYBLOCK", "SKYBLOCKCOOP", "SKYBLOCKGUEST" -> true;
			default -> false;
		};
	}

	private static boolean hasSkyBlockSidebar(Minecraft minecraft) {
		ClientLevel level = minecraft.level;

		if (level == null) {
			return false;
		}

		Objective sidebar = level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		return sidebar != null && isSkyBlockTitle(sidebar.getDisplayName().getString());
	}

	/** Makes all remembered evidence belong to the current live connection, or to none. */
	private static boolean syncConnection(Minecraft minecraft) {
		Connection current = liveConnection(minecraft);

		if (current != sessionConnection) {
			resetFor(current);
		}

		return current != null;
	}

	private static Connection liveConnection(Minecraft minecraft) {
		if (minecraft == null || minecraft.hasSingleplayerServer()) {
			return null;
		}

		ClientPacketListener listener = minecraft.getConnection();

		if (listener == null) {
			return null;
		}

		Connection connection = listener.getConnection();
		return connection != null && connection.isConnected() ? connection : null;
	}

	private static void resetFor(Connection connection) {
		sessionConnection = connection;
		receivedHello = false;
		skyBlock = false;
		unverifiedSidebarTicks = 0;
		warnedUnverifiedSidebar = false;
	}
}
