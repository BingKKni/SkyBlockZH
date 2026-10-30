package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Offline NEU item identities, separate from translated menu labels and gameplay terms. */
public final class ItemNames {
	private static final Pattern PET = Pattern.compile("^\\[Lvl \\d{1,3}] (.+)$");
	private static final Pattern STARS = Pattern.compile(" [✪★☆➊➋➌➍➎✦]+$");
	private static final Pattern LEADING_ICON = Pattern.compile("^" + Capture.ICON.regex() + " ");
	private static final ItemNames INSTANCE = load();

	private final Map<String, String> names;
	private final Map<String, String> pets;
	private final List<String> reforges;

	private ItemNames(JsonObject data) {
		this.names = names(data, "names");
		this.pets = names(data, "pets");
		this.reforges = names(data, "reforges").values().stream()
			.sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
	}

	private static Map<String, String> names(JsonObject data, String key) {
		Map<String, String> result = new HashMap<>();
		if (data.has(key)) {
			for (JsonElement entry : data.getAsJsonArray(key)) {
				String name = entry.getAsString();
				result.put(Glyphs.canonical(name).toLowerCase(Locale.ROOT), name);
			}
		}
		return Map.copyOf(result);
	}

	private static ItemNames load() {
		try (InputStream stream = ItemNames.class.getResourceAsStream("/assets/skyzh/item-names.json")) {
			if (stream == null) {
				throw new IllegalStateException("Missing item-name catalog");
			}
			return new ItemNames(JsonParser.parseReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception exception) {
			LoggerFactory.getLogger("SkyZH").warn("物品原名目录加载失败，已关闭物品原文提示。", exception);
			return new ItemNames(new JsonObject());
		}
	}

	/** Only complete names and known item decorations qualify; unknown text fails closed. */
	public static String canonical(String value) {
		String display = value.trim();
		String name = Glyphs.canonical(STARS.matcher(display).replaceFirst(""));
		String exact = INSTANCE.names.get(name.toLowerCase(Locale.ROOT));
		if (exact != null) {
			return display;
		}
		Matcher pet = PET.matcher(name);
		if (pet.matches() && INSTANCE.pets.containsKey(pet.group(1).toLowerCase(Locale.ROOT))) {
			return display;
		}
		for (String reforge : INSTANCE.reforges) {
			// startsWith(reforge + " "), without building that string for each of the reforges.
			if (name.length() > reforge.length() && name.charAt(reforge.length()) == ' ' && name.startsWith(reforge)
				&& INSTANCE.names.containsKey(name.substring(reforge.length() + 1).toLowerCase(Locale.ROOT))) {
				return display;
			}
		}
		return null;
	}

	/** Reads the catalog now, on the calling thread, rather than on the first tooltip the client draws. */
	public static void preload() {
		// Class initialisation has read INSTANCE by the time this runs; there is nothing else to do.
	}

	/** A decorated item still needs a complete catalog identity after its leading server icons. */
	public static boolean isLoadoutReference(String value) {
		if (!value.equals(value.trim())) return false;
		if (canonical(value) != null) return true;
		String bare = value;
		for (int count = 0; count < 3; count++) {
			Matcher icon = LEADING_ICON.matcher(bare);
			if (!icon.find()) break;
			bare = bare.substring(icon.end());
		}
		return canonical(bare) != null;
	}

	/**
	 * Whether this is a base catalog name as written, dungeon stars aside, with no reforge in front.
	 * "Hyper Catalyst" and "Heavy Helmet" start with a reforge word and are still items of their own.
	 */
	public static boolean isBaseName(String value) {
		String name = Glyphs.canonical(STARS.matcher(value.trim()).replaceFirst(""));
		return INSTANCE.names.containsKey(name.toLowerCase(Locale.ROOT));
	}
}
