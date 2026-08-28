package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.SkyZH;
import io.github.bingkkni.skyzh.text.Possessive;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which gameplay category a piece of text belongs to, worked out from where the player was standing.
 *
 * <p>{@code original_text/} is filed by gameplay first — {@code Mining/NPC_Message/Fragilis.json} —
 * so a capture that cannot name the gameplay cannot be filed. The area the sidebar reports is the one
 * signal that is always there, always the server's, and already means what the folder names mean: a
 * line heard in Dwarven Mines is Mining text whoever said it.
 *
 * <p>The table is a resource rather than a switch statement because SkyBlock adds areas faster than
 * this mod is rebuilt, and an area nobody has classified is not an error — it lands in
 * {@code _Unknown_Gameplay}, which is a directory whose existence is the instruction to add a line to
 * {@code areas.json}.
 *
 * <p>One table, three vocabularies. The sidebar names the corner of an island ({@code Fairy Grotto}),
 * the tab list and Hypixel's Mod API name the island ({@code Crystal Hollows}), and the Mod API also
 * has an id for it that no wording change can break ({@code crystal_hollows}). They are all answers
 * to "where is the player", they all come out as the same gameplay folder, and keeping them in one
 * table is what makes the three readings interchangeable at the point of use.
 */
public final class Areas {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String RESOURCE = "assets/skyzh/capture/areas.json";

	private static Areas instance;

	private final Map<String, String> byArea;
	private final Map<String, String> folded;
	private final String unknown;
	/** Areas already complained about, so an unclassified place costs one log line and not one a tick. */
	private final Set<String> reported = ConcurrentHashMap.newKeySet();

	private Areas(Map<String, String> byArea, Map<String, String> folded, String unknown) {
		this.byArea = byArea;
		this.folded = folded;
		this.unknown = unknown;
	}

	public static synchronized Areas get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	private static Areas load() {
		Path path = null;

		try {
			ModContainer container = FabricLoader.getInstance().getModContainer(SkyZH.MOD_ID).orElse(null);
			path = container == null ? null : container.findPath(RESOURCE).orElse(null);
		} catch (Throwable ignored) {
			// No Fabric runtime to ask, which happens when the capture is exercised outside a game.
		}

		if (path == null) {
			LOGGER.warn("找不到 {}，采集到的文本将全部落进未知玩法目录。", RESOURCE);
			return new Areas(Map.of(), Map.of(), "_Unknown_Gameplay");
		}

		return from(path);
	}

	/** Reads the table from a file. Public so the shipped table itself can be checked without a game. */
	public static Areas from(Path path) {
		Map<String, String> byArea = new HashMap<>();
		String unknown = "_Unknown_Gameplay";

		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

			if (json.has("unknown")) {
				unknown = json.get("unknown").getAsString();
			}

			// "modes" is read into the same map as "areas" on purpose: an id and a name are two
			// spellings of one place, and every lookup wants whichever of them it happens to hold.
			for (String section : new String[] {"areas", "modes"}) {
				if (!json.has(section) || !json.get(section).isJsonObject()) {
					continue;
				}

				for (Map.Entry<String, JsonElement> area : json.getAsJsonObject(section).entrySet()) {
					byArea.put(area.getKey(), area.getValue().getAsString());
				}
			}
		} catch (Exception e) {
			LOGGER.warn("读取 {} 失败，采集到的文本将全部落进未知玩法目录：{}", path, e.toString());
		}

		Map<String, String> folded = new HashMap<>();

		for (Map.Entry<String, String> area : byArea.entrySet()) {
			folded.putIfAbsent(area.getKey().toLowerCase(Locale.ROOT), area.getValue());
		}

		return new Areas(Map.copyOf(byArea), Map.copyOf(folded), unknown);
	}

	/**
	 * The gameplay category for an area name, or the unknown bucket when the table has no answer.
	 *
	 * <p>An unclassified area is said out loud, once, with the name spelled exactly as it was read.
	 * "Everything ended up in the unknown folder" has two possible causes that look identical from the
	 * output — a place nobody has added to the table, and a place whose name was read wrongly — and
	 * printing the name separates them at a glance: {@code Dwarven Mines} is a table to edit,
	 * {@code §7Dwarven Mines} is a bug.
	 */
	public String gameplay(String area) {
		if (area == null || area.isBlank()) {
			return this.unknown;
		}

		String found = find(area);

		if (found != null) {
			return found;
		}

		if (this.reported.add(area)) {
			LOGGER.info(
				"SkyZH 采集：区域「{}」还没归类，这一批文本落进 {}/。"
					+ "往 assets/skyzh/capture/areas.json 的 areas 里补一条就行，不用改代码。",
				area, this.unknown
			);
		}

		return this.unknown;
	}

	/**
	 * Says once that an area is missing from the table even though its text was filed correctly.
	 *
	 * <p>Separate from the complaint {@link #gameplay} makes, because the two failures need different
	 * words and only one of them is urgent. "Nobody knew where the player was, so this went to the
	 * unknown folder" is a hole in the output; "the sidebar named a corner nobody has classified and
	 * the island reading covered for it" is a line missing from the table and nothing else. Saying
	 * nothing at all in the second case is what would make it permanent — the folder that used to be
	 * the reminder is now, correctly, empty.
	 */
	public void unclassified(String area, String gameplay) {
		if (area == null || area.isBlank() || knows(area) || !this.reported.add(area)) {
			return;
		}

		LOGGER.info(
			"SkyZH 采集：区域「{}」还没归类，这一批文本靠岛屿那一路落进了 {}/。"
				+ "往 assets/skyzh/capture/areas.json 的 areas 里补一条，以后不靠岛屿也分得对。",
			area, gameplay
		);
	}

	/**
	 * The table's answer for a name, in the three readings that mean the same place, or {@code null}.
	 *
	 * <p>The third reading is what makes {@code inkkni's Museum} work. A place named after its owner
	 * is a different string for every player, so no table can list it; what kind of place it is
	 * survives the owner and is the half that decides the folder. Reached only after the direct
	 * lookups have failed, so a name the table holds in full — {@code Goblin Queen's Den} — is
	 * answered by that entry and never taken apart.
	 */
	private String find(String area) {
		String exact = this.byArea.get(area);

		if (exact != null) {
			return exact;
		}

		String folded = this.folded.get(area.toLowerCase(Locale.ROOT));

		if (folded != null) {
			return folded;
		}

		Possessive.Owned owned = Possessive.split(area);

		if (owned == null) {
			return null;
		}

		String thing = this.byArea.get(owned.thing());

		return thing != null ? thing : this.folded.get(owned.thing().toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether the table has an answer for this name, without saying anything about it.
	 *
	 * <p>Separate from {@link #gameplay} because that method complains, once, about a name it does not
	 * know — which is right when a line is being filed and wrong when three readings of the same fact
	 * are being tried in turn. Asked first, the log keeps saying "this place needs a line in the
	 * table"; asked with this, it would say it about every reading that was not the one used.
	 */
	public boolean knows(String area) {
		if (area == null || area.isBlank()) {
			return false;
		}

		return find(area) != null;
	}

	public String unknown() {
		return this.unknown;
	}
}
