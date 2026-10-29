package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Identity lists and explicitly untranslated enchantments; neither is a capture exclusion list. */
public final class PreservedText {
	private static final String RESOURCE = "/assets/skyzh/capture/preserved-text.json";
	private static final Pattern PARTY_MEMBER = Pattern.compile("[A-Za-z0-9_]{1,16} \\([0-9]{1,3}\\)");
	private record Rules(Set<String> enchantments, Set<String> profileNames) {}
	private static final Rules RULES = load();

	private PreservedText() {}

	/** Party Finder's player/level list is not an item description, and is scoped to that menu. */
	public static boolean partyFinderLine(String menu, String plain) {
		return "Party Finder".equals(menu) && PARTY_MEMBER.matcher(plain.trim()).matches();
	}

	/** Fruit Bowl lists profile identities, not ingredients. Do not suppress fruit text elsewhere. */
	public static boolean fruitProfileLine(String itemName, String plain) {
		if (!"Fruit Bowl".equals(itemName) || plain.isBlank()) return false;
		String[] names = plain.trim().split(",", -1);
		int end = names.length;
		if (end > 1 && names[end - 1].isBlank()) end--;
		if (end == 0 || end > 32) return false;
		for (int i = 0; i < end; i++) {
			if (!RULES.profileNames().contains(names[i].trim())) return false;
		}
		return true;
	}

	/** Used to recognize preserved names inside otherwise translated enchantment-book descriptions. */
	public static Set<String> enchantmentNames() {
		return RULES.enchantments();
	}

	private static Rules load() {
		try (var stream = PreservedText.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) throw new IllegalStateException("Missing " + RESOURCE);
			JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Rules(strings(json, "enchantments"), strings(json, "profile_names"));
		} catch (Exception error) {
			LoggerFactory.getLogger("SkyZH").warn("无法读取专名参考名单，继续报告未翻译文本", error);
			return new Rules(Set.of(), Set.of());
		}
	}

	private static Set<String> strings(JsonObject json, String field) {
		Set<String> values = new HashSet<>();
		if (!json.has(field)) return Set.of();
		for (var value : json.getAsJsonArray(field)) values.add(value.getAsString());
		return Set.copyOf(values);
	}
}
