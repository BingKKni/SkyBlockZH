package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.compat.HypixelApi;
import io.github.bingkkni.skyzh.text.StyledText;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether capture should be running at all, and the two facts everything captured is filed under:
 * which server this is, and where on it the player is standing.
 *
 * <p>Both guards exist for the same reason. Capture writes files to somebody's disk and produces data
 * meant to end up in {@code original_text/}, so text from a survival world, a minigame lobby or
 * another server is not merely useless, it is contamination that nobody could spot afterwards — it
 * looks exactly like SkyBlock text in the output. The mod would rather record nothing than record
 * something it cannot vouch for, so both the address and the sidebar have to agree that this is
 * Hypixel SkyBlock before a single line is kept.
 *
 * <p>The sidebar is read rather than any mod's idea of the current island: it is the server's own
 * state, it is present on every SkyBlock profile, and reading it costs nothing because it is a field
 * on a {@code Scoreboard} the client already keeps.
 */
public final class CaptureContext {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

	/** The mark SkyBlock puts in front of the zone name on the sidebar's location row. */
	private static final char ZONE_MARK = '⏣';

	/** The same mark as the server's icon font draws it. */
	private static final char ZONE_GLYPH = '\uE067';

	/**
	 * What SkyBlock writes on the location row while it does not know where the player is.
	 *
	 * <p>Seen on the first sidebar after a join, before the island has settled. It is a place name in
	 * the same position as any other and it is the server saying nothing, so it is read as nothing:
	 * treating it as an area would file the whole of a join — the inventory, the welcome messages, the
	 * first tab list — under a place called None.
	 */
	private static final String NOWHERE = "None";

	/** The Rift uses a mark of its own in the same position. */
	private static final char RIFT_MARK = '\u0444';

	/**
	 * What the tab list writes in front of the island's name, which is where the place is read from
	 * when the sidebar will not say.
	 */
	private static final String[] PLACE_LABELS = {"Area:", "Dungeon:"};

	/**
	 * How many refreshes the sidebar may go without naming a place before it is dumped to the log.
	 *
	 * <p>Five seconds at one refresh a tick. Long enough that an ordinary join passes in silence —
	 * the location row is missing at first and then says {@code None} for a while, which is the same
	 * thing — and short enough that somebody who turned capture on and went to look at the output is
	 * told why it is empty before they give up on the feature.
	 */
	private static final int PATIENCE = 100;

	private static volatile boolean onSkyBlock;
	private static volatile String area = "";
	/** Consecutive refreshes on SkyBlock with no location row. Reset the moment one is read. */
	private static int unnamed;
	/** Whether the sidebar has already been spelled out this session. Once is a diagnosis; twice is spam. */
	private static boolean dumped;
	private static int containerId = -1;
	private static String containerTitle = "";
	/** How many slots of the open container are its own, or {@code -1} before its contents arrived. */
	private static int containerSlots = -1;

	private CaptureContext() {
	}

	/**
	 * Whether a line offered right now should be kept.
	 *
	 * <p>Checked at every capture point rather than once, because all three inputs change under the
	 * player: the switch can be turned off in Mod Menu mid-session, a server hop leaves SkyBlock, and
	 * warping to a lobby empties the sidebar.
	 */
	public static boolean active() {
		return SkyZHConfig.get().captureUntranslated && onSkyBlock;
	}

	/** The zone the sidebar last reported, or an empty string when it has not said. */
	public static String area() {
		return area;
	}

	/**
	 * Whether the sidebar has said where the player is standing.
	 *
	 * <p>Asked before anything is filed. "On SkyBlock" and "somewhere in particular on SkyBlock" are
	 * different facts and they arrive at different times: the sidebar's title is one packet and its
	 * location row is assembled from several more, so there is a window on every warp where the first
	 * is true and the second is not yet. Text captured in that window is held rather than filed —
	 * see {@link Unplaced}.
	 */
	public static boolean located() {
		return onSkyBlock && !area.isEmpty();
	}

	/**
	 * The gameplay category the current zone belongs to — the top folder a capture is filed under.
	 *
	 * <p>{@link #area} is already the best of the three readings the table has an answer for, so this
	 * is a plain lookup — see {@link #whereabouts}.
	 */
	public static String gameplay() {
		return Areas.get().gameplay(area);
	}

	/**
	 * Reads the sidebar. Called once a tick, from the same place the sidebar watcher runs.
	 *
	 * <p>Deliberately not cached against the scoreboard object: SkyBlock rewrites the sidebar
	 * constantly, and re-reading two strings a tick is cheaper than working out whether it changed.
	 *
	 * <p>Once a tick and not once every ten, which is what the watcher's own timer runs at. Capturing
	 * the sidebar and the tab list is worth throttling — nothing on either appears for less than half
	 * a second. Knowing where the player is standing is not: it is the label on everything captured in
	 * between, and half a second of a warp's arrival chatter is a great deal of text to mislabel.
	 */
	public static void refresh() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null || !isExpectedServer(minecraft)) {
			onSkyBlock = false;
			area = "";
			return;
		}

		Scoreboard scoreboard = level.getScoreboard();
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

		if (sidebar == null) {
			onSkyBlock = false;
			area = "";
			return;
		}

		onSkyBlock = isSkyBlockTitle(sidebar.getDisplayName().getString());

		if (!onSkyBlock) {
			area = "";
			return;
		}

		area = whereabouts(minecraft, scoreboard, sidebar);

		if (!area.isEmpty()) {
			unnamed = 0;
			return;
		}

		// The sidebar says SkyBlock but not where. For a moment after a warp that is ordinary; for
		// longer than that it is the one failure this feature cannot diagnose from its own output —
		// a folder full of _Unknown_Gameplay looks the same whether the location row was missing, was
		// marked with a character this code does not know, or was read and came out mangled. So the
		// rows are printed exactly as they arrived, with anything invisible spelled out.
		if (++unnamed == PATIENCE && !dumped) {
			dumped = true;
			dump(scoreboard, sidebar);
		}
	}

	/**
	 * Where the player is, out of the three surfaces that say so, preferring a reading the table can
	 * file text under.
	 *
	 * <p>The sidebar's location row is still asked first and still wins whenever the table knows it:
	 * it names the corner of the island rather than the island, so Fairy Grotto is not merely Crystal
	 * Hollows. But a corner nobody has classified is a <em>worse</em> answer than the island it sits
	 * in, and preferring it unconditionally is what filed a whole evening of the Hub under
	 * {@code _Unknown_Gameplay}: the sidebar says {@code Forest} or {@code Combat Settlement}
	 * depending on where the player happens to be standing, and every menu opened while standing
	 * there went with it. Reading on costs nothing — both remaining readings are already sitting in
	 * memory — and turns "this text is unfiled" into "this text is filed, and the table has a line
	 * missing", which is a much smaller problem and one that {@link Areas#unclassified} still reports.
	 *
	 * <p>When no reading is in the table the sidebar's is the one handed back, because it names the
	 * place the player is actually standing and is therefore the line somebody would add.
	 */
	private static String whereabouts(Minecraft minecraft, Scoreboard scoreboard, Objective sidebar) {
		Areas table = Areas.get();
		String zone = readZone(scoreboard, sidebar);

		if (table.knows(zone)) {
			return zone;
		}

		// Hypixel's own answer, when the player has the mod that carries the API. Structured, sent
		// the moment the server changes rather than assembled row by row afterwards, and therefore
		// the reading most likely to be right in exactly the window where the sidebar is not.
		String island = fromModApi();

		if (table.knows(island)) {
			table.unclassified(zone, table.gameplay(island));
			return island;
		}

		// Third reading of the same fact, from a surface that is not rebuilt on the same schedule.
		// The tab list carries the island under a plain label, which makes it a poorer answer than the
		// sidebar's corner and a steadier one. Walking eighty rows a tick is only paid while standing
		// somewhere no table knows, by somebody who has turned capture on — which is exactly the
		// person the log line below is written for.
		String listed = readTabArea(minecraft);

		if (table.knows(listed)) {
			table.unclassified(zone, table.gameplay(listed));
			return listed;
		}

		if (!zone.isEmpty()) {
			return zone;
		}

		return island.isEmpty() ? listed : island;
	}

	/**
	 * The island Hypixel's Mod API last reported, by whichever of its two names the table knows.
	 *
	 * <p>The id is tried as well as the name because they fail differently: a name is what SkyBlock
	 * calls the island this month, and an id is what it has always called it. Neither is preferred
	 * outright — the first one the table has an answer for is the one used, and when it has an answer
	 * for neither the name goes through anyway, so the log names the island that needs a line rather
	 * than saying nothing at all.
	 */
	private static String fromModApi() {
		Areas table = Areas.get();
		String map = HypixelApi.map();
		String mode = HypixelApi.mode();

		if (table.knows(map)) {
			return map;
		}

		if (table.knows(mode)) {
			return mode;
		}

		return map.isEmpty() ? mode : map;
	}

	/**
	 * The island the tab list reports, or an empty string when it does not.
	 *
	 * <p>Walked rather than indexed: SkyBlock's tab list is a grid of display names with no structure
	 * behind it, so the row holding the place is found by what it says. Eighty rows once a tick is
	 * nothing next to what the client already does with them every frame, and this only runs at all
	 * while the sidebar is not answering.
	 */
	private static String readTabArea(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();

		if (connection == null) {
			return "";
		}

		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();

			if (name == null) {
				continue;
			}

			String place = tabArea(name.getString());

			if (!place.isEmpty()) {
				return place;
			}
		}

		return "";
	}

	/**
	 * The island name out of one tab-list row, or an empty string when this is not that row.
	 *
	 * <p>Given the raw row for the same reason {@link #zone} is: the label and the name arrive with
	 * {@code §} codes between them, and a reading that leaves them in place matches nothing in
	 * {@code areas.json}. The dungeon floor after the name is dropped there too — {@code Catacombs
	 * (F7)} says which run this is, not which place.
	 */
	public static String tabArea(String row) {
		String plain = plain(row).trim();

		for (String label : PLACE_LABELS) {
			if (!plain.regionMatches(true, 0, label, 0, label.length())) {
				continue;
			}

			return named(plain.substring(label.length()));
		}

		return "";
	}

	/**
	 * The place name out of the rest of a row: trimmed, without the parenthetical after it, and empty
	 * when the server said it does not know.
	 *
	 * <p>A trailing parenthetical is dropped because the dungeon floor in {@code The Catacombs (F7)}
	 * says which run this is, not which place.
	 */
	private static String named(String rest) {
		String place = rest.trim();
		int parenthetical = place.indexOf(" (");

		if (parenthetical > 0) {
			place = place.substring(0, parenthetical).trim();
		}

		return place.equalsIgnoreCase(NOWHERE) ? "" : place;
	}

	/** Spells out the sidebar, once a session, when it will not say where the player is. */
	private static void dump(Scoreboard scoreboard, Objective sidebar) {
		LOGGER.warn(
			"SkyZH 采集：侧边栏上找不到 ⏣ 开头的所在地那一行，这段时间采到的文本只能落进未知玩法目录。"
				+ "下面把这块侧边栏原样打出来（看不见的字符转成了 \\uXXXX），"
				+ "对照着就能看出是这一行没发过来、还是标记换了字符："
		);
		LOGGER.warn("  [标题] {}", LegacyText.escape(sidebar.getDisplayName().getString()));

		int row = 0;

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (entry.isHidden()) {
				continue;
			}

			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			String line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();

			LOGGER.warn("  [第 {} 行] {}", ++row, LegacyText.escape(line));
		}

		if (row == 0) {
			LOGGER.warn("  （这块计分板一行都没有，说明侧边栏的行还没发过来，不是解析的问题。）");
		}

		LOGGER.warn("  Tab 列表里也没有 Area: 那一行。下面是 Tab 列表上带冒号的行，同样是原样打出来的：");

		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		int listed = 0;

		if (connection != null) {
			for (PlayerInfo info : connection.getListedOnlinePlayers()) {
				Component name = info.getTabListDisplayName();
				String line = name == null ? "" : plain(name.getString()).trim();

				if (line.indexOf(':') > 0 && ++listed <= 20) {
					LOGGER.warn("  [Tab] {}", LegacyText.escape(name.getString()));
				}
			}
		}

		if (listed == 0) {
			LOGGER.warn("  （Tab 列表上一行带冒号的都没有。）");
		}

		LOGGER.warn(
			"  Hypixel Mod API 这一路给的是 map=[{}] mode=[{}]。两个都是空,说明没装 hypixel-mod-api"
				+ "、或者这一局还没收到位置事件；有值却还落进未知玩法,就是 areas.json 里缺这一条。",
			HypixelApi.map(), HypixelApi.mode()
		);
	}

	/**
	 * Whether the address the player typed matches the one capture is allowed on.
	 *
	 * <p>Matched on the suffix so {@code mc.hypixel.net} and {@code alpha.hypixel.net} both pass, and
	 * left configurable because a player behind a proxy connects to an address of their own choosing —
	 * emptying the setting turns this guard off and leaves the sidebar as the only check, which is a
	 * decision for whoever is running the proxy, not for this mod to make on their behalf.
	 */
	private static boolean isExpectedServer(Minecraft minecraft) {
		String expected = SkyZHConfig.get().captureServer.trim().toLowerCase(Locale.ROOT);

		if (expected.isEmpty()) {
			return true;
		}

		if (minecraft.hasSingleplayerServer()) {
			return false;
		}

		ServerData server = minecraft.getCurrentServer();

		if (server == null || server.ip == null) {
			return false;
		}

		String host = server.ip.toLowerCase(Locale.ROOT);
		int port = host.indexOf(':');

		if (port >= 0) {
			host = host.substring(0, port);
		}

		return host.equals(expected) || host.endsWith('.' + expected);
	}

	/**
	 * The sidebar's own title, which on SkyBlock always says SKYBLOCK — with a highlight travelling
	 * across the letters, and sometimes CO-OP or GUEST after it. Letters only, so neither the shimmer
	 * nor the suffix matters.
	 */
	public static boolean isSkyBlockTitle(String title) {
		StringBuilder letters = new StringBuilder();
		String plain = plain(title);

		for (int i = 0; i < plain.length(); i++) {
			if (Character.isLetter(plain.charAt(i))) {
				letters.append(Character.toUpperCase(plain.charAt(i)));
			}
		}

		return letters.indexOf("SKYBLOCK") >= 0;
	}

	/** The zone name off the sidebar's {@code ⏣ Dwarven Mines} row. */
	private static String readZone(Scoreboard scoreboard, Objective sidebar) {
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (entry.isHidden()) {
				continue;
			}

			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			String zone = zone(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());

			if (!zone.isEmpty()) {
				return zone;
			}
		}

		return "";
	}

	/**
	 * The place name out of one sidebar row, or an empty string when this is not the location row.
	 *
	 * <p>Split out and given the raw line rather than a tidy one because of the mistake it is here to
	 * stop repeating. A sidebar row assembled from a team's prefix and suffix still has Hypixel's
	 * {@code §} codes sitting inside its literal text — {@code Component#getString} flattens the tree
	 * but does not touch them — so the obvious reading of this row hands back {@code "§7Dwarven Mines"}
	 * and every lookup against it misses. Everything captured then lands in the unknown-gameplay
	 * folder, which is a failure that looks like a missing table rather than like a bug.
	 *
	 * <p>The stripping is {@link StyledText#plainOf}'s and not {@code ChatFormatting}'s, which is the
	 * second half of the same mistake: Hypixel hides a {@code §q} inside the name to keep each sidebar
	 * entry a distinct string, the renderer eats it, and a reading that only knows the codes Minecraft
	 * has names for hands back {@code "Dwarven M§qines"} — which looks exactly like an area nobody has
	 * classified. See {@link StyledText#of}.
	 *
	 * <p>What comes back is trimmed of its parenthetical and empty when the row says {@code None} —
	 * see {@link #named}.
	 */
	public static String zone(String row) {
		String plain = plain(row);
		int mark = -1;

		for (int i = 0; i < plain.length(); i++) {
			char c = plain.charAt(i);

			if (c == ZONE_MARK || c == ZONE_GLYPH || c == RIFT_MARK) {
				mark = i;
				break;
			}
		}

		if (mark < 0) {
			return "";
		}

		return named(plain.substring(mark + 1));
	}

	/**
	 * Text with Hypixel's legacy colour codes taken out, the same reading
	 * {@code StyledText#plain} gives and the same one every lookup is written against.
	 *
	 * <p>Which is why it goes through {@link StyledText#plainOf} rather than
	 * {@code ChatFormatting#stripFormatting}: the two disagree about {@code §} followed by a letter
	 * Minecraft has no name for, the sidebar's location row is full of exactly that, and this method
	 * claiming to be the same reading while quietly being a different one is what filed a session of
	 * Mining text under {@code Dwarven M§qines}.
	 */
	private static String plain(String text) {
		return StyledText.plainOf(text);
	}

	/** How many slots at the end of a container packet are the player's own inventory and hotbar. */
	private static final int PLAYER_SLOTS = 36;

	/** Remembers the title the server opened a container with, so its items can be filed under it. */
	public static void openScreen(int id, Component title) {
		containerId = id;
		containerSlots = -1;
		// Stripped here rather than at each use: this string becomes both a file name and the note
		// saying where an item was seen, and a stray §9 on the front of either is noise in the output.
		containerTitle = plain(title.getString()).trim();
	}

	/**
	 * Remembers how wide the open container is, from the packet that fills it.
	 *
	 * <p>Every container's contents packet ends with the player's own thirty-six slots, so the size
	 * of the packet is the only place the boundary between the menu and the backpack is written down.
	 * It is not in the packet a single slot arrives in, which is why it has to be kept here.
	 */
	public static void contents(int id, int size) {
		if (id == containerId && size > PLAYER_SLOTS) {
			containerSlots = size - PLAYER_SLOTS;
		}
	}

	/**
	 * The menu a container's items belong to.
	 *
	 * <p>Container 0 is the player's own inventory, which the server sends unprompted and never names;
	 * an id that does not match the last screen the server opened is in the same position. Both get
	 * the same answer, which is what {@code _Inventory} means: text that arrived without a menu around
	 * it.
	 *
	 * <p>So does the tail of every other container, and that is the part worth spelling out. A chest
	 * menu's contents packet carries the player's backpack after the menu's own slots, so a session
	 * that opened sixty menus filed the same pickaxe, the same drill and the same wardrobe under all
	 * sixty of them — more than half of everything captured, in files named after menus those items
	 * were never in. The boundary is {@link #contents}'s to know; here it only has to be applied.
	 */
	public static String menu(int id, int slot) {
		if (id == 0 || id != containerId || containerTitle.isEmpty()) {
			return "";
		}

		return containerSlots >= 0 && slot >= containerSlots ? "" : containerTitle;
	}

	/** Forgets everything remembered about a session. Called when the connection goes away. */
	public static void reset() {
		HypixelApi.forget();
		onSkyBlock = false;
		area = "";
		unnamed = 0;
		dumped = false;
		containerId = -1;
		containerTitle = "";
		containerSlots = -1;
	}
}
