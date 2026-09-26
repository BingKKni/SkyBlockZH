package io.github.bingkkni.skyzh;

import com.google.gson.*;
import io.github.bingkkni.skyzh.capture.CaptureSurface;
import io.github.bingkkni.skyzh.capture.Classifier;
import io.github.bingkkni.skyzh.text.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import net.minecraft.network.chat.Component;

/** Complete-sentence matching checks without fonts. Pixel wrapping remains an in-game check. */
public final class LoreHarness {
	private static int checked;
	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		Map<String, JsonObject> files = TranslationHarness.readCorpus(Path.of(args[0]));
		TranslationIndex index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
		for (var file : files.entrySet()) {
			if (!file.getKey().contains("/GUI_Lore/")) continue;
			for (JsonObject r : records(file.getValue())) {
				String address = file.getKey() + "#" + r.get("id").getAsString();
				List<Sample> samples = new ArrayList<>();
				try {
					samples.add(exampleSample(r));
				} catch (IllegalArgumentException e) {
					check(address + " 非法 raw/example: " + e.getMessage(), false);
				}
				if (r.has("samples")) {
					int variant = 0;
					for (JsonElement el : r.getAsJsonArray("samples")) {
						String origin = "samples[" + variant++ + "]";
						try {
							List<String> lines = new ArrayList<>();
							for (JsonElement line : el.getAsJsonArray()) lines.add(line.getAsString());
							samples.add(sourceSample(r, lines, origin));
						} catch (IllegalArgumentException e) {
							check(address + " 非法 " + origin + ": " + e.getMessage(), false);
						}
					}
				}
				for (Sample sample : samples) {
					String id = address + " " + sample.origin();
					List<Component> input = sample.lines();
					if (sample.origin().contains("synthetic"))
						System.out.println("SAMPLE " + id + " source_items=" + r.get("source_items"));
					if (r.has("translate") && !r.get("translate").getAsBoolean()) {
						checkPreserved(id, r, input, index);
						continue;
					}
					LoreMatcher.Match match = LoreMatcher.find(index, input, 0);
					check(id + " 完整匹配", match != null && match.lines() == input.size());
					if (match == null) continue;
					check(id + " 不丢颜色", !match.entry().losesColour(match.core(), match.matcher()));
					check(id + " 无未填译文/词表值", !match.entry().mixed(match.core(), match.matcher(), index.terms()).any());
					String before = input.toString();
					String rendered = TranslationHarness.legacy(match.entry().render(match.core(), match.matcher(), index.terms()));
					check(id + " 原始组件未修改", before.equals(input.toString()));
					// Change the wrap, including a colour run split across lines. Matching/style must agree.
					StyledText whole = match.core();
					int split = whole.plain().indexOf(' ', whole.length() / 2);
					if (split > 0 && split < whole.length() - 1 && whole.plain().charAt(split - 1) != ' '
						&& whole.plain().charAt(split + 1) != ' ') {
						LoreMatcher.Match reflow = LoreMatcher.find(index, List.of(whole.slice(0, split),
							whole.slice(split + 1, whole.length())), 0);
						check(id + " 改变英文断行", reflow != null && reflow.lines() == 2
							&& rendered.equals(TranslationHarness.legacy(reflow.render(index.terms()))));
					}
				}
			}
		}
		JsonArray fixtures = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
		for (JsonElement el : fixtures) {
			JsonObject f = el.getAsJsonObject();
			List<Component> input = new ArrayList<>();
			for (JsonElement line : f.getAsJsonArray("lines")) input.add(Component.literal(line.getAsString()));
			String title = f.get("name").getAsString();
			if (f.has("units")) {
				for (JsonElement unit : f.getAsJsonArray("units")) if (unit.getAsJsonObject().has("expected_plain"))
					System.out.println("FIXTURE " + title + " " + colourEvidence(unit.getAsJsonObject()));
				List<String> differences = planDifferences(f, input);
				check(title + " 整组 plan " + differences, differences.isEmpty());
				continue;
			}
			LoreMatcher.Match match = LoreMatcher.find(index, input, 0);
			if (f.has("miss") && f.get("miss").getAsBoolean()) {
				check(title, match == null); continue;
			}
			if (f.has("expected") == f.has("expected_plain")) {
				check(title + " 必须且只能指定 expected 或 expected_plain", false); continue;
			}
			boolean plainOnly = f.has("expected_plain");
			if (plainOnly) System.out.println("FIXTURE " + title + " " + colourEvidence(f));
			String expected = plainOnly ? f.get("expected_plain").getAsString()
				: TranslationHarness.legacy(Component.literal(f.get("expected").getAsString()));
			if (plainOnly) check(title + " expected_plain 不含颜色码", expected.indexOf('§') < 0);
			String actual = match == null ? "<no match>" : plainOnly
				? StyledText.of(match.render(index.terms())).plain() : TranslationHarness.legacy(match.render(index.terms()));
			check(title + " expected=" + expected + " actual=" + actual, expected.equals(actual));
			if (match != null) {
				check(title + " 只消费已匹配行", match.lines() == f.get("consumed").getAsInt());
				check(title + " 自动检查颜色损失", !match.entry().losesColour(match.core(), match.matcher()));
				List<String> fragmentErrors = colourFragmentDifferences(f, match.render(index.terms()));
				if (f.has("colour_fragments")) check(title + " 局部颜色期望 " + fragmentErrors, fragmentErrors.isEmpty());
			}
		}
		checkHarnessRegressions(index);
		// A LORE template equal to an old ITEM head line whose next record is a continuation would translate
		// the head and leave the tail English — the exact outcome the tail rule exists to prevent.
		List<String> overlapping = new ArrayList<>();
		for (var file : files.entrySet()) {
			String relative = file.getKey();
			if (!relative.contains("/GUI_Item/") && !relative.startsWith("_shared/")) continue;
			for (Map.Entry<String, JsonElement> member : file.getValue().entrySet()) {
				if (!member.getValue().isJsonArray()) continue;
				JsonArray records = member.getValue().getAsJsonArray();
				for (int i = 0; i + 1 < records.size(); i++) {
					if (!records.get(i).isJsonObject() || !records.get(i + 1).isJsonObject()) continue;
					JsonObject head = records.get(i).getAsJsonObject(), tail = records.get(i + 1).getAsJsonObject();
					if (!head.has("text") || !tail.has("continuation") || !tail.get("continuation").getAsBoolean()) continue;
					String text = Glyphs.canonical(head.get("text").getAsString()).trim();
					if (!text.isEmpty() && index.lookup(Surface.LORE, text) != null) overlapping.add(relative + "#" + head.get("id").getAsString());
				}
			}
		}
		check("整句记录不与带尾行的旧首行同文 " + overlapping, overlapping.isEmpty());
		// New records never enter ITEM: ability names must not start translating equipment names.
		check("新 Lore 记录不参与物品名", index.entries(Surface.ITEM).stream()
			.noneMatch(e -> e.sourceFile().contains("/GUI_Lore/")));
		for (String name : List.of("Hyperion", "Terminator", "Hollow Wand", "Salvation", "Legendary Treasure"))
			check("物品名保持原样 " + name, TooltipTranslator.translateItemName(Component.literal(name)).padded().getString().equals(name));
		Component known = Component.literal("§7Can damage endermen.");
		check("已译 Lore 不作为未翻译采集", Classifier.of(CaptureSurface.GUI_LORE, StyledText.of(known)) == null);
		check("新词表只用于小人资源", index.terms().translate("item_name", "acacia logs") == null
			&& "金合欢原木".equals(index.terms().translate("minion_resource", "acacia logs")));
		check("已译整句仍报告真实颜色丢失", Classifier.of(CaptureSurface.GUI_LORE,
			StyledText.of(Component.literal("§7Can damage §cendermen."))).bucket() == Classifier.Bucket.COLOUR);
		TranslationIndex bounded = new TranslationIndex();
		String thirteen = String.join(" ", Collections.nCopies(13, "boundary"));
		addTestEntry(bounded, thirteen);
		check("最多检查十二行", LoreMatcher.find(bounded, Collections.nCopies(13, Component.literal("boundary")), 0) == null);
		String oversized = "x".repeat(LoreMatcher.MAX_CHARS + 1);
		addTestEntry(bounded, oversized);
		check("超长文本不进入整句匹配", LoreMatcher.find(bounded, List.of(Component.literal(oversized)), 0) == null);
		addTestEntry(bounded, "alpha"); addTestEntry(bounded, "alpha beta");
		check("优先完整较长记录", LoreMatcher.find(bounded,
			List.of(Component.literal("alpha"), Component.literal("beta")), 0).lines() == 2);
		check("负数起始索引不匹配", LoreMatcher.find(index, List.of(known), -1) == null);
		check("越界起始索引不匹配", LoreMatcher.find(index, List.of(known), 1) == null);
		// Like production, build a fresh index before querying it; an index caches misses.
		TranslationIndex edges = new TranslationIndex();
		addTestEntry(edges, String.join(" ", Collections.nCopies(12, "boundary")));
		String limit = "x".repeat(LoreMatcher.MAX_CHARS);
		addTestEntry(edges, limit);
		LoreMatcher.Match twelve = LoreMatcher.find(edges, Collections.nCopies(12, Component.literal("boundary")), 0);
		check("恰好十二行可以匹配", twelve != null && twelve.lines() == 12);
		check("恰好2048字符可以匹配", LoreMatcher.find(edges, List.of(Component.literal(limit)), 0) != null);
		check("审计不把列表局部颜色丢失计为覆盖", !NeuLoreAudit.coveredList(
			Component.literal("§9Protec§ction V, §9Sharpness V"), index));
		check("审计不把局部翻译的未知列表计为覆盖", !NeuLoreAudit.coveredList(
			Component.literal("§9Protection V, §9Unknown V"), index));
		check("审计接受完整正常的附魔列表", NeuLoreAudit.coveredList(
			Component.literal("§9Protection V, §9Sharpness V"), index));
		TranslationEntry badTail = TranslationEntry.compile(new TranslationEntry.Definition(
			"tail-test", "test", List.of(new TranslationEntry.Segment("tail text", "尾行")),
			Map.of(), new TranslationEntry.Options(true, "", Set.of(), "")
		));
		StyledText tailText = StyledText.of(Component.literal("§7tail §ctext"));
		check("审计不豁免 continuation 的颜色错误", !NeuLoreAudit.coveredLegacy(
			new Translator.Located(badTail, tailText, badTail.match(tailText.canonical())), index, true));
		System.out.printf("Lore checks=%d, failures=%d%n", checked, failures.size());
		for (String failure : failures) System.err.println("FAIL " + failure);
		if (!failures.isEmpty()) throw new AssertionError("Lore regression failed");
	}

	private static void addTestEntry(TranslationIndex index, String text) {
		TranslationEntry entry = TranslationEntry.compile(new TranslationEntry.Definition(
			"boundary-test", "test", List.of(new TranslationEntry.Segment(text, "测试")),
			Map.of(), TranslationEntry.Options.DEFAULT
		));
		index.add(Surface.LORE, text, entry);
	}

	private static List<JsonObject> records(JsonObject file) {
		List<JsonObject> records = new ArrayList<>();
		for (Map.Entry<String, JsonElement> member : file.entrySet()) {
			JsonElement value = member.getValue();
			if (!value.isJsonArray()) continue;
			for (JsonElement element : value.getAsJsonArray()) {
				if (element.isJsonObject() && element.getAsJsonObject().has("id"))
					records.add(element.getAsJsonObject());
			}
		}
		return records;
	}

	private static final Pattern TOKEN = Pattern.compile("%(?:\\d+\\$)?[sd]|%%");
	private static final Map<String, String> PET_STATS = Map.of(
		"SEA_CREATURE_CHANCE", "Sea Creature Chance", "DEFENSE", "Defense", "HEALTH", "Health",
		"ABILITY_DAMAGE", "Ability Damage", "CRIT_DAMAGE", "Crit Damage", "SPEED", "Speed",
		"INTELLIGENCE", "Intelligence", "OVERBLOOM", "Overbloom");
	private static final Pattern PET_PARAMETER = Pattern.compile("\\{(?:[0-9]+|"
		+ String.join("|", new TreeSet<>(PET_STATS.keySet())) + ")}");
	private record Sample(List<Component> lines, String origin) {}

	private static Map<String, JsonObject> placeholders(JsonObject r) {
		Map<String, JsonObject> result = new LinkedHashMap<>();
		if (r.has("placeholders")) for (JsonElement el : r.getAsJsonArray("placeholders")) {
			JsonObject p = el.getAsJsonObject();
			String token = p.get("token").getAsString();
			if (result.put(token, p) != null) throw new IllegalArgumentException("duplicate token " + token);
		}
		return result;
	}

	private static boolean neuPet(JsonObject r) {
		if (r.has("source_items")) for (JsonElement item : r.getAsJsonArray("source_items"))
			if (item.getAsString().matches("[A-Z0-9_]+;[0-5]")) return true;
		return false;
	}

	/** Only NEU pet parameters in typed slots are instantiated; named stats also require their own label. */
	private static String value(JsonObject r, JsonObject p, String original, List<String> notes) {
		String type = p.has("type") ? p.get("type").getAsString() : "raw";
		Capture capture = Capture.of(type);
		String value = original;
		if (PET_PARAMETER.matcher(value).find()) {
			if (!neuPet(r) || capture != Capture.NUMBER && capture != Capture.MULTIPLIER_INCREASE
				&& capture != Capture.TIER)
				throw new IllegalArgumentException("unapproved pet parameter " + original + " type=" + type);
			Matcher parameter = PET_PARAMETER.matcher(value);
			while (parameter.find()) {
				String name = parameter.group().substring(1, parameter.group().length() - 1);
				if (PET_STATS.containsKey(name) && (capture != Capture.NUMBER
					|| !r.get("text").getAsString().startsWith(PET_STATS.get(name) + ": ")))
					throw new IllegalArgumentException("stat parameter disagrees with label/type: " + name);
			}
			String replacement = capture == Capture.TIER ? "III" : "10";
			value = PET_PARAMETER.matcher(value).replaceAll(replacement);
			notes.add(p.get("token").getAsString() + ":" + original + "->" + value + " (" + type + ")");
		}
		String plain = StyledText.of(Component.literal(value)).canonical();
		if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0
			|| !Pattern.matches(capture.regex(), plain) || !capture.accepts(plain))
			throw new IllegalArgumentException("invalid " + p.get("token") + "=" + original + " type=" + type);
		return value;
	}

	private static Sample exampleSample(JsonObject r) {
		List<String> notes = new ArrayList<>();
		Map<String, String> values = new LinkedHashMap<>();
		for (var p : placeholders(r).entrySet()) {
			if (!p.getValue().has("example")) throw new IllegalArgumentException("missing example " + p.getKey());
			values.put(p.getKey(), value(r, p.getValue(), p.getValue().get("example").getAsString(), notes));
		}
		String raw = r.has("raw") ? r.get("raw").getAsString() : r.get("text").getAsString();
		String rendered = TOKEN.matcher(raw).replaceAll(x -> {
			if (x.group().equals("%%")) return "%";
			String v = values.get(x.group());
			if (v == null) throw new IllegalArgumentException("missing placeholder " + x.group());
			return Matcher.quoteReplacement(v);
		});
		// This is a constructed offline example, not a claim about current game values.
		Sample sample = sourceSample(r, List.of(rendered), "offline raw/example");
		return new Sample(sample.lines(), sample.origin() + (notes.isEmpty() ? "" : " synthetic " + notes));
	}

	private static Sample sourceSample(JsonObject r, List<String> original, String origin) {
		List<String> lines = new ArrayList<>(original);
		List<String> notes = new ArrayList<>();
		// Archived NEU extraction already replaced this tier with numeric 10. Repair only that
		// identified source sample, not raw/example or arbitrary invalid tier fixtures.
		if (origin.startsWith("samples[") && "neu_63be85c6714f".equals(r.get("id").getAsString())
			&& r.has("source_items") && r.getAsJsonArray("source_items").contains(new JsonPrimitive("SILVERFISH;4"))
			&& placeholders(r).containsKey("%2$s")
			&& "tier".equals(placeholders(r).get("%2$s").get("type").getAsString())) {
			for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("Haste 10")) {
				lines.set(i, lines.get(i).replace("Haste 10", "Haste III"));
				notes.add("archived Haste 10->Haste III (synthetic tier; not ingame evidence)");
			}
		}
		String template = Glyphs.canonical(r.get("text").getAsString());
		String plain = String.join(" ", lines.stream()
			.map(s -> StyledText.of(Component.literal(s)).canonical().trim()).toList());
		Map<String, JsonObject> definitions = placeholders(r);
		StringBuilder regex = new StringBuilder();
		List<String> tokens = new ArrayList<>();
		Matcher token = TOKEN.matcher(template);
		int end = 0;
		while (token.find()) {
			regex.append(Pattern.quote(template.substring(end, token.start())));
			if (token.group().equals("%%")) regex.append('%');
			else {
				JsonObject p = definitions.get(token.group());
				if (p == null) throw new IllegalArgumentException("missing placeholder " + token.group());
				Capture capture = Capture.of(p.has("type") ? p.get("type").getAsString() : "raw");
				regex.append('(').append(capture.regex());
				if (neuPet(r) && (capture == Capture.NUMBER || capture == Capture.MULTIPLIER_INCREASE || capture == Capture.TIER))
					regex.append("|[+\\-]?").append(PET_PARAMETER.pattern()).append("[a-zA-Z%]{0,2}");
				regex.append(')'); tokens.add(token.group());
			}
			end = token.end();
		}
		regex.append(Pattern.quote(template.substring(end)));
		Matcher match = Pattern.compile(regex.toString()).matcher(plain);
		if (!match.matches()) throw new IllegalArgumentException("source does not align with text: " + original);
		Map<String, String> replacements = new LinkedHashMap<>();
		for (int i = 0; i < tokens.size(); i++) {
			JsonObject p = definitions.get(tokens.get(i));
			if (p == null) throw new IllegalArgumentException("missing placeholder " + tokens.get(i));
			String captured = match.group(i + 1);
			value(r, p, captured, notes);
			Matcher parameter = PET_PARAMETER.matcher(captured);
			while (parameter.find()) {
				String replacement = Capture.of(p.get("type").getAsString()) == Capture.TIER ? "III" : "10";
				String previous = replacements.put(parameter.group(), replacement);
				if (previous != null && !previous.equals(replacement))
					throw new IllegalArgumentException("conflicting types for " + parameter.group());
			}
		}
		List<Component> components = new ArrayList<>();
		for (String line : lines) {
			for (var replacement : replacements.entrySet()) line = line.replace(replacement.getKey(), replacement.getValue());
			if (line.indexOf('{') >= 0 || line.indexOf('}') >= 0)
				throw new IllegalArgumentException("unbound braces in " + line);
			components.add(Component.literal(line));
		}
		return new Sample(List.copyOf(components), origin + (notes.isEmpty() ? "" : " synthetic " + notes + " source=" + original));
	}

	private static void checkPreserved(String title, JsonObject record, List<Component> input, TranslationIndex index) {
		check(title + " translate:false 不命中翻译", LoreMatcher.find(index, input, 0) == null);
		List<LoreTranslation.Unit> plan = LoreTranslation.plan(input);
		check(title + " translate:false 原文原色且不吞行", plan.size() == input.size()
			&& java.util.stream.IntStream.range(0, input.size()).allMatch(i ->
				plan.get(i).start() == i && plan.get(i).source().size() == 1 && !plan.get(i).translated()
				&& sameStyled(input.get(i), plan.get(i).rendered())));
		String whole = String.join(" ", input.stream().map(c -> StyledText.of(c).canonical().trim()).toList());
		boolean exact = !TOKEN.matcher(record.get("text").getAsString()).find();
		check(title + " 仅精确原文有采集例外", index.preserved(Surface.LORE, whole) == exact);
		if (exact) check(title + " 不重复采集保留英文",
			Classifier.of(CaptureSurface.GUI_LORE, StyledText.of(Component.literal(whole))) == null);
	}

	/** Compare per-character styles too: legacy serialization alone cannot distinguish a reset. */
	private static boolean sameStyled(Component expected, Component actual) {
		StyledText a = StyledText.of(expected), b = StyledText.of(actual);
		if (!a.plain().equals(b.plain())) return false;
		for (int i = 0; i < a.length(); i++) if (!a.styleAt(i).equals(b.styleAt(i))) return false;
		return true;
	}

	private static String colourEvidence(JsonObject fixture) {
		int fragments = fixture.has("colour_fragments") ? fixture.getAsJsonArray("colour_fragments").size() : 0;
		return "expected_plain: text only; " + (fragments == 0 ? "no manual colour golden"
			: "manual colour golden limited to " + fragments + " fragment(s)");
	}

	/** A fragment must locate uniquely; otherwise a correctly coloured duplicate could hide a bad one. */
	private static List<String> colourFragmentDifferences(JsonObject fixture, Component rendered) {
		List<String> errors = new ArrayList<>();
		if (!fixture.has("colour_fragments")) return errors;
		StyledText actual = StyledText.of(rendered);
		for (JsonElement el : fixture.getAsJsonArray("colour_fragments")) {
			String raw = el.getAsString();
			StyledText fragment = StyledText.of(Component.literal(raw));
			String text = fragment.plain();
			if (text.isEmpty() || raw.indexOf('§') < 0) {
				errors.add("colour fragment requires styled nonempty text: " + raw); continue;
			}
			int start = actual.plain().indexOf(text);
			if (start < 0 || actual.plain().indexOf(text, start + 1) >= 0) {
				errors.add("colour fragment must locate uniquely (extend context): " + raw); continue;
			}
			if (!sameStyled(Component.literal(raw), actual.slice(start, start + text.length())))
				errors.add("colour fragment style mismatch: " + raw);
		}
		return errors;
	}

	/** units pins the entire font-free plan, including preserved/blank lines and independent titles. */
	private static List<String> planDifferences(JsonObject fixture, List<Component> input) {
		List<String> errors = new ArrayList<>();
		List<Component> before = input.stream().map(c -> (Component)c.copy()).toList();
		List<LoreTranslation.Unit> plan = LoreTranslation.plan(input);
		JsonArray expected = fixture.getAsJsonArray("units");
		if (plan.size() != expected.size()) errors.add("unit count expected=" + expected.size() + " actual=" + plan.size());
		int cursor = 0;
		for (int i = 0; i < expected.size(); i++) {
			JsonObject e = expected.get(i).getAsJsonObject();
			int start = e.has("start") ? e.get("start").getAsInt() : cursor;
			int consumed = e.get("consumed").getAsInt();
			if (start != cursor || consumed < 1) errors.add("invalid fixture boundary unit=" + i);
			cursor += consumed;
			if (i >= plan.size()) continue;
			LoreTranslation.Unit unit = plan.get(i);
			if (unit.start() != start || unit.source().size() != consumed
				|| e.has("complete") && unit.complete() != e.get("complete").getAsBoolean()
				|| e.has("translated") && unit.translated() != e.get("translated").getAsBoolean())
				errors.add("boundary/state unit=" + i + " start=" + unit.start() + " consumed=" + unit.source().size());
			for (int j = 0; j < unit.source().size(); j++) {
				int sourceLine = unit.start() + j;
				if (sourceLine < 0 || sourceLine >= input.size() || !sameStyled(input.get(sourceLine), unit.source().get(j)))
					errors.add("source provenance unit=" + i + " line=" + j);
			}
			if (e.has("expected") == e.has("expected_plain")) {
				errors.add("unit=" + i + " must specify exactly one of expected/expected_plain"); continue;
			}
			boolean plainOnly = e.has("expected_plain");
			String text = e.get(plainOnly ? "expected_plain" : "expected").getAsString();
			for (TranslationEntry.Matched matched : unit.matches())
				if (matched.entry().losesColour(matched.source(), matched.match()))
					errors.add("automatic colour loss unit=" + i + " record=" + matched.entry().id());
			for (String error : colourFragmentDifferences(e, unit.rendered())) errors.add("unit=" + i + " " + error);
			if (plainOnly ? text.indexOf('§') >= 0 || !text.equals(StyledText.of(unit.rendered()).plain())
				: !sameStyled(Component.literal(text), unit.rendered()))
				errors.add((plainOnly ? "text only (no colour validation)" : "text/style") + " unit=" + i
					+ " expected=" + text + " actual=" + TranslationHarness.legacy(unit.rendered()));
			if (e.has("records")) {
				List<String> records = new ArrayList<>();
				for (JsonElement record : e.getAsJsonArray("records")) records.add(record.getAsString());
				List<String> actual = unit.matches().stream().map(m -> m.entry().sourceFile() + "#" + m.entry().id()).toList();
				if (!records.equals(actual)) errors.add("records unit=" + i + " expected=" + records + " actual=" + actual);
			}
		}
		if (cursor != input.size()) errors.add("fixture must cover every input line");
		for (int i = 0; i < input.size(); i++) if (!sameStyled(before.get(i), input.get(i))) errors.add("input mutated line=" + i);
		return errors;
	}
	private static void checkHarnessRegressions(TranslationIndex corpus) throws Exception {
		int firstCheck = checked, firstFailure = failures.size();
		JsonObject pet = JsonParser.parseString("""
			{"id":"pet-test","text":"Grants %s speed.","raw":"§7Grants §a%s§7 speed.",
			 "source_items":["BEE;4"],"placeholders":[{"token":"%s","type":"number","example":"{4}%"}]}
			""").getAsJsonObject();
		String original = pet.toString();
		check("离线宠物参数按 number 实例化并标注合成来源", exampleSample(pet).origin().contains("synthetic")
			&& exampleSample(pet).lines().getFirst().getString().contains("10%") && original.equals(pet.toString()));
		check("宠物原始多行只替换有类型参数", sourceSample(pet,
			List.of("§7Grants §a{4}%", "§7speed."), "samples[0]").lines().getFirst().getString().contains("10%"));
		JsonObject stat = pet.deepCopy();
		stat.addProperty("text", "Ability Damage: +%s");
		stat.addProperty("raw", "§7Ability Damage: §a+%s");
		stat.getAsJsonArray("placeholders").get(0).getAsJsonObject().addProperty("example", "{ABILITY_DAMAGE}");
		check("具名 NEU 属性参数保留类型与来源", exampleSample(stat).origin().contains("{ABILITY_DAMAGE}->10"));
		JsonObject wrongStat = stat.deepCopy();
		wrongStat.addProperty("text", "Health: +%s");
		rejectSample("具名属性参数不越过原属性标签", () -> exampleSample(wrongStat));
		JsonObject haste = JsonParser.parseString("""
			{"id":"neu_63be85c6714f","source_items":["SILVERFISH;4"],
			 "text":"Grants %1$s speed and permanent Haste %2$s.",
			 "placeholders":[{"token":"%1$s","type":"number","example":"+10"},
			 {"token":"%2$s","type":"tier","example":"III"}]}
			""").getAsJsonObject();
		String archived = "Grants +10 speed and permanent Haste 10.";
		Sample syntheticHaste = sourceSample(haste, List.of(archived), "samples[0]");
		check("Haste 旧离线10仅标注合成III而非实测", syntheticHaste.origin().contains("synthetic tier; not ingame evidence")
			&& syntheticHaste.origin().contains(archived) && syntheticHaste.lines().getFirst().getString().contains("Haste III"));
		haste.getAsJsonArray("placeholders").get(1).getAsJsonObject().addProperty("example", "10");
		rejectSample("Haste 自造 example 10 也不使用来源例外", () -> exampleSample(haste));
		JsonObject invalid = pet.deepCopy();
		invalid.getAsJsonArray("placeholders").get(0).getAsJsonObject().addProperty("example", "not a number");
		rejectSample("非法自造 number example 不兜底", () -> exampleSample(invalid));
		JsonObject tier = pet.deepCopy();
		tier.getAsJsonArray("placeholders").get(0).getAsJsonObject().addProperty("type", "tier");
		tier.getAsJsonArray("placeholders").get(0).getAsJsonObject().addProperty("example", "10");
		rejectSample("非法自造 tier 10 不兜底", () -> exampleSample(tier));
		JsonObject raw = pet.deepCopy();
		raw.addProperty("raw", "Grants broken speed.");
		rejectSample("非法自造 raw 不兜底", () -> exampleSample(raw));
		rejectSample("非数字花括号不盲目替换", () -> sourceSample(pet, List.of("Grants {name} speed."), "samples[0]"));
		JsonObject nonPet = pet.deepCopy();
		nonPet.remove("source_items");
		rejectSample("非宠物来源不能推定 NEU 参数", () -> exampleSample(nonPet));
		JsonObject phrase = pet.deepCopy();
		phrase.getAsJsonArray("placeholders").get(0).getAsJsonObject().addProperty("type", "raw");
		rejectSample("raw 类型不能推定数字参数", () -> exampleSample(phrase));

		JsonObject fragments = JsonParser.parseString("""
			{"colour_fragments":["§6§l传奇", "§7时，"]}
			""").getAsJsonObject();
		check("局部颜色片段接受原色粗体和连接词重置", colourFragmentDifferences(fragments,
			Component.literal("前§6§l传奇§7时，后")).isEmpty());
		check("局部颜色片段拒绝颜色错误", !colourFragmentDifferences(fragments,
			Component.literal("前§a§l传奇§7时，后")).isEmpty());
		check("局部颜色片段拒绝粗体丢失", !colourFragmentDifferences(fragments,
			Component.literal("前§6传奇§7时，后")).isEmpty());
		check("局部颜色片段拒绝有歧义的重复文字", !colourFragmentDifferences(fragments,
			Component.literal("§6§l传奇§7时，后§c传奇")).isEmpty());
		check("局部颜色片段拒绝缺失文字", !colourFragmentDifferences(fragments,
			Component.literal("只有其它文字")).isEmpty());
		JsonObject fixture = JsonParser.parseString("""
			{"units":[
			 {"start":0,"consumed":1,"expected":"§6测试","complete":true,"translated":true},
			 {"start":1,"consumed":1,"expected":"§a测试","complete":true,"translated":true},
			 {"start":2,"consumed":1,"expected":"","complete":false,"translated":false},
			 {"start":3,"consumed":1,"expected":"§7Unknown fixture tail","complete":false,"translated":false}
			]}
			""").getAsJsonObject();
		List<Component> lines = List.of(Component.literal("§6Fixture title"), Component.literal("§aFixture stat"),
			Component.literal(""), Component.literal("§7Unknown fixture tail"));
		try {
			TranslationIndex separate = new TranslationIndex();
			addTestEntry(separate, "Fixture title"); addTestEntry(separate, "Fixture stat");
			TranslationHarness.installIndex(separate);
			check("整组夹具接受独立标题属性和未译尾行", planDifferences(fixture, lines).isEmpty());
			JsonObject flattened = JsonParser.parseString("""
				{"units":[{"consumed":1,"expected_plain":"测试"}]}
				""").getAsJsonObject();
			check("纯文字期望仍自动拒绝源颜色压平", planDifferences(flattened,
				List.of(Component.literal("§6Fixture §atitle"))).contains("automatic colour loss unit=0 record=boundary-test"));
			JsonObject minimal = fixture.deepCopy();
			for (JsonElement el : minimal.getAsJsonArray("units")) {
				JsonObject unit = el.getAsJsonObject();
				unit.remove("start"); unit.remove("complete"); unit.remove("translated");
			}
			check("简写整组夹具仍按累计消费检查边界", planDifferences(minimal, lines).isEmpty());
			JsonObject plainUnit = minimal.getAsJsonArray("units").get(1).getAsJsonObject();
			plainUnit.remove("expected"); plainUnit.addProperty("expected_plain", "测试");
			check("expected_plain 显式仅校验文字不要求颜色", planDifferences(minimal, lines).isEmpty());
			plainUnit.add("colour_fragments", JsonParser.parseString("[\"§a测试\"]"));
			check("整组纯文字加局部颜色期望", planDifferences(minimal, lines).isEmpty());
			plainUnit.add("colour_fragments", JsonParser.parseString("[\"§c测试\"]"));
			check("整组局部颜色不能被纯文字期望绕过", !planDifferences(minimal, lines).isEmpty());
			plainUnit.remove("colour_fragments");
			plainUnit.addProperty("expected_plain", "错误");
			check("expected_plain 仍拒绝错误文字", !planDifferences(minimal, lines).isEmpty());
			plainUnit.addProperty("expected_plain", "§a测试");
			check("expected_plain 不允许冒充颜色期望", !planDifferences(minimal, lines).isEmpty());
			plainUnit.addProperty("expected_plain", "测试"); plainUnit.addProperty("expected", "§a测试");
			check("整组夹具不能同时指定两种期望", !planDifferences(minimal, lines).isEmpty());
			JsonObject wrongColour = fixture.deepCopy();
			wrongColour.getAsJsonArray("units").get(1).getAsJsonObject().addProperty("expected", "§c测试");
			check("整组夹具拒绝属性染色", !planDifferences(wrongColour, lines).isEmpty());
			JsonObject wrongText = fixture.deepCopy();
			wrongText.getAsJsonArray("units").get(1).getAsJsonObject().addProperty("expected", "§a错误");
			check("整组夹具拒绝非首行文字错误", !planDifferences(wrongText, lines).isEmpty());
			JsonObject missingTail = fixture.deepCopy();
			missingTail.getAsJsonArray("units").remove(3);
			check("整组夹具拒绝遗漏未译尾行", !planDifferences(missingTail, lines).isEmpty());
			TranslationIndex merged = new TranslationIndex();
			addTestEntry(merged, "Fixture title Fixture stat");
			TranslationHarness.installIndex(merged);
			check("整组夹具拒绝最长记录吞并独立层级", !planDifferences(fixture, lines).isEmpty());
			JsonObject preserved = JsonParser.parseString("""
				{"records":[{"id":"preserved-test","text":"Preserved fixture words","translate":false,"zh":"不能出现"},
				{"id":"preserved-template","text":"Preserved fixture %s","translate":false,"zh":"不能出现"}]}
				""").getAsJsonObject();
			TranslationIndex english = TranslationLoader.compile(Map.of("Test/GUI_Lore/Fixture.json", preserved));
			TranslationHarness.installIndex(english);
			checkPreserved("translate:false 自测", preserved.getAsJsonArray("records").get(0).getAsJsonObject(),
				List.of(Component.literal("§7Preserved fixture words")), english);
			check("不翻译模板不是通用采集豁免", !english.preserved(Surface.LORE, "Preserved fixture unknown")
				&& Classifier.of(CaptureSurface.GUI_LORE, StyledText.of(Component.literal("Preserved fixture unknown"))) != null);
		} finally {
			TranslationHarness.installIndex(corpus);
		}
		System.out.printf("Harness self-checks=%d, failures=%d%n", checked - firstCheck, failures.size() - firstFailure);
	}

	private static void rejectSample(String title, Runnable sample) {
		try { sample.run(); check(title, false); }
		catch (IllegalArgumentException expected) { check(title, true); }
	}
	private static void check(String title, boolean ok) { checked++; if (!ok) failures.add(title); }
}
