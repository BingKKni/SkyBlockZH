package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.network.chat.Component;

/**
 * Proves the corpus that ships is the corpus that was authored.
 *
 * <p>The build strips the fields only a translator reads — {@code context}, {@code gloss},
 * {@code raw}, {@code segments[].color}, {@code placeholders[].desc} — and drops the records the
 * loader would throw away anyway, which takes the shipped corpus to roughly a third of its size.
 * Every one of those decisions is a claim about what the engine does not read, and a wrong claim
 * would not fail to build: it would quietly stop translating some lines, in a jar, on someone else's
 * machine.
 *
 * <p>So both corpora are compiled and the two indexes compared record by record: same surfaces, same
 * ids, same template, same continuation and layout flags, and — the part that matters most — the same
 * record answering for every line either of them can answer for.
 */
public final class MinifiedCorpusCheck {
	private static int failures;

	private MinifiedCorpusCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("用法: MinifiedCorpusCheck <original_text 目录> <精简后目录>");
			System.exit(2);
			return;
		}

		Map<String, JsonObject> authoredFiles = TranslationHarness.readCorpus(Path.of(args[0]));
		Map<String, JsonObject> shippedFiles = TranslationHarness.readCorpus(Path.of(args[1]));

		System.out.println("原始语料文件: " + authoredFiles.size() + "，精简后: " + shippedFiles.size());

		TranslationIndex authored = TranslationLoader.compile(authoredFiles);
		TranslationIndex shipped = TranslationLoader.compile(shippedFiles);

		check("可用记录总数一致", authored.size() + " 条", shipped.size() + " 条");

		for (Surface surface : Surface.values()) {
			check("渲染面 " + surface + " 的记录数一致",
				String.valueOf(authored.size(surface)), String.valueOf(shipped.size(surface)));
		}

		// The word "SkyBlock" and the value dictionary are read by name rather than walked with the
		// records, so a stripped key there would go unnoticed by a count. Compared by what they draw
		// rather than by their fields: the full name, the compound short form, and the stoplist that
		// decides between them all show up in these three lines.
		for (String line : List.of("SkyBlock", "SkyBlock Level 42", "Welcome to SkyBlock 的世界")) {
			check("SkyBlock 译名行为一致 [" + line + "]",
				TranslationHarness.legacy(authored.skyBlockName().apply(StyledText.of(Component.literal(line)))),
				TranslationHarness.legacy(shipped.skyBlockName().apply(StyledText.of(Component.literal(line)))));
		}

		for (String value : List.of("Collections", "Combat", "Farming", "Recipe Book", "Other Crystals",
			"Black Cat", "Dwarven Mines", "DONE")) {
			check("词表仍答得出 " + value,
				String.valueOf(authored.terms().translate("category_name", value)),
				String.valueOf(shipped.terms().translate("category_name", value)));
			check("词表(mob_name)仍答得出 " + value,
				String.valueOf(authored.terms().translate("mob_name", value)),
				String.valueOf(shipped.terms().translate("mob_name", value)));
		}

		// Every record, by id, on every surface: the shipped index must hold the same set.
		for (Surface surface : Surface.values()) {
			Map<String, TranslationEntry> left = byId(authored, surface);
			Map<String, TranslationEntry> right = byId(shipped, surface);

			List<String> missing = new ArrayList<>(new TreeSet<>(left.keySet()));
			missing.removeAll(right.keySet());

			List<String> extra = new ArrayList<>(new TreeSet<>(right.keySet()));
			extra.removeAll(left.keySet());

			if (!missing.isEmpty()) {
				report(surface + " 精简后丢了记录", false, String.join(", ", missing.subList(0, Math.min(12, missing.size()))));
			}

			if (!extra.isEmpty()) {
				report(surface + " 精简后多出记录", false, String.join(", ", extra.subList(0, Math.min(12, extra.size()))));
			}

			for (Map.Entry<String, TranslationEntry> entry : left.entrySet()) {
				TranslationEntry other = right.get(entry.getKey());

				if (other == null) {
					continue;
				}

				TranslationEntry mine = entry.getValue();

				if (!mine.template().equals(other.template())
					|| mine.continuation() != other.continuation()
					|| !String.valueOf(mine.layout()).equals(String.valueOf(other.layout()))
					|| mine.specificity() != other.specificity()) {
					report("记录 " + entry.getKey() + " 精简前后不一致", false,
						"模板 [" + mine.template() + "] vs [" + other.template() + "]，"
							+ "continuation " + mine.continuation() + " vs " + other.continuation() + "，"
							+ "layout " + mine.layout() + " vs " + other.layout());
				}
			}
		}

		// The decisive test: feed every record its own English back through both indexes and insist the
		// same record answers, drawing the same thing. A dropped field that mattered shows up here even
		// if the counts happened to match.
		int compared = 0;

		for (Surface surface : Surface.values()) {
			for (TranslationEntry entry : authored.entries(surface)) {
				String plain = entry.template();

				if (plain.isEmpty() || plain.indexOf('%') >= 0) {
					// A template with a placeholder cannot be looked up by its own text — the text is
					// not a line the server would send. The id comparison above already covers them.
					continue;
				}

				TranslationEntry mine = authored.lookup(surface, plain);
				TranslationEntry other = shipped.lookup(surface, plain);
				compared++;

				String left = mine == null ? "(无)" : mine.id();
				String right = other == null ? "(无)" : other.id();

				if (!left.equals(right)) {
					report("同一行文本在精简前后匹配到不同记录", false,
						surface + " [" + plain + "] -> " + left + " vs " + right);
				}
			}
		}

		System.out.println();
		System.out.println("逐行比对了 " + compared + " 条固定文本");

		if (failures == 0) {
			System.out.println("精简后的语料与原始语料编译结果完全一致。");
			return;
		}

		System.out.println("有 " + failures + " 项不一致，精简规则动到了引擎会读的字段。");
		System.exit(1);
	}

	private static Map<String, TranslationEntry> byId(TranslationIndex index, Surface surface) {
		Map<String, TranslationEntry> byId = new LinkedHashMap<>();

		for (TranslationEntry entry : index.entries(surface)) {
			byId.put(entry.id(), entry);
		}

		return byId;
	}

	private static void check(String name, String expected, String actual) {
		report(name, expected.equals(actual), "原始 [" + expected + "] 精简后 [" + actual + "]");
	}

	private static void report(String name, boolean passed, String detail) {
		if (passed) {
			System.out.println("  [通过] " + name);
			return;
		}

		failures++;
		System.out.println("  [失败] " + name + " — " + detail);
	}
}
