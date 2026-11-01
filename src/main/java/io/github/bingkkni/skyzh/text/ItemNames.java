package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline NEU item identities, separate from translated menu labels and gameplay terms. */
public final class ItemNames {
	private static final Pattern PET = Pattern.compile("^\\[Lvl \\d{1,3}] (.+)$");
	private static final Pattern STARS = Pattern.compile(" [✪★☆➊➋➌➍➎✦]+$");
	private static final Pattern LEADING_ICON = Pattern.compile("^" + Capture.ICON.regex() + " ");

	private final Map<String, String> names;
	private final Map<String, String> pets;
	private final List<String> reforges;

	private ItemNames(JsonObject data) {
		this.names = names(data, "names");
		this.pets = names(data, "pets");
		this.reforges = names(data, "reforges").values().stream()
			.sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
	}

	public static ItemNames from(JsonObject data) { return new ItemNames(data); }

	private static ItemNames current() { return Translator.index().data().items(); }

	private static Map<String, String> names(JsonObject data, String key) {
		Map<String, String> result = new HashMap<>();
		for (String name : CorpusData.strings(data, key)) {
			result.put(Glyphs.canonical(name).toLowerCase(Locale.ROOT), name);
		}
		return Map.copyOf(result);
	}

	/** Only complete names and known item decorations qualify; unknown text fails closed. */
	public static String canonical(String value) {
		return current().canonicalName(value);
	}

	private String canonicalName(String value) {
		String display = value.trim();
		String name = Glyphs.canonical(STARS.matcher(display).replaceFirst(""));
		String exact = this.names.get(name.toLowerCase(Locale.ROOT));
		if (exact != null) {
			return display;
		}
		Matcher pet = PET.matcher(name);
		if (pet.matches() && this.pets.containsKey(pet.group(1).toLowerCase(Locale.ROOT))) {
			return display;
		}
		for (String reforge : this.reforges) {
			// startsWith(reforge + " "), without building that string for each of the reforges.
			if (name.length() > reforge.length() && name.charAt(reforge.length()) == ' ' && name.startsWith(reforge)
				&& this.names.containsKey(name.substring(reforge.length() + 1).toLowerCase(Locale.ROOT))) {
				return display;
			}
		}
		return null;
	}

	/** A decorated item still needs a complete catalog identity after its leading server icons. */
	public static boolean isLoadoutReference(String value) {
		ItemNames catalog = current();
		if (!value.equals(value.trim())) return false;
		if (catalog.canonicalName(value) != null) return true;
		String bare = value;
		for (int count = 0; count < 3; count++) {
			Matcher icon = LEADING_ICON.matcher(bare);
			if (!icon.find()) break;
			bare = bare.substring(icon.end());
		}
		return catalog.canonicalName(bare) != null;
	}

	/**
	 * Whether this is a base catalog name as written, dungeon stars aside, with no reforge in front.
	 * "Hyper Catalyst" and "Heavy Helmet" start with a reforge word and are still items of their own.
	 */
	public static boolean isBaseName(String value) {
		String name = Glyphs.canonical(STARS.matcher(value.trim()).replaceFirst(""));
		return current().names.containsKey(name.toLowerCase(Locale.ROOT));
	}
}
