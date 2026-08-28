package io.github.bingkkni.skyzh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Runs the lines a session captured back through the corpus and prints what each one draws as now.
 *
 * <p>The other half of {@code captureUntranslated}. That switch writes down every line the corpus
 * could not answer for; this reads those files back and says which of them a new batch of records
 * actually fixed — and, more usefully, what the fix looks like. A record can match and still be
 * wrong: segments in the wrong order read backwards, a hand-written space lands between two Chinese
 * characters, a term nobody added leaves half the line English. None of that shows up in
 * {@code checkTranslations}, which only knows what the corpus says about itself, and all of it is
 * obvious the moment the line is printed with its colour codes on.
 *
 * <pre>
 *   ./gradlew replayCapture                      # the whole of logs/skyzh-capture
 *   ./gradlew replayCapture -Pcapture=&lt;dir&gt; # one gameplay, one surface, one file
 * </pre>
 *
 * <p>A capture file also holds records the collector template-ised — {@code Bought %1$sx %2$s} —
 * which are not lines any server ever sent; those come out unmatched and mean nothing. Point the
 * tool at a hand-written file of real lines when that matters.
 */
public final class CaptureReplay {
	private CaptureReplay() {
	}

	public static void main(String[] args) throws Exception {
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(index);

		List<Path> files = new ArrayList<>();

		try (Stream<Path> walk = Files.walk(Path.of(args[1]))) {
			walk.filter(path -> path.toString().endsWith(".json")).sorted().forEach(files::add);
		}

		int changed = 0;
		int total = 0;

		for (Path file : files) {
			JsonObject json;

			try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				json = JsonParser.parseReader(reader).getAsJsonObject();
			}

			Surface surface = json.has("surface")
				? Surface.fromDirectory(json.get("surface").getAsString()) : null;

			if (surface == null || !json.has("lines")) {
				continue;
			}

			JsonArray lines = json.getAsJsonArray("lines");
			List<String> report = new ArrayList<>();

			for (JsonElement element : lines) {
				String raw = rawOf(element.getAsJsonObject());

				if (raw.isEmpty()) {
					continue;
				}

				total++;

				// The tab list goes through translateRow rather than translateLine, because a row
				// there is a label and a value and the mod translates the two separately.
				Component source = Component.literal(raw).setStyle(Style.EMPTY);
				String drawn = TranslationHarness.legacy(surface == Surface.TABLIST
					? Translator.translateRow(source, surface)
					: Translator.translateLine(source, surface));

				if (drawn.equals(raw)) {
					report.add("  ✗ " + raw);
				} else {
					changed++;
					report.add("  ✓ " + raw + "\n      -> " + drawn);
				}
			}

			if (!report.isEmpty()) {
				System.out.println("== " + file);
				report.forEach(System.out::println);
			}
		}

		System.out.println("\n渲染有变化 " + changed + " / " + total);
	}

	/**
	 * One line as the server actually sent it.
	 *
	 * <p>{@code raw_escaped} wins wherever the collector wrote one: it is there precisely because the
	 * line holds characters nothing can print — the server's icon font — and the {@code raw} beside
	 * it has had them flattened on the way into JSON.
	 */
	private static String rawOf(JsonObject line) {
		JsonObject capture = line.has("_capture") ? line.getAsJsonObject("_capture") : null;

		if (capture == null || !capture.has("raw_escaped")) {
			return line.has("raw") ? line.get("raw").getAsString() : "";
		}

		String escaped = capture.get("raw_escaped").getAsString();
		StringBuilder out = new StringBuilder(escaped.length());

		for (int i = 0; i < escaped.length(); i++) {
			if (escaped.charAt(i) == '\\' && i + 5 < escaped.length() && escaped.charAt(i + 1) == 'u') {
				out.append((char) Integer.parseInt(escaped.substring(i + 2, i + 6), 16));
				i += 5;
			} else {
				out.append(escaped.charAt(i));
			}
		}

		return out.toString();
	}
}
