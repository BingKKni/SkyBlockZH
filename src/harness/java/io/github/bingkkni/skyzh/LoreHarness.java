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
			for (JsonElement el : file.getValue().getAsJsonArray("lines")) {
				JsonObject r = el.getAsJsonObject();
				String id = r.get("id").getAsString();
				String raw = sample(r);
				List<List<Component>> samples = new ArrayList<>();
				samples.add(List.of(Component.literal(raw)));
				if (r.has("samples")) for (JsonElement variant : r.getAsJsonArray("samples")) {
					List<Component> lines = new ArrayList<>();
					for (JsonElement line : variant.getAsJsonArray())
						lines.add(Component.literal(line.getAsString().replaceAll("\\{\\w+}", "10")));
					samples.add(lines);
				}
				for (List<Component> input : samples) {
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
							&& rendered.equals(TranslationHarness.legacy(reflow.render(index.terms(), false))));
					}
				}
			}
		}
		JsonArray fixtures = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
		for (JsonElement el : fixtures) {
			JsonObject f = el.getAsJsonObject();
			List<Component> input = new ArrayList<>();
			for (JsonElement line : f.getAsJsonArray("lines")) input.add(Component.literal(line.getAsString()));
			LoreMatcher.Match match = LoreMatcher.find(index, input, 0);
			String title = f.get("name").getAsString();
			if (f.has("miss") && f.get("miss").getAsBoolean()) {
				check(title, match == null); continue;
			}
			String expected = TranslationHarness.legacy(Component.literal(f.get("expected").getAsString()));
			String actual = match == null ? "<no match>" : TranslationHarness.legacy(match.render(index.terms(), false));
			check(title + " expected=" + expected + " actual=" + actual, expected.equals(actual));
			if (match != null) check(title + " 只消费已匹配行", match.lines() == f.get("consumed").getAsInt());
		}
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
		TranslationEntry badTail = TranslationEntry.compile("tail-test", "test", List.of("tail text"),
			List.of("尾行"), true, "", Map.of(), Map.of());
		StyledText tailText = StyledText.of(Component.literal("§7tail §ctext"));
		check("审计不豁免 continuation 的颜色错误", !NeuLoreAudit.coveredLegacy(
			new Translator.Located(badTail, tailText, badTail.match(tailText.canonical())), index, true));
		System.out.printf("Lore checks=%d, failures=%d%n", checked, failures.size());
		for (String failure : failures) System.err.println("FAIL " + failure);
		if (!failures.isEmpty()) throw new AssertionError("Lore regression failed");
	}

	private static void addTestEntry(TranslationIndex index, String text) {
		TranslationEntry entry = TranslationEntry.compile("boundary-test", "test", List.of(text),
			List.of("测试"), false, "", Map.of(), Map.of());
		index.add(Surface.LORE, text, entry);
	}

	private static String sample(JsonObject r) {
		String raw = r.get("raw").getAsString();
		Map<String,String> values = new HashMap<>();
		for (JsonElement el : r.getAsJsonArray("placeholders")) {
			JsonObject p = el.getAsJsonObject(); values.put(p.get("token").getAsString(), p.get("example").getAsString());
		}
		Matcher m = Pattern.compile("%\\d+\\$s").matcher(raw);
		return m.replaceAll(x -> Matcher.quoteReplacement(values.get(x.group())));
	}
	private static void check(String title, boolean ok) { checked++; if (!ok) failures.add(title); }
}
