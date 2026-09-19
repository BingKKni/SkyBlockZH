package io.github.bingkkni.skyzh.text;

import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Chat alignment measured with the same styled font widths used to draw the text. */
public final class ChatLayout {
	public enum Alignment { LEFT, CENTER, RIGHT, UNKNOWN }

	public record Plan(Component text, Issue issue) {}

	/** Pixel measurements only; encoding capture evidence is the caller's responsibility. */
	public record Metrics(
		int chatWidth, int sourceLeft, int sourceWidth, int sourceExtent, int translatedWidth,
		int beforeLeft, int renderedLeft, int spaceWidth
	) {}

	public record Issue(
		Alignment expected, Alignment observed, String policy, Metrics metrics,
		Component beforeCorrection, boolean corrected, boolean conflict, boolean overflow
	) {}

	// Hypixel pads its English announcements against vanilla's default 320px chat box.
	public static final int SERVER_WIDTH = 320;
	private static final int SOURCE_TOLERANCE = 12;
	// Zero/tiny chat scales can produce Integer.MAX_VALUE after vanilla divides by scale.
	private static final int MAX_WIDTH = 16_384;

	private ChatLayout() {
	}

	public static Plan plan(
		Component source, Translator.Result translated, int width, ToIntFunction<Component> measure
	) {
		return plan(source, translated, width, measure, extent(source, measure));
	}

	/**
	 * The right edge of a line as the server sent it: its padding plus its text, in pixels.
	 *
	 * <p>Overflow is only worth reporting when the translation caused it. Hypixel's own English is
	 * routinely wider than the chat box and vanilla wraps it without anyone minding; a Chinese line
	 * that is narrower than that English is not a regression however wide it is. A joined message is
	 * the exception — each of its lines fitted on its own — so the caller that joins lines passes the
	 * widest of them as the extent.
	 */
	public static int extent(Component line, ToIntFunction<Component> measure) {
		StyledText styled = StyledText.of(line);
		int from = leading(styled.plain());

		return (from == 0 ? 0 : measure.applyAsInt(styled.slice(0, from))) + measure.applyAsInt(trim(line));
	}

	public static Plan plan(
		Component source, Translator.Result translated, int width, ToIntFunction<Component> measure,
		int sourceExtent
	) {
		Component padded = translated.padded();

		if (!translated.matched() || width <= 0 || width > MAX_WIDTH) {
			return new Plan(padded, null);
		}

		StyledText en = StyledText.of(source);
		int from = leading(en.plain());
		int speaker = LineShape.speakerTagEnd(en.plain());

		if (translated.head() == null || StyledText.of(translated.head()).length() < speaker) {
			speaker = -1;
		}

		int speakerPadding = speaker < 0 ? 0 : leading(en.plain().substring(speaker));
		Component core = trim(padded);

		if (speaker >= 0) {
			MutableComponent spoken = Component.empty().append(en.slice(0, speaker)).append(translated.core());

			if (translated.tail() != null) {
				spoken.append(translated.tail());
			}

			core = trim(spoken);
		}

		int enLeft = from == 0 ? 0 : measure.applyAsInt(en.slice(0, from));
		int enWidth = measure.applyAsInt(trim(source));
		int zhWidth = measure.applyAsInt(core);
		int space = Math.max(1, measure.applyAsInt(Component.literal(" ")));
		int slack = width - zhWidth;
		Alignment observed = speaker < 0 ? observed(from, enLeft, enWidth, width) : Alignment.UNKNOWN;
		String policy = translated.entry().layout();
		Alignment declared = switch (policy) {
			case "left_chat" -> Alignment.LEFT;
			case "right_chat" -> Alignment.RIGHT;
			case "center_chat_banner" -> Alignment.CENTER;
			case "chat_source_alignment" -> observed;
			case "center_chat_if_padded" -> from > 0 || speakerPadding > 0 ? Alignment.CENTER : Alignment.UNKNOWN;
			case "center_chat_if_widely_padded" -> from >= 8 || speakerPadding >= 8 ? Alignment.CENTER : Alignment.UNKNOWN;
			default -> Alignment.UNKNOWN;
		};
		boolean explicit = declared != Alignment.UNKNOWN && !policy.isEmpty() && !policy.equals("chat_source_alignment");
		Alignment expected = declared;

		if (!explicit && observed != Alignment.UNKNOWN) {
			expected = observed;
		}

		Component candidate = place(core, padded, declared, slack, enLeft, space);
		Component output = expected == declared ? candidate : place(core, candidate, expected, slack, enLeft, space);
		int actualLeft = left(candidate, measure);
		int finalLeft = expected == declared ? actualLeft : left(output, measure);
		boolean corrected = Math.abs(actualLeft - finalLeft) > space / 2.0 + 1;
		boolean conflict = explicit && observed != Alignment.UNKNOWN && observed != declared;
		boolean overflow = finalLeft + zhWidth > width && sourceExtent <= width;

		if (!corrected && !overflow && !conflict) {
			return new Plan(output, null);
		}

		Metrics metrics = new Metrics(width, enLeft, enWidth, sourceExtent, zhWidth, actualLeft, finalLeft, space);
		Issue issue = new Issue(expected, observed, policy, metrics, candidate, corrected, conflict, overflow);

		return new Plan(output, issue);
	}

	private static Alignment observed(int spaces, int left, int textWidth, int width) {
		if (spaces == 0) {
			return Alignment.UNKNOWN;
		}

		if (spaces <= 4) {
			return Math.abs(left + textWidth / 2.0 - SERVER_WIDTH / 2.0) <= SOURCE_TOLERANCE
				? Alignment.UNKNOWN : Alignment.LEFT;
		}

		for (int box : new int[] {SERVER_WIDTH, width}) {
			if (spaces >= 8 && Math.abs(left + textWidth / 2.0 - box / 2.0) <= SOURCE_TOLERANCE) {
				return Alignment.CENTER;
			}

			if (spaces >= 8 && Math.abs(left + textWidth - box) <= 4) {
				return Alignment.RIGHT;
			}
		}

		return Alignment.UNKNOWN;
	}

	private static Component place(
		Component core, Component original, Alignment alignment, int slack, int indent, int space
	) {
		if (alignment == Alignment.UNKNOWN) {
			return original;
		}

		int target = switch (alignment) {
			case CENTER -> Math.max(0, slack / 2);
			case RIGHT -> Math.max(0, slack);
			default -> indent;
		};
		int count = Math.min(Math.max(0, Math.round((float) target / space)), Math.max(0, slack / space));

		return count == 0 ? core : Component.literal(" ".repeat(count)).append(core);
	}

	private static int left(Component text, ToIntFunction<Component> measure) {
		StyledText styled = StyledText.of(text);
		int leading = leading(styled.plain());

		return leading == 0 ? 0 : measure.applyAsInt(styled.slice(0, leading));
	}

	private static int leading(String text) {
		int start = 0;

		while (start < text.length() && text.charAt(start) == ' ') {
			start++;
		}

		return start;
	}

	private static Component trim(Component text) {
		StyledText styled = StyledText.of(text);
		int start = leading(styled.plain());
		int end = styled.length();

		while (end > start && styled.plain().charAt(end - 1) == ' ') {
			end--;
		}

		return styled.slice(start, end);
	}
}
