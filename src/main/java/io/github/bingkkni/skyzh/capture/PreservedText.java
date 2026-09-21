package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.Surface;
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
	private record Rules(Set<String> enchantments, Set<String> scoreboardLines) {}
	private static final Rules RULES = load();

	private PreservedText() {}

	/** Exact surface/whole-line boundaries; never suppress a sentence containing one of these words. */
	public static boolean ignored(Surface surface, String plain) {
		String text = plain.trim();
		if (surface == Surface.SCOREBOARD) {
			return RULES.scoreboardLines().contains(text);
		}
		if (surface != Surface.ITEM) {
			return false;
		}
		if (RULES.enchantments().contains(text)) {
			return true;
		}
		Matcher level = LEVEL.matcher(text);
		return level.matches() && RULES.enchantments().contains(level.group(1));
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
			return new Rules(strings(json, "enchantments"), strings(json, "scoreboard_lines"));
		} catch (Exception error) {
			// Fail open for capture: missing policy must never hide potentially untranslated content.
			LoggerFactory.getLogger("SkyZH").warn("无法读取保留原文采集名单，继续报告未翻译文本", error);
			return new Rules(Set.of(), Set.of());
		}
	}

	private static Set<String> strings(JsonObject json, String field) {
		Set<String> values = new HashSet<>();
		for (var value : json.getAsJsonArray(field)) values.add(value.getAsString());
		return Set.copyOf(values);
	}
}
