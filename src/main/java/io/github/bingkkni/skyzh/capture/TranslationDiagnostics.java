package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.ChatLayout;
import io.github.bingkkni.skyzh.text.ChatTranslation;
import io.github.bingkkni.skyzh.text.LineShape;
import io.github.bingkkni.skyzh.text.LoreMatcher;
import io.github.bingkkni.skyzh.text.LoreTranslation;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TermTable;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.Translator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

/** Evidence about the rendered result, without interpreting or changing game state. */
public final class TranslationDiagnostics {
	private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?");
	private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{2,}");
	private static final Pattern LOWER_WORD = Pattern.compile("\\b[a-z]{2,}\\b");
	private static final Pattern HEAD_END = Pattern.compile("(?s).*[.!?:]$");
	private static final Pattern SENTENCE_END = Pattern.compile("(?s).*[.!?]$");
	private static final Pattern WORD_END = Pattern.compile("[A-Za-z]+[.!?]");

	private TranslationDiagnostics() {
	}

	public record Finding(List<Component> source, Classifier.Verdict verdict) {}

	/** Layout evidence describes exactly the same joined units that the chat renderer draws. */
	public static List<Finding> chat(ChatTranslation.Plan plan) {
		List<Finding> findings = new ArrayList<>();

		for (ChatTranslation.Unit unit : plan.units()) {
			ChatLayout.Issue issue = unit.layout().issue();

			if (issue == null) {
				continue;
			}

			String code = "alignment_corrected";

			if (issue.overflow()) {
				code = "layout_overflow";
			} else if (issue.conflict()) {
				code = "alignment_policy_conflict";
			}

			ChatLayout.Metrics metrics = issue.metrics();
			JsonObject evidence = evidence(code, unit.source(), List.of(unit.layout().text()), unit.matches());
			evidence.add("before_correction", encoded(List.of(issue.beforeCorrection())));
			evidence.addProperty("expected_alignment", issue.expected().name().toLowerCase(Locale.ROOT));
			evidence.addProperty("source_alignment", issue.observed().name().toLowerCase(Locale.ROOT));
			evidence.addProperty("confidence", issue.conflict() ? "suspected" : "measured");
			evidence.addProperty("declared_policy", issue.policy());
			evidence.addProperty("chat_width_px", metrics.chatWidth());
			evidence.addProperty("server_reference_width_px", ChatLayout.SERVER_WIDTH);
			evidence.addProperty("source_left_px", metrics.sourceLeft());
			evidence.addProperty("source_text_width_px", metrics.sourceWidth());
			evidence.addProperty("source_extent_px", metrics.sourceExtent());
			evidence.addProperty("translated_text_width_px", metrics.translatedWidth());
			evidence.addProperty("before_left_px", metrics.beforeLeft());
			evidence.addProperty("rendered_left_px", metrics.renderedLeft());
			evidence.addProperty("space_width_px", metrics.spaceWidth());
			evidence.addProperty("corrected", issue.corrected());
			findings.add(new Finding(unit.source(), verdict(
				Classifier.Bucket.LAYOUT, unit.matches().getFirst().entry(), evidence
			)));
		}

		return List.copyOf(findings);
	}

	/**
	 * Values the server sent that the Chinese no longer shows.
	 *
	 * <p>Only placeholder values are counted. A number written into the record's own English —
	 * {@code 12:00 am-11:59 pm}, {@code 5x}, {@code 10m cooldown} — was in front of the translator
	 * when the Chinese was written, and 全天 or 提升 4 倍 dropping it is a decision, not a loss. A value
	 * the term table translates whole ({@code 2X POWDER} → 双倍粉末) is the same kind of decision.
	 * What cannot be a decision is a value the record only sees at runtime: those go through the
	 * documented ordinal, duration and multiplier conversions, and every digit group left must still
	 * be on screen, as many times as it arrived.
	 */
	public static Classifier.Verdict numbers(
		List<Component> source, Component rendered, List<TranslationEntry.Matched> matches
	) {
		if (matches.isEmpty()) {
			return null;
		}

		List<String> problems = new ArrayList<>();
		StringBuilder expected = new StringBuilder();
		TermTable terms = Translator.index().terms();

		for (var match : matches) {
			problems.addAll(match.entry().argumentProblems(match.match()));

			for (String value : match.entry().renderedValues(match.match(), terms)) {
				expected.append(value).append(' ');
			}
		}

		Map<String, Integer> before = numbers(expected.toString());
		Map<String, Integer> after = numbers(StyledText.of(rendered).plain());

		for (var number : before.entrySet()) {
			if (after.getOrDefault(number.getKey(), 0) < number.getValue()) {
				problems.add("译文缺少数值或次数: " + number.getKey());
			}
		}

		if (problems.isEmpty()) {
			return null;
		}

		JsonObject evidence = evidence("missing_value", source, List.of(rendered), matches);
		JsonArray reasons = new JsonArray();
		problems.stream().distinct().forEach(reasons::add);
		evidence.add("reasons", reasons);

		return verdict(Classifier.Bucket.VALUE, matches.getFirst().entry(), evidence);
	}

	/**
	 * Digit groups and how often each occurs. {@code 5m} is five of something — minutes here,
	 * millions there — and the Chinese keeps the {@code 5} either way, so no magnitude suffix is
	 * expanded and no percent sign is kept: the check is that a value survived, not how it is spelled.
	 */
	private static Map<String, Integer> numbers(String text) {
		Map<String, Integer> result = new LinkedHashMap<>();
		var matcher = NUMBER.matcher(text);

		while (matcher.find()) {
			String key = new BigDecimal(matcher.group().replace(",", "")).stripTrailingZeros().toPlainString();
			result.merge(key, 1, Integer::sum);
		}

		return result;
	}

	/** Keep the original ordered lines. Deduplicated individual records cannot reconstruct a tooltip. */
	public static List<Finding> lore(List<LoreTranslation.Unit> units) {
		List<Finding> findings = new ArrayList<>();

		for (int i = 0; i < units.size(); i++) {
			LoreTranslation.Unit unit = units.get(i);
			Classifier.Verdict value = numbers(unit.source(), unit.rendered(), unit.matches());

			if (value != null) {
				findings.add(new Finding(unit.source(), value));
			}

			if (!unit.translated() || i + 1 >= units.size()) {
				continue;
			}

			LoreTranslation.Unit next = units.get(i + 1);
			String head = visible(unit.source().getLast()).trim();
			String tail = visible(next.source().getFirst()).trim();
			String zh = StyledText.of(unit.rendered()).plain();

			if (next.translated() || tail.isEmpty() || !hasHan(zh) || !isEnglishContinuation(head, tail)) {
				continue;
			}

			List<Component> source = new ArrayList<>(unit.source());
			List<Component> output = new ArrayList<>();
			output.add(unit.rendered());

			for (int j = i + 1; j < units.size() && source.size() < LoreMatcher.MAX_LINES; j++) {
				LoreTranslation.Unit context = units.get(j);
				String plain = StyledText.of(context.rendered()).plain().trim();

				if (context.translated() || plain.isEmpty()) {
					break;
				}

				source.addAll(context.source());
				output.add(context.rendered());

				if (SENTENCE_END.matcher(plain).matches()) {
					break;
				}
			}

			JsonObject evidence = evidence("untranslated_continuation", source, output, unit.matches());
			evidence.addProperty("confidence", "suspected");
			evidence.addProperty("line", unit.start() + 1);
			evidence.addProperty("reason", "已译句后紧邻英语续行；检查是否重复原文或漏译后半句，不自动吞行。");
			findings.add(new Finding(List.copyOf(source), verdict(
				Classifier.Bucket.INCOMPLETE, unit.matches().getFirst().entry(), evidence
			)));
		}

		return List.copyOf(findings);
	}

	/**
	 * Whether an English line reads as the rest of the translated sentence above it.
	 *
	 * <p>A tail that begins on a lowercase word is the server's own wrap and needs no further
	 * evidence. A tail beginning on a capital is judged by the head: a sentence Hypixel wrapped still
	 * has ordinary lowercase words in its head line, whereas a heading — {@code Ability: Jingle Bells},
	 * {@code Topaz Crystal Hunter}, {@code +1 Commission Slot}, {@code 4th Grand Feast 1d} — is a
	 * label or a name written in title case, and whatever English follows it is a new line, not the
	 * end of it. A line that opens with a list mark is a new entry whatever came before it.
	 */
	private static boolean isEnglishContinuation(String head, String tail) {
		if (!ENGLISH.matcher(tail).find() || LineShape.isBullet(tail.charAt(0))) {
			return false;
		}

		if (Character.isLowerCase(tail.charAt(0))) {
			return true;
		}

		if (HEAD_END.matcher(head).matches() || isHeading(head)) {
			return false;
		}

		return WORD_END.matcher(tail).matches()
			|| head.split(" +").length >= 3 && LOWER_WORD.matcher(tail).find()
				&& LineShape.enchantments(tail).isEmpty();
	}

	/** A label, or a run of words that each open on a capital, a digit or a symbol: title case, not prose. */
	private static boolean isHeading(String head) {
		if (head.indexOf(':') >= 0) {
			return true;
		}

		for (String word : head.split(" +")) {
			if (!word.isEmpty() && Character.isLowerCase(word.charAt(0))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The characters of a line a player can read. Hypixel decorates rarity lines with obfuscated
	 * letters — {@code §ka} either side of {@code MYTHIC HELMET} — that the renderer draws as
	 * flickering noise; read as text they are a lowercase {@code a} opening the line.
	 */
	private static String visible(Component line) {
		StyledText styled = StyledText.of(line);
		StringBuilder text = new StringBuilder(styled.length());

		for (int i = 0; i < styled.length(); i++) {
			if (!styled.styleAt(i).isObfuscated()) {
				text.append(styled.plain().charAt(i));
			}
		}

		return text.toString();
	}

	private static boolean hasHan(String text) {
		return text.codePoints().anyMatch(c -> c >= 0x3400 && c <= 0x9FFF);
	}

	public static JsonObject evidence(
		String code, List<Component> source, List<Component> output, List<TranslationEntry.Matched> matches
	) {
		JsonObject evidence = new JsonObject();
		evidence.addProperty("code", code);
		evidence.add("original_lines", encoded(source));
		evidence.add("rendered_lines", encoded(output));
		JsonArray records = new JsonArray();

		for (var match : matches) {
			records.add(match.entry().sourceFile() + "#" + match.entry().id());
		}

		evidence.add("records", records);

		return evidence;
	}

	public static JsonArray encoded(List<Component> lines) {
		JsonArray array = new JsonArray();

		for (Component line : lines) {
			array.add(LegacyText.encode(StyledText.of(line)).raw());
		}

		return array;
	}

	public static Classifier.Verdict verdict(Classifier.Bucket bucket, TranslationEntry entry, JsonObject evidence) {
		return new Classifier.Verdict(bucket, List.of(), List.of(), null,
			entry == null ? "" : entry.id(), entry == null ? "" : entry.sourceFile(), evidence);
	}
}
