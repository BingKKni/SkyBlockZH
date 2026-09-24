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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
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

			JsonObject file = files.get(relative);

			if (parts.length >= 3 && "NPC_Message".equalsIgnoreCase(parts[parts.length - 2])) {
				npcNames(file, index);
			}

			for (JsonObject record : records(file)) {
				JsonObject source = resolve(record, byReference);
				TranslationEntry entry = compile(record, relative, byReference);

				if (entry == null) {
					if (source.has("translate") && !source.get("translate").getAsBoolean()) {
						// Decided, not forgotten: the corpus keeps this line in English on purpose, so
						// capture must stop reporting it as missing every session. Exact lines only.
						index.preserve(surface, template(source));
					}

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

		TermTable baseTerms = index.terms();
		index.terms(baseTerms.withItemNames(itemNames(index), english -> {
			// Name templates cover real catalog items such as Basic Cow Axe. Never apply a menu or
			// lore template to an unknown captured name, and render with the non-recursive base table.
			if (ItemNames.canonical(english) == null) return null;
			TranslationEntry entry = index.lookup(Surface.ITEM, english);
			if (entry == null || entry.continuation()) return null;
			Matcher match = entry.match(english);
			if (match == null) return null;
			String chinese = entry.render(StyledText.of(Component.literal(english)), match, baseTerms).getString();
			return chinese.isEmpty() || chinese.equals(english) ? null : chinese;
		}));

		LOGGER.info("SkyZH 已加载 {} 个翻译文件，可用记录 {} 条（{} 条尚未翻译或不需要翻译，保持英文）。",
			files.size(), compiled, skipped);

		return index;
	}

	/**
	 * English to Chinese for every exact (placeholder-free) item-surface record that actually changes
	 * its line, so a {@code guide_item_name} placeholder can show the same name the item's own line shows.
	 * Rendered through the record itself, which is what makes segments, {@code ref}s and the SkyBlock
	 * name substitution come out the same as on the item.
	 */
	private static Map<String, String> itemNames(TranslationIndex index) {
		Map<String, String> names = new HashMap<>();

		for (TranslationEntry entry : index.entries(Surface.ITEM)) {
			String english = entry.template();

			if (english.indexOf('%') >= 0 || entry.continuation() || english.isBlank()
				|| english.length() > 64 || !english.trim().equals(english)) {
				continue;
			}

			Matcher match = entry.match(english);

			if (match == null) {
				continue;
			}

			String chinese = entry.render(StyledText.of(Component.literal(english)), match, index.terms()).getString();

			if (!chinese.isEmpty() && !chinese.equals(english)) {
				names.putIfAbsent(english, chinese);
			}
		}

		return names;
	}

	/**
	 * The NPC an NPC_Message file belongs to, plus any rotating aliases ({@code King.json} names seven).
	 *
	 * <p>A proper name is text the project keeps in English, and a hologram that is nothing but one of
	 * these names is an NPC's nameplate — not a line anybody will translate. Kept on the index so the
	 * hologram capture can tell a nameplate from a role label without a colour heuristic.
	 */
	private static void npcNames(JsonObject file, TranslationIndex index) {
		index.npcName(string(file, "npc"));

		if (file.has("aliases") && file.get("aliases").isJsonArray()) {
			for (JsonElement alias : file.getAsJsonArray("aliases")) {
				if (alias.isJsonPrimitive()) {
					index.npcName(alias.getAsString());
				}
			}
		}
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
		if (source.has("translate") && !source.get("translate").getAsBoolean()) {
			return false;
		}

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
			// Authoring metadata for a line deliberately left in English. It is intentionally absent from
			// the runtime index (and from the minified jar); this documents the decision but is not a broad
			// passthrough matcher and does not suppress untranslated capture.
			return null;
		}

		List<TranslationEntry.Segment> segments = new ArrayList<>();
		String id = record.has("id") ? record.get("id").getAsString() : "?";

		if (source.has("segments") && source.get("segments").isJsonArray()) {
			for (JsonElement element : source.getAsJsonArray("segments")) {
				JsonObject segment = element.getAsJsonObject();
				// An omitted segment draws nothing; an empty translation preserves its English.
				boolean omit = segment.has("omit") && segment.get("omit").getAsBoolean();
				int order = segment.has("order") && segment.get("order").isJsonPrimitive()
					? segment.get("order").getAsInt() : segments.size();
				segments.add(new TranslationEntry.Segment(
					string(segment, "text"), omit ? null : string(segment, "zh"), order
				));
			}
		} else {
			segments.add(new TranslationEntry.Segment(string(source, "text"), string(source, "zh"), 0));
		}

		TranslationEntry.Options options = new TranslationEntry.Options(
			source.has("continuation") && source.get("continuation").getAsBoolean(),
			string(source, "layout"), excludedTexts(source), string(source, "chat_join_next")
		);

		return TranslationEntry.compile(new TranslationEntry.Definition(
			id, relative, orderedSegments(segments, id, relative), arguments(source), options
		));
	}

	private static Set<String> excludedTexts(JsonObject source) {
		Set<String> excluded = new HashSet<>();
		if (source.has("exclude") && source.get("exclude").isJsonArray()) {
			for (JsonElement value : source.getAsJsonArray("exclude")) {
				if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
					excluded.add(Glyphs.canonical(value.getAsString()));
				}
			}
		}
		return Set.copyOf(excluded);
	}

	/**
	 * Validates segment order; identity or invalid order falls back to the source sequence.
	 *
	 * <p>Lore continuation groups are allowed to order fragments across several records: the head and
	 * its tails together own the 0..n-1 permutation. At load time one record can therefore only reject
	 * nonsense that is locally knowable — negative slots and two of its own fragments claiming the same
	 * slot. {@code checkTranslations} validates the whole group while the authored corpus is available.
	 */
	private static List<TranslationEntry.Segment> orderedSegments(
		List<TranslationEntry.Segment> segments, String id, String relative
	) {
		Set<Integer> taken = new HashSet<>();
		boolean reordered = false;

		for (int i = 0; i < segments.size(); i++) {
			int at = segments.get(i).order();

			if (at < 0 || !taken.add(at)) {
				LOGGER.warn(
					"记录 {}（{}）的 segments[].order 在本记录内重复或为负数（第 {} 段写的是 {}），"
						+ "已忽略这条记录的 order，按原文顺序渲染。",
					id, relative, i, at
				);

				return sourceOrder(segments);
			}

			reordered |= at != i;
		}

		return reordered ? List.copyOf(segments) : sourceOrder(segments);
	}

	private static List<TranslationEntry.Segment> sourceOrder(List<TranslationEntry.Segment> segments) {
		return segments.stream()
			.map(segment -> new TranslationEntry.Segment(segment.source(), segment.target())).toList();
	}

	/** Types and examples are read together so both always describe the same placeholder number. */
	private static Map<Integer, TranslationEntry.Argument> arguments(JsonObject source) {
		if (!source.has("placeholders") || !source.get("placeholders").isJsonArray()) {
			return Map.of();
		}

		Map<Integer, TranslationEntry.Argument> values = new HashMap<>();
		int next = 1;

		for (JsonElement element : source.getAsJsonArray("placeholders")) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject placeholder = element.getAsJsonObject();
			Matcher numbered = NUMBERED_TOKEN.matcher(string(placeholder, "token"));
			int index = numbered.find() ? Integer.parseInt(numbered.group(1)) : next++;

			values.putIfAbsent(index, new TranslationEntry.Argument(
				string(placeholder, "type"), string(placeholder, "example")
			));
		}

		return values;
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
