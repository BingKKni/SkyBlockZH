package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.CorpusData;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.Set;
import java.util.regex.Pattern;

/** Identity lists and explicitly untranslated enchantments; neither is a capture exclusion list. */
public final class PreservedText {
	private static final Pattern PARTY_MEMBER = Pattern.compile("[A-Za-z0-9_]{1,16} \\([0-9]{1,3}\\)");
	public record Rules(Set<String> enchantments, Set<String> profileNames) {}

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
		Set<String> profiles = Translator.index().data().preserved().profileNames();
		for (int i = 0; i < end; i++) {
			if (!profiles.contains(names[i].trim())) return false;
		}
		return true;
	}

	/** Used to recognize preserved names inside otherwise translated enchantment-book descriptions. */
	public static Set<String> enchantmentNames() {
		return Translator.index().data().preserved().enchantments();
	}

	public static Rules from(JsonObject json) {
		return new Rules(CorpusData.strings(json, "enchantments"), CorpusData.strings(json, "profile_names"));
	}
}
