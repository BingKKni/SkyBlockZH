package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Explicitly preserved text is capture policy, not a fake translation or a render-time rewrite. */
public final class PreservedText {
	private static final String RESOURCE = "/assets/skyzh/capture/preserved-text.json";
	private static final Pattern LEVEL = Pattern.compile(
		"^(.+?) ([IVXLCDM]{1,8})(?: ([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbB]?))?$"
	);
	private static final Pattern BOSS_COUNTER = Pattern.compile("^(.+?) [0-9]{1,3}/[0-9]{1,3}$");
	private static final Pattern LEVELED_NAME = Pattern.compile("^\\[Lv[0-9]{1,4}] (.+)$");
	private static final Pattern DATE_SERVER = Pattern.compile("[0-9]{2}/[0-9]{2}/[0-9]{2} (?:m|mini|mega)[A-Za-z0-9]{1,8}");
	private static final Pattern ELECTION_BAR = Pattern.compile("\\|{1,40} ([A-Za-z][A-Za-z ]{0,31})");
	private static final Pattern ELECTION_ROW = Pattern.compile("([A-Za-z][A-Za-z ]{0,31}): \\|{1,40} \\([0-9.]+%\\)");
	private static final Pattern BESTIARY_COUNTER = Pattern.compile("(.{1,48}?) (?:[IVXLCDM]{1,8}|[0-9]{1,3}): [0-9,]+/[0-9,]+");
	private static final Pattern TROPHY_ROW = Pattern.compile("[●○]{4} (.+)");
	private static final Pattern TROPHY_TASK = Pattern.compile("[✔✖] (.+) x[0-9][0-9,]*");
	private static final Pattern PARTY_MEMBER = Pattern.compile("[A-Za-z0-9_]{1,16} \\([0-9]{1,3}\\)");
	private record Rules(Set<String> enchantments, Set<String> scoreboardLines,
		Set<String> bossCounters, Set<String> leveledItemNames, Set<String> profileNames,
		Set<String> trophyFishNames) {}
	private static final Rules RULES = load();

	private PreservedText() {}

	/** Exact surface/whole-line boundaries; never suppress a sentence containing one of these words. */
	public static boolean ignored(Surface surface, String plain) {
		String text = plain.trim();
		if (surface == Surface.CHAT && text.length() > 4 && text.startsWith("✆ ") && text.endsWith(" ✆")) {
			// Known personal names can stay English. A registered NPC can also be a translatable
			// profession, so an explicit contact/nameplate translation must keep capture enabled.
			String name = text.substring(2, text.length() - 2).trim();
			var index = Translator.index();
			return index.isNpcName(name) && index.lookupExact(Surface.ITEM, name) == null
				&& index.lookupExact(Surface.HOLOGRAM, name) == null;
		}
		if (surface == Surface.SCOREBOARD) {
			Matcher vote = ELECTION_BAR.matcher(text);
			return RULES.scoreboardLines().contains(text) || DATE_SERVER.matcher(text).matches()
				|| vote.matches() && knownCandidate(vote.group(1));
		}
		if (surface == Surface.TABLIST) {
			Matcher vote = ELECTION_ROW.matcher(text);
			if (vote.matches() && knownCandidate(vote.group(1))) return true;
			Matcher counter = BESTIARY_COUNTER.matcher(text);
			if (counter.matches() && counter.group(1).equals(
				Translator.index().terms().translate("raw", counter.group(1)))) return true;
			Matcher trophy = TROPHY_ROW.matcher(text), task = TROPHY_TASK.matcher(text);
			return trophy.matches() && RULES.trophyFishNames().contains(trophy.group(1))
				|| task.matches() && RULES.trophyFishNames().contains(task.group(1));
		}
		if (surface == Surface.GUI_TITLE) {
			int arrow = text.indexOf(" ➜ ");
			if (arrow < 0) return false;
			String enchantment = text.substring(0, arrow), truncated = text.substring(arrow + 3);
			return RULES.enchantments().contains(enchantment) && !truncated.isBlank()
				&& enchantment.startsWith(truncated);
		}
		if (surface == Surface.BOSS_BAR) {
			Matcher counter = BOSS_COUNTER.matcher(text);
			return counter.matches() && RULES.bossCounters().contains(counter.group(1));
		}
		if (surface != Surface.ITEM) {
			return false;
		}
		for (var range : io.github.bingkkni.skyzh.text.LineShape.candidates(Surface.ITEM, text)) {
			Matcher name = LEVELED_NAME.matcher(text.substring(range.start(), range.end()));
			if (name.matches() && RULES.leveledItemNames().contains(name.group(1))) return true;
		}
		if (RULES.enchantments().contains(text)) {
			return true;
		}
		Matcher level = LEVEL.matcher(text);
		return level.matches() && RULES.enchantments().contains(level.group(1));
	}

	private static boolean knownCandidate(String name) {
		return Translator.index().isNpcName(name) || Translator.index().isNpcName("Mayor " + name);
	}

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

	/** Read-only for the authoring/packaging agreement test. */
	public static Set<String> enchantmentNames() {
		return RULES.enchantments();
	}

	private static Rules load() {
		try (var stream = PreservedText.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException("Missing " + RESOURCE);
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Rules(strings(json, "enchantments"), strings(json, "scoreboard_lines"),
				strings(json, "bossbar_counters"), strings(json, "leveled_item_names"), strings(json, "profile_names"),
				strings(json, "trophy_fish_names"));
		} catch (Exception error) {
			// Fail open for capture: missing policy must never hide potentially untranslated content.
			LoggerFactory.getLogger("SkyZH").warn("无法读取保留原文采集名单，继续报告未翻译文本", error);
			return new Rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
		}
	}

	private static Set<String> strings(JsonObject json, String field) {
		Set<String> values = new HashSet<>();
		if (!json.has(field)) return Set.of();
		for (var value : json.getAsJsonArray(field)) values.add(value.getAsString());
		return Set.copyOf(values);
	}
}
