package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.Areas;
import io.github.bingkkni.skyzh.capture.PreservedText;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Auxiliary tables owned by one corpus generation, never independently published. */
public record CorpusData(ItemNames items, Areas areas, PreservedText.Rules preserved) {
	public static final String ITEMS = "_data/item-names.json";
	public static final String AREAS = "_data/areas.json";
	public static final String PRESERVED = "_data/preserved-text.json";
	public static final Set<String> FILES = Set.of(ITEMS, AREAS, PRESERVED);

	public static CorpusData from(Map<String, JsonObject> files) {
		// Small offline fixtures can omit all auxiliary data; a real pack must supply every table.
		if (FILES.stream().noneMatch(files::containsKey)) return bundled();
		return new CorpusData(ItemNames.from(required(files, ITEMS)), Areas.from(required(files, AREAS)),
			PreservedText.from(required(files, PRESERVED)));
	}

	public static JsonObject required(Map<String, JsonObject> files, String path) {
		JsonObject value = files.get(path);
		if (value == null) throw new IllegalArgumentException("缺少必要文件: " + path);
		return value;
	}

	/** Lazy only for the empty startup index and standalone fixtures; full loads use their own data. */
	public static CorpusData bundled() { return Bundled.DATA; }

	private static final class Bundled {
		private static final CorpusData DATA = from(Map.of(ITEMS, read(ITEMS), AREAS, read(AREAS), PRESERVED, read(PRESERVED)));
	}

	private static JsonObject read(String path) {
		try (var stream = CorpusData.class.getResourceAsStream("/assets/skyzh/original_text/" + path)) {
			if (stream == null) throw new IllegalStateException("缺少内置数据: " + path);
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (java.io.IOException e) {
			throw new IllegalStateException("读取内置数据失败: " + path, e);
		}
	}

	public static Set<String> strings(JsonObject json, String key) {
		if (!json.has(key) || !json.get(key).isJsonArray())
			throw new IllegalArgumentException("缺少字符串数组: " + key);
		Set<String> values = new HashSet<>();
		for (JsonElement value : json.getAsJsonArray(key)) values.add(string(value, key));
		return Set.copyOf(values);
	}

	public static String string(JsonElement value, String key) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("字段必须为字符串: " + key);
		return value.getAsString();
	}
}
