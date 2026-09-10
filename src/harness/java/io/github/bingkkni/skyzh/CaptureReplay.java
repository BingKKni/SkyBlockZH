package io.github.bingkkni.skyzh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.LegacyText;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Replays concrete captured lines through the font-free render paths. Capture files are deduplicated
 * records, not ordered tooltips: continuation paragraphs and pixel layout need separate fixtures.
 *
 * <pre>
 *   ./gradlew replayCapture
 *   ./gradlew replayCapture -Pcapture=&lt;directory-or-file&gt;
 * </pre>
 *
 * <p>Inferred templates are reported separately, never counted as misses. Their observed value lists
 * do not preserve which values arrived together, so filling them would invent server messages.
 */
public final class CaptureReplay {
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:[0-9]+\\$)?[sd]");

	private CaptureReplay() {
	}

	public static void main(String[] args) throws Exception {
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(index);

		List<Path> files = new ArrayList<>();

		try (Stream<Path> walk = Files.walk(Path.of(args[1]))) {
			walk.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".json")).sorted().forEach(files::add);
		}

		int changed = 0;
		int total = 0;
		int templates = 0;

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

			List<String> report = new ArrayList<>();

			for (JsonElement element : json.getAsJsonArray("lines")) {
				JsonObject line = element.getAsJsonObject();
				String raw = rawOf(line);

				if (raw.isEmpty()) {
					continue;
				}

				if (isTemplate(raw)) {
					templates++;
					report.add("  ~ 模板（未回放） " + raw);
					continue;
				}

				total++;
				Component source = decode(raw);
				JsonObject capture = line.has("_capture") ? line.getAsJsonObject("_capture") : null;
				boolean itemName = surface == Surface.ITEM && capture != null && capture.has("where")
					&& capture.get("where").getAsString().endsWith("物品名");
				Component output = itemName
					? TooltipTranslator.translateItemName(source).padded() : Probe.draw(source, surface);

				if (changed(source, output)) {
					changed++;
					report.add("  ✓ " + raw + "\n      -> " + encoded(output));
				} else {
					report.add("  ✗ " + raw);
				}
			}

			if (!report.isEmpty()) {
				System.out.println("== " + file);
				report.forEach(System.out::println);
			}
		}

		System.out.println("\n具体原文: 渲染有变化 " + changed + " / " + total + "；未回放模板 " + templates);
		System.out.println("变化数不等于翻译覆盖率；不重建跨行顺序、字体、悬浮/点击事件或像素排版。");
	}

	static boolean isTemplate(String raw) {
		return PLACEHOLDER.matcher(raw).find();
	}

	static boolean changed(Component source, Component output) {
		StyledText before = StyledText.of(source);
		StyledText after = StyledText.of(output);

		if (!before.plain().equals(after.plain())) {
			return true;
		}

		for (int i = 0; i < before.length(); i++) {
			Style left = before.styleAt(i);
			Style right = after.styleAt(i);

			if (left == right) {
				continue;
			}

			int leftColor = left.getColor() == null ? -1 : left.getColor().getValue();
			int rightColor = right.getColor() == null ? -1 : right.getColor().getValue();

			if (leftColor != rightColor || !Objects.equals(left.getFont(), right.getFont())
				|| left.isBold() != right.isBold() || left.isItalic() != right.isItalic()
				|| left.isUnderlined() != right.isUnderlined()
				|| left.isStrikethrough() != right.isStrikethrough()
				|| left.isObfuscated() != right.isObfuscated()) {
				return true;
			}
		}

		return false;
	}

	private static String encoded(Component component) {
		return LegacyText.encode(StyledText.of(component)).raw();
	}

	/** Capture's §#RRGGBB notation is not a vanilla legacy code; it must become a styled component. */
	static Component decode(String raw) {
		MutableComponent result = Component.empty();
		Style style = Style.EMPTY;
		int start = 0;

		for (int i = 0; i < raw.length(); i++) {
			if (raw.charAt(i) != '§') {
				continue;
			}

			if (start < i) {
				result.append(Component.literal(raw.substring(start, i)).setStyle(style));
			}

			if (i + 7 < raw.length() && raw.charAt(i + 1) == '#'
				&& raw.substring(i + 2, i + 8).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
				style = Style.EMPTY.withColor(Integer.parseInt(raw.substring(i + 2, i + 8), 16));
				i += 7;
			} else {
				ChatFormatting format = i + 1 < raw.length() ? ChatFormatting.getByCode(raw.charAt(i + 1)) : null;

				if (format != null) {
					style = format == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(format);
				}

				i = Math.min(i + 1, raw.length() - 1);
			}

			start = i + 1;
		}

		if (start < raw.length()) {
			result.append(Component.literal(raw.substring(start)).setStyle(style));
		}

		return result;
	}

	/** raw_escaped uses literal Unicode escapes to preserve private-use glyphs. */
	static String rawOf(JsonObject line) {
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
