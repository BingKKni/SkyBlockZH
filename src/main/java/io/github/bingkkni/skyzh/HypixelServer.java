package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.Locale;
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
 *   <li>Hypixel identifies itself on every join: the official {@code hypixel:hello} custom payload,
 *       and the standard {@code minecraft:brand} payload naming {@code Hypixel BungeeCord}. Either is
 *       accepted — SkyBlocker checks the brand, SkyHanni subscribes to the hello, and each one alone
 *       has gone missing in practice (a mod that registers the hello makes Fabric consume it before
 *       anyone else can look; the brand is only sent once per backend switch).</li>
 *   <li>SkyBlock displays its {@code SBScoreboard} sidebar objective while that game is active.</li>
 * </ol>
 *
 * <p>Both halves are required. A random server with a line that happens to match the corpus has
 * neither; a SkyBlock-style server may copy the sidebar but does not accidentally send Hypixel's
 * protocol hello or brand; and another Hypixel game receives the identity but does not have the
 * SkyBlock sidebar. A malicious server can impersonate any unauthenticated game protocol, but
 * accidental translation is fail-closed without rejecting accelerators, direct IPs or local relays.
 */
public final class HypixelServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String HELLO_PAYLOAD = "hypixel:hello";
	private static final String BRAND_MARKER = "hypixel";
	private static final String SKYBLOCK_OBJECTIVE = "SBScoreboard";
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
	 * <p>The mixin calls this from the packet's own dispatch, before any listener sees it. It works
	 * whether {@code hypixel-mod-api} is installed and recognises the typed payload or vanilla
	 * represents it as an unknown payload, because the identifier is present in both cases. Merely
	 * reading a packet the server already sent does not register an event or send anything back.
	 */
	public static void observePayload(Connection source, Identifier identifier) {
		if (source == null || identifier == null || !isHypixelHello(identifier.toString())) {
			return;
		}

		identify(source, "hypixel:hello");
	}

	/**
	 * Observes the server brand the moment it arrives, rather than reading it back off the listener.
	 *
	 * <p>The brand is sent once per backend, during configuration, before there is a player to read
	 * it through — waiting for {@code ClientPacketListener#serverBrand()} on the tick would work for
	 * the first backend and miss nothing, but the packet path is the same one the hello uses and keeps
	 * the two pieces of identity in one place.
	 */
	public static void observeBrand(Connection source, String brand) {
		if (source == null || !isHypixelBrand(brand)) {
			return;
		}

		identify(source, "服务器品牌 " + brand.trim());
	}

	private static void identify(Connection source, String evidence) {
		boolean announce = source != sessionConnection || !receivedHello;

		if (source != sessionConnection) {
			resetFor(source);
		} else if (receivedHello) {
			// Hypixel identifies itself again when the proxy moves this connection to another backend.
			// Close the old game's half of the gate immediately; the next tick must see a current
			// SkyBlock sidebar before rendering can resume. (The brand and the hello of one join also
			// land here one after the other, which closes a gate that is not open yet — harmless.)
			skyBlock = false;
			unverifiedSidebarTicks = 0;
			warnedUnverifiedSidebar = false;
		}

		receivedHello = true;

		if (announce) {
			LOGGER.info("SkyZH 已通过 {} 确认当前连接属于 Hypixel；连接地址不参与判断。", evidence);
		}
	}

	/** Pure identifier check, exposed for the no-client regression harness. */
	public static boolean isHypixelHello(String identifier) {
		return HELLO_PAYLOAD.equals(identifier);
	}

	/**
	 * Whether a {@code minecraft:brand} names Hypixel. The live value is {@code Hypixel BungeeCord};
	 * the word alone is matched, case-insensitively, so a proxy rename does not silently close the
	 * gate. {@code vanilla}, {@code fabric}, {@code Paper} and friends never contain it.
	 */
	public static boolean isHypixelBrand(String brand) {
		return brand != null && brand.toLowerCase(Locale.ROOT).contains(BRAND_MARKER);
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
					"SkyZH 检测到 SkyBlock 侧边栏（SBScoreboard），但本次连接既没有收到 Hypixel 的 hypixel:hello，"
						+ "服务器品牌也不是 Hypixel。为避免在其他服务器误译，本次连接暂不启用翻译；"
						+ "服务器地址不会用于兜底判断。"
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
	 * The internal name of Hypixel SkyBlock's sidebar objective.
	 *
	 * <p>The objective's <em>name</em>, not its display title. The title is drawn for people — a
	 * highlight travels across the letters, an icon or {@code CO-OP} / {@code GUEST} trails it, and
	 * any of that can change with a restyle; the name is the protocol-level identifier the server
	 * registers the objective under, {@code SBScoreboard}, and is what SkyHanni keys its own
	 * scoreboard events on. Exact and case-sensitive: the official hello or brand still protects the
	 * boundary, but there is no reason to weaken its independent second half.
	 */
	public static boolean isSkyBlockObjective(String objectiveName) {
		return SKYBLOCK_OBJECTIVE.equals(objectiveName);
	}

	/** Whether this objective is Hypixel SkyBlock's sidebar. */
	public static boolean isSkyBlockObjective(Objective objective) {
		return objective != null && isSkyBlockObjective(objective.getName());
	}

	private static boolean hasSkyBlockSidebar(Minecraft minecraft) {
		ClientLevel level = minecraft.level;

		if (level == null) {
			return false;
		}

		return isSkyBlockObjective(level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR));
	}

	/**
	 * Makes all remembered evidence belong to the current live connection, or to none.
	 *
	 * <p>"No player" is not "no connection". The hello and the brand arrive during the configuration
	 * phase — on the first join and again on every backend switch, which Hypixel performs by sending
	 * the same connection back through configuration — and there is no player to read a listener off
	 * until play starts. Evidence for a session whose socket is still open is kept through that gap;
	 * only a closed socket, or a different one, clears it.
	 */
	private static boolean syncConnection(Minecraft minecraft) {
		Connection current = liveConnection(minecraft);

		if (current == null) {
			Connection session = sessionConnection;

			if (session != null && (!session.isConnected() || minecraft == null || minecraft.hasSingleplayerServer())) {
				resetFor(null);
			}

			return false;
		}

		if (current != sessionConnection) {
			resetFor(current);
		}

		return true;
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
