package io.github.bingkkni.skyzh.compat;

import io.github.bingkkni.skyzh.SkyZHConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the player is, asked of Hypixel instead of read off the screen.
 *
 * <p>Everything captured is filed under the gameplay the text came from, and that is worked out from
 * the place the player was standing. Reading the place off the sidebar works and is what runs when
 * this is not available, but it is a reading of something drawn for a human: it depends on a marker
 * character, on a row assembled out of a team's prefix and suffix, and on the sidebar having been
 * rebuilt after a warp. Hypixel's own Mod API answers the same question in a structured packet, sent
 * the moment the player changes server, naming the island as {@code map} and its type as
 * {@code mode}.
 *
 * <p><b>Nothing here is reimplemented.</b> The protocol, the handshake and the plumbing into
 * Minecraft's networking all live in the {@code hypixel-mod-api} mod and the {@code net.hypixel:mod-api}
 * library it carries, which is compiled against and never bundled: a player who does not have that
 * mod loses this reading and keeps the other two, and a player who does — anyone already running
 * SkyHanni or SkyBlocker, which is most SkyBlock players — gets it for free. That is the whole of the
 * integration, some forty lines, and it is deliberately not more.
 *
 * <p><b>It is tied to the capture switch, and that is not tidiness.</b> Subscribing to an event is
 * the one thing in this mod that <em>sends</em> something to Hypixel — a register packet naming the
 * events wanted — and "a client running SkyZH is, as far as the server is concerned, a client running
 * in English" is a promise the translation half of the mod keeps completely. So the subscription
 * happens only for somebody who has turned on the capture that writes corpus files, which is already
 * the switch that means "I am filling in the corpus, not playing".
 */
public final class HypixelApi {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

	/** The Fabric mod that carries the library and wires it into the game's networking. */
	private static final String MOD_ID = "hypixel-mod-api";

	private static volatile String map = "";
	private static volatile String mode = "";
	private static boolean installed;

	private HypixelApi() {
	}

	/**
	 * Subscribes to the location event, once, if there is anything to subscribe through.
	 *
	 * <p>Called both at startup and when capture first runs, because the switch can be turned on in
	 * Mod Menu in the middle of a session — the library sends the register packet straight away when
	 * that happens, and on every join afterwards.
	 */
	public static synchronized void install() {
		if (installed || !SkyZHConfig.get().captureUntranslated) {
			return;
		}

		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			return;
		}

		try {
			Wiring.subscribe();
			installed = true;

			LOGGER.info(
				"SkyZH 采集：已通过 {} 订阅 Hypixel 的位置事件，玩法分类不再只靠读侧边栏。"
					+ "（这是采集功能唯一一处会向服务器发包的地方，关掉采集就不会发。）",
				MOD_ID
			);
		} catch (Throwable e) {
			// A version of the library that moved something, or a mod list that says one thing and
			// loads another. The sidebar reading is still there and still correct, so this is a note.
			LOGGER.info("SkyZH 采集：接 {} 没接上（{}），玩法分类照旧读侧边栏。", MOD_ID, e.toString());
		}
	}

	/** The island name Hypixel last reported, such as {@code Dwarven Mines}. */
	public static String map() {
		return map;
	}

	/** The island type Hypixel last reported, such as {@code mining_3}. Steadier than the name. */
	public static String mode() {
		return mode;
	}

	/** Forgets the last location. Called when the connection goes away. */
	public static void forget() {
		map = "";
		mode = "";
	}

	/**
	 * The only code in the mod that names a class from the library, kept in a nested class so that
	 * loading it is a separate event from loading {@link HypixelApi}.
	 *
	 * <p>Not a style choice. {@link #forget()} runs on every disconnect whether or not the library is
	 * there, and a class is verified as a whole when it is first loaded — putting a method that
	 * mentions {@code ClientboundLocationPacket} beside it risks turning a missing optional dependency
	 * into a {@code NoClassDefFoundError} on an ordinary player's client. Nothing in here is reached
	 * until {@link #install()} has already checked that the mod is loaded, inside a {@code try}.
	 */
	private static final class Wiring {
		private Wiring() {
		}

		static void subscribe() {
			net.hypixel.modapi.HypixelModAPI api = net.hypixel.modapi.HypixelModAPI.getInstance();

			api.createHandler(
				net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket.class,
				packet -> {
					map = packet.getMap().orElse("");
					mode = packet.getMode().orElse("");
				}
			);
			api.subscribeToEventPacket(
				net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket.class
			);
		}
	}
}
