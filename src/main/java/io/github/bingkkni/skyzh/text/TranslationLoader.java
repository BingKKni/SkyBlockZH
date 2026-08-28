package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the corpus in {@code assets/skyzh/original_text/} — the same files a translator edits, shipped
 * verbatim — and compiles it into a {@link TranslationIndex}.
 *
 * <p>Nothing is generated at build time on purpose. A wrong line on screen should lead back to one
 * file and one {@code id} with no intermediate format to disbelieve, and the corpus is small enough
 * (a few hundred kilobytes) that parsing it once during startup does not register.
 *
 * <p>The reader is deliberately incurious about the shape of a file: any top-level array whose
 * elements carry an {@code id} is a list of records, whatever it is called. The corpus already uses
 * {@code messages}, {@code titles}, {@code lines}, {@code bars}, {@code entries} and {@code lore}
 * for exactly the same thing, and a new gameplay category should not have to teach the loader a new
 * word for "record" before its text shows up.
 */
public final class TranslationLoader {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String ROOT = "assets/skyzh/original_text";

	/** {@code %2$s} in a {@code placeholders[].token}, which names the argument it describes. */
	private static final Pattern NUMBERED_TOKEN = Pattern.compile("%(\\d+)\\$");

	/**
	 * Holds the translation of "SkyBlock" itself. Not a list of records like every other file — the
	 * word is substituted into lines rather than being a line — so it is read by name instead of
	 * being walked with the rest.
	 */
	private static final String SKYBLOCK_NAME = "_shared/SkyBlock_Name.json";

	/**
	 * Holds Chinese for the values placeholders capture — area names and the like — rather than for
	 * whole lines. Read by name for the same reason as the file above: it is a dictionary, not a list
	 * of records, and walking it as records would find nothing.
	 */
	private static final String TERMS = "_shared/Terms.json";

	private TranslationLoader() {
	}

	public static TranslationIndex load() {
		TranslationIndex index = new TranslationIndex();
		ModContainer container = FabricLoader.getInstance().getModContainer("skyzh").orElse(null);

		if (container == null) {
			LOGGER.error("找不到 SkyZH 自身的 mod 容器，翻译语料无法加载，游戏文本将保持英文。");
			return index;
		}

		Path root = container.findPath(ROOT).orElse(null);

		if (root == null) {
			LOGGER.error("jar 内缺少 {}，翻译语料无法加载，游戏文本将保持英文。", ROOT);
			return index;
		}

		Map<String, JsonObject> files = new HashMap<>();

		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.filter(Files::isRegularFile).toList()) {
				String relative = root.relativize(path).toString().replace('\\', '/');

				if (!relative.endsWith(".json")) {
					continue;
				}

				try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					JsonElement parsed = JsonParser.parseReader(reader);

					if (parsed.isJsonObject()) {
						files.put(relative, parsed.getAsJsonObject());
					}
				} catch (Exception e) {
					LOGGER.warn("翻译文件 {} 解析失败，已跳过：{}", relative, e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.error("遍历翻译语料目录失败，游戏文本将保持英文。", e);
			return index;
		}

		return compile(files);
	}

	/**
	 * Turns already-parsed corpus files into an index. Separated from the reading above so the whole
	 * matching engine can be run against the real corpus without a game around it — the compile step
	 * is where every interesting decision lives, and it should be testable on its own.
	 *
	 * @param files record files keyed by their path relative to {@code original_text/}
	 */
	public static TranslationIndex compile(Map<String, JsonObject> files) {
		TranslationIndex index = new TranslationIndex();

		JsonObject name = files.get(SKYBLOCK_NAME);

		if (name != null) {
			index.skyBlockName(SkyBlockName.from(name));
		}

		JsonObject terms = files.get(TERMS);

		if (terms != null) {
			index.terms(TermTable.from(terms));
		}

		// Every record addressable as "<path>#<id>", so a "ref" in one file can borrow a shared line
		// out of another instead of copying it — the shared-fragment rule from original_text/README.
		Map<String, JsonObject> byReference = new HashMap<>();

		files.forEach((relative, file) -> {
			for (JsonObject record : records(file)) {
				if (record.has("id")) {
					byReference.put(relative + "#" + record.get("id").getAsString(), record);
				}
			}
		});

		int compiled = 0;
		int skipped = 0;

		// Sorted, so which of two records that could both answer for a line is registered first does
		// not depend on how a HashMap felt about their file names. Ranking still decides the winner
		// (see TranslationIndex#lookup); this only makes ties come out the same way every run.
		for (String relative : new TreeSet<>(files.keySet())) {
			String[] parts = relative.split("/");

			// "<category>/<surface>/<file>.json", or "_shared/<file>.json" for the cross-category library.
			Surface surface = Surface.fromDirectory(parts.length >= 3 ? parts[parts.length - 2] : parts[0]);

			if (surface == null) {
				continue;
			}

			for (JsonObject record : records(files.get(relative))) {
				JsonObject source = resolve(record, byReference);
				TranslationEntry entry = compile(record, relative, byReference);

				if (entry == null) {
					if (translated(source)) {
						// It has Chinese in it and still would not compile, which leaves only one
						// reason: a template with no word of its own, matching every line on its
						// surface. Worth saying out loud — the record looks finished in the file.
						LOGGER.warn(
							"记录 {}（{}）的原文除占位符外只剩空格和 ASCII 标点（如 \"%s: %s\"），这种模板会匹配该渲染面上的每一行"
								+ "并顶掉真正为那些行写的翻译，已跳过。请把它拆成写明具体词句的多条记录。",
							record.has("id") ? record.get("id").getAsString() : "?", relative
						);
					}

					skipped++;
					continue;
				}

				index.add(surface, template(source), entry);
				compiled++;
			}
		}

		LOGGER.info("SkyZH 已加载 {} 个翻译文件，可用记录 {} 条（{} 条尚未翻译或不需要翻译，保持英文）。",
			files.size(), compiled, skipped);

		return index;
	}

	/** Top-level members that hold records: arrays of objects with an {@code id}, plus a lone {@code name} object. */
	private static List<JsonObject> records(JsonObject file) {
		List<JsonObject> records = new ArrayList<>();

		for (Map.Entry<String, JsonElement> member : file.entrySet()) {
			JsonElement value = member.getValue();

			if (value.isJsonArray()) {
				JsonArray array = value.getAsJsonArray();

				for (JsonElement element : array) {
					if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
						records.add(element.getAsJsonObject());
					}
				}
			} else if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
				records.add(value.getAsJsonObject());
			}
		}

		return records;
	}

	/** Whether a record carries Chinese at all — the difference between "not done yet" and "wrong". */
	private static boolean translated(JsonObject source) {
		if (source.has("continuation") && source.get("continuation").getAsBoolean()) {
			return true;
		}

		if (source.has("segments") && source.get("segments").isJsonArray()) {
			for (JsonElement element : source.getAsJsonArray("segments")) {
				if (!string(element.getAsJsonObject(), "zh").isEmpty()) {
					return true;
				}
			}

			return false;
		}

		return !string(source, "zh").isEmpty();
	}

	/** Follows a {@code ref} to the shared library; a record without one is already itself. */
	private static JsonObject resolve(JsonObject record, Map<String, JsonObject> byReference) {
		if (!record.has("ref")) {
			return record;
		}

		JsonObject target = byReference.get(record.get("ref").getAsString());
		return target != null ? target : record;
	}

	private static TranslationEntry compile(JsonObject record, String relative, Map<String, JsonObject> byReference) {
		JsonObject source = resolve(record, byReference);

		if (source.has("translate") && !source.get("translate").getAsBoolean()) {
			// Proper nouns and blank lore lines are collected precisely so the code knows they were
			// looked at and left alone. Nothing to compile.
			return null;
		}

		List<String> sources = new ArrayList<>();
		List<String> targets = new ArrayList<>();
		List<Integer> order = new ArrayList<>();
		String id = record.has("id") ? record.get("id").getAsString() : "?";

		if (source.has("segments") && source.get("segments").isJsonArray()) {
			for (JsonElement element : source.getAsJsonArray("segments")) {
				JsonObject segment = element.getAsJsonObject();
				sources.add(string(segment, "text"));
				// "omit": true — this colour run has no Chinese of its own because its words moved
				// into a neighbouring run when the sentence was reordered. Distinct from an empty
				// "zh", which still means "not translated yet, leave the English".
				boolean omit = segment.has("omit") && segment.get("omit").getAsBoolean();
				targets.add(omit ? null : string(segment, "zh"));
				// "order": where this run sits in the finished Chinese. Absent on almost every record,
				// which is what "the Chinese says these things in the order the English did" looks like.
				order.add(segment.has("order") && segment.get("order").isJsonPrimitive()
					? segment.get("order").getAsInt() : order.size());
			}
		} else {
			sources.add(string(source, "text"));
			targets.add(string(source, "zh"));
			order.add(0);
		}

		return TranslationEntry.compile(
			id, relative, sources, targets, permutation(order, id, relative),
			source.has("continuation") && source.get("continuation").getAsBoolean(),
			string(source, "layout"), argTypes(source)
		);
	}

	/**
	 * The declared render order, or an empty list meaning "leave it in the order the English is in".
	 *
	 * <p>Checked here rather than where it is used, because this is the only place that knows which
	 * record to name. Two positions claiming the same slot, or a slot outside the array, would mean a
	 * fragment silently never drawn — half a sentence missing from the screen with nothing said about
	 * it — so a malformed order is refused outright and the line is drawn in the order it arrived.
	 */
	private static List<Integer> permutation(List<Integer> order, String id, String relative) {
		boolean[] taken = new boolean[order.size()];
		boolean reordered = false;

		for (int i = 0; i < order.size(); i++) {
			int at = order.get(i);

			if (at < 0 || at >= taken.length || taken[at]) {
				LOGGER.warn(
					"记录 {}（{}）的 segments[].order 不是 0..{} 的一个排列（第 {} 段写的是 {}），"
						+ "这会让某一段永远不被画出来。已忽略这条记录的 order，按原文顺序渲染。",
					id, relative, order.size() - 1, i, at
				);

				return List.of();
			}

			taken[at] = true;
			reordered |= at != i;
		}

		return reordered ? List.copyOf(order) : List.of();
	}

	/**
	 * The {@code type} of each of a record's placeholders, keyed by the argument number the template
	 * refers to it as.
	 *
	 * <p>A {@code token} of {@code "%2$s"} says its own number. A bare {@code "%s"} does not, so it
	 * takes the next one — which matches how the template itself numbers them, and how a translator
	 * reading the file top to bottom would pair the array up with the sentence.
	 */
	private static Map<Integer, String> argTypes(JsonObject source) {
		if (!source.has("placeholders") || !source.get("placeholders").isJsonArray()) {
			return Map.of();
		}

		Map<Integer, String> types = new HashMap<>();
		int next = 1;

		for (JsonElement element : source.getAsJsonArray("placeholders")) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject placeholder = element.getAsJsonObject();
			Matcher numbered = NUMBERED_TOKEN.matcher(string(placeholder, "token"));
			int index = numbered.find() ? Integer.parseInt(numbered.group(1)) : next++;

			types.putIfAbsent(index, string(placeholder, "type"));
		}

		return types;
	}

	/** The English of a record with its fragments joined back together — the key the index buckets on. */
	private static String template(JsonObject source) {
		if (source.has("segments") && source.get("segments").isJsonArray()) {
			StringBuilder joined = new StringBuilder();

			for (JsonElement element : source.getAsJsonArray("segments")) {
				joined.append(string(element.getAsJsonObject(), "text"));
			}

			return Glyphs.canonical(joined.toString());
		}

		// Canonical, because the index is searched with the canonical spelling of the live line:
		// whichever icon font a record was collected under, it is filed under the symbol.
		return Glyphs.canonical(string(source, "text"));
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
	}
}
