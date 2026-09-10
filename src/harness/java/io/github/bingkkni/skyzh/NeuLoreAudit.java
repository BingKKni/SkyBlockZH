package io.github.bingkkni.skyzh;

import com.google.gson.*;
import io.github.bingkkni.skyzh.text.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.network.chat.Component;

/** Offline NEU inventory audit. A match is not a claim of semantic correctness or live verification. */
public final class NeuLoreAudit {
	public static void main(String[] args) throws Exception {
		if (args.length != 3) throw new IllegalArgumentException("NeuLoreAudit <corpus> <NEU items> <report.json>");
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(index);
		JsonArray result = new JsonArray();
		int total = 0, complete = 0, allLines = 0, coveredLines = 0, newLines = 0;
		try (var walk = Files.list(Path.of(args[1]))) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				JsonObject item = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				List<Component> lore = new ArrayList<>();
				boolean synthetic = false;
				if (item.has("lore")) for (JsonElement raw : item.getAsJsonArray("lore")) {
					String text = raw.getAsString();
					synthetic |= text.matches(".*\\{\\w+}.*");
					lore.add(Component.literal(text.replaceAll("\\{\\w+}", "10")));
				}
				JsonArray missing = new JsonArray();
				int words = 0, covered = 0, fresh = 0;
				boolean merging = false;
				for (int i = 0; i < lore.size(); i++) {
					LoreMatcher.Match match = LoreMatcher.find(index, lore, i);
					if (match != null) {
						boolean valid = !match.entry().losesColour(match.core(), match.matcher())
							&& !match.entry().mixed(match.core(), match.matcher(), index.terms()).any();
						for (int j = i; j < i + match.lines(); j++) {
							if (!hasWords(lore.get(j))) continue;
							words++;
							if (valid) { covered++; fresh++; }
							else missing.add(StyledText.of(lore.get(j)).plain());
						}
						i += match.lines() - 1;
						merging = false;
						continue;
					}
					Component line = lore.get(i);
					if (!hasWords(line)) { merging = false; continue; }
					words++;
					Translator.Located found = Translator.locate(line, Surface.ITEM);
					boolean valid = coveredLegacy(found, index, merging);
					if (!found.matched()) valid = coveredList(line, index);
					if (valid) covered++; else missing.add(StyledText.of(line).plain());
					merging = valid;
				}
				total++;
				if (words == covered) complete++;
				allLines += words; coveredLines += covered; newLines += fresh;
				JsonObject row = new JsonObject();
				row.addProperty("item", file.getFileName().toString().replace(".json", ""));
				row.addProperty("word_lines", words); row.addProperty("covered_lines", covered);
				row.addProperty("new_sentence_lines", fresh); row.addProperty("synthetic_pet_values", synthetic);
				row.add("missing", missing); result.add(row);
			}
		}
		JsonObject report = new JsonObject();
		report.addProperty("items", total); report.addProperty("all_word_lines_matched", complete);
		report.addProperty("word_lines", allLines); report.addProperty("covered_lines", coveredLines);
		report.addProperty("new_sentence_lines", newLines);
		report.addProperty("caveat", "Conservative record/style audit, not semantic or live verification. Every counted list part and continuation is checked for missing translations and colour loss. Deliberately preserved names without a matcher count as misses. Name lines excluded. Pet placeholders sampled at 10. No claims of 100% effect coverage.");
		report.add("inventory", result);
		Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report), StandardCharsets.UTF_8);
		System.out.printf("NEU items=%d, all word lines matched=%d; lines=%d/%d, new=%d%n", total, complete, coveredLines, allLines, newLines);
	}

	/** A tail needs a covered head AND must not lose its own translated fragments/styles. */
	static boolean coveredLegacy(Translator.Located found, TranslationIndex index, boolean merging) {
		return found.matched() && (!found.entry().continuation() || merging)
			&& !found.entry().mixed(found.core(), found.match(), index.terms()).any()
			&& !found.entry().losesColour(found.core(), found.match());
	}

	/** A partly translated list is not evidence that the whole line was covered. */
	static boolean coveredList(Component source, TranslationIndex index) {
		StyledText styled = StyledText.of(source);
		List<LineShape.Range> pieces = LineShape.enchantments(styled.canonical());
		if (pieces.isEmpty()) return false;
		for (LineShape.Range range : pieces) {
			Translator.Located found = Translator.locate(styled.sub(range.start(), range.end()), Surface.ITEM);
			if (!coveredLegacy(found, index, false)) return false;
		}
		return true;
	}

	private static boolean hasWords(Component c) {
		return StyledText.of(c).plain().matches(".*[A-Za-z].*");
	}
}
