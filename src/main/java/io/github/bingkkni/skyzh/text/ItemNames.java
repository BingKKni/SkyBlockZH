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
	private static final Pattern STARS = Pattern.compile(" [✪★☆➊➋➌➍➎]+$");
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
				result.put(name.toLowerCase(Locale.ROOT), name);
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
			LoggerFactory.getLogger("SkyZH").warn("物品原名目录加载失败，已关闭物品原文标注。", exception);
			return new ItemNames(new JsonObject());
		}
	}

	/** Only complete names and known item decorations qualify; unknown text fails closed. */
	public static String canonical(String value) {
		String display = value.trim();
		String name = STARS.matcher(display).replaceFirst("");
		String exact = INSTANCE.names.get(name.toLowerCase(Locale.ROOT));
		if (exact != null) {
			return display;
		}
		Matcher pet = PET.matcher(name);
		if (pet.matches() && INSTANCE.pets.containsKey(pet.group(1).toLowerCase(Locale.ROOT))) {
			return display;
		}
		for (String reforge : INSTANCE.reforges) {
			if (name.startsWith(reforge + " ")
				&& INSTANCE.names.containsKey(name.substring(reforge.length() + 1).toLowerCase(Locale.ROOT))) {
				return display;
			}
		}
		return null;
	}
}
