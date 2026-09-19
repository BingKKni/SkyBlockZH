package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The same message splitting, opt-in joining and layout for rendering and packet diagnostics. */
public final class ChatTranslation {
	public record Unit(
		List<Component> source, List<TranslationEntry.Matched> matches, ChatLayout.Plan layout
	) {}

	public record Plan(List<Unit> units) {
		public Component text() {
			MutableComponent result = Component.empty();

			for (int i = 0; i < this.units.size(); i++) {
				if (i > 0) {
					result.append("\n");
				}

				result.append(this.units.get(i).layout().text());
			}

			return result;
		}
	}

	private ChatTranslation() {
	}

	/** Corpus availability is independent of the translation switch and held original-text key. */
	public static Plan plan(Component source, int width, ToIntFunction<Component> measure) {
		List<Component> lines = lines(StyledText.of(source));
		List<Translator.Result> translated = new ArrayList<>(lines.size());

		for (Component line : lines) {
			translated.add(Translator.translateAvailable(line, Surface.CHAT));
		}

		List<Unit> units = new ArrayList<>(lines.size());

		for (int at = 0; at < lines.size(); at++) {
			int end = joinEnd(translated, at);
			List<Translator.Result> parts = translated.subList(at, end + 1);
			List<TranslationEntry.Matched> matches = new ArrayList<>(parts.size());

			for (Translator.Result part : parts) {
				if (part.matched()) {
					matches.add(new TranslationEntry.Matched(part.entry(), part.matchedCore(), part.match()));
				}
			}

			Translator.Result result = translated.get(at);

			if (end > at) {
				result = joined(parts, matches);
			}

			// A joined sentence keeps its head's alignment anchor, but measures the whole Chinese
			// against the widest of the lines it replaced.
			int extent = 0;

			for (int line = at; line <= end; line++) {
				extent = Math.max(extent, ChatLayout.extent(lines.get(line), measure));
			}

			ChatLayout.Plan layout = ChatLayout.plan(lines.get(at), result, width, measure, extent);
			units.add(new Unit(List.copyOf(lines.subList(at, end + 1)), List.copyOf(matches), layout));
			at = end;
		}

		return new Plan(List.copyOf(units));
	}

	/** Keeps empty leading, middle and trailing lines. */
	private static List<Component> lines(StyledText source) {
		List<Component> lines = new ArrayList<>();
		String plain = source.plain();
		int start = 0;

		while (true) {
			int newline = plain.indexOf('\n', start);
			int end = newline < 0 ? plain.length() : newline;
			lines.add(source.slice(start, end));

			if (newline < 0) {
				return lines;
			}

			start = newline + 1;
		}
	}

	/** Returns the inclusive tail, or start unless the whole declared chain matches this message. */
	private static int joinEnd(List<Translator.Result> translated, int start) {
		Translator.Result head = translated.get(start);

		if (!head.matched() || head.entry().continuation() || head.entry().chatJoinNext().isEmpty()) {
			return start;
		}

		String expected = head.entry().chatJoinNext();

		for (int at = start + 1; at < translated.size(); at++) {
			Translator.Result tail = translated.get(at);

			if (!tail.matched() || tail.entry().continuation()
				|| !head.entry().sourceFile().equals(tail.entry().sourceFile())
				|| !expected.equals(tail.entry().id())) {
				return start;
			}

			expected = tail.entry().chatJoinNext();

			if (expected.isEmpty()) {
				return at;
			}
		}

		return start;
	}

	private static Translator.Result joined(
		List<Translator.Result> parts, List<TranslationEntry.Matched> matches
	) {
		Translator.Result first = parts.getFirst();
		Translator.Result last = parts.getLast();
		MutableComponent core = Translator.skyBlockName(
			TranslationEntry.renderJoined(matches, Translator.index().terms()), SkyZHConfig.get()
		);

		return new Translator.Result(
			core, first.head(), last.tail(), first.entry(), first.matchedCore(), first.match()
		);
	}
}
