package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.StyledText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Turns a line back into the {@code §}-coded string Hypixel sent, which is the form
 * {@code original_text/}'s {@code raw} field is written in.
 *
 * <p>The corpus records colour twice over — once as {@code raw}, once split into {@code segments} —
 * and both are produced here from the same walk, because they have to agree: a {@code segments} array
 * whose boundaries do not line up with the colour changes in {@code raw} is worse than no
 * {@code segments} at all. A capture that recorded only the words would be useless for exactly the
 * problem this project keeps hitting, which is that a line changes colour at boundaries nobody can
 * guess from reading it.
 *
 * <p><b>Rendering-equivalent, not byte-identical.</b> {@code §} codes are not recoverable from text
 * that has been flattened — a run drawn in white got there through {@code §f} or through
 * {@code §r§f} or through a component's style, and by the time anything can be read they are the same
 * run. What comes out here is the shortest sequence of codes that draws the same pixels, which is
 * what {@code raw} is for: a translator reads it and the engine matches against it, and neither cares
 * how many resets the server sent.
 *
 * <p><b>Where legacy codes cannot say it.</b> {@code §} has sixteen colours and five decorations, and
 * a {@code Style} can hold more than that: a 24-bit colour, a font, a click or hover event. Those are
 * written out as {@code §#RRGGBB} — readable, and what most tooling means by it — with the run's full
 * attributes reported separately in {@code style_runs}, so nothing observed is silently dropped. The
 * flag is on the record, not buried: a translator copying a {@code raw} that quietly lost half its
 * colouring is the failure mode worth spending a field on.
 */
public final class LegacyText {
	/** One run of characters drawn with a single style — a {@code segments} entry before translation. */
	public record Run(int start, int end, String codes, String text, Style style) {}

	/** A whole line rendered back into codes, plus what could not be said in them. */
	public record Encoded(String raw, List<Run> runs, boolean lossy) {}

	/**
	 * The sixteen colours a {@code §} code can name, and the code that names each.
	 *
	 * <p>Built by asking Minecraft rather than by writing the pairs down: {@code fromLegacyFormat} is
	 * the same mapping the renderer uses, so a colour that is in this map is exactly a colour that
	 * survives a round trip through legacy codes. Anything else is a 24-bit colour and is reported as
	 * one.
	 */
	private static final Map<TextColor, ChatFormatting> LEGACY_COLOURS = legacyColours();

	private static Map<TextColor, ChatFormatting> legacyColours() {
		Map<TextColor, ChatFormatting> colours = new HashMap<>();

		for (ChatFormatting formatting : ChatFormatting.values()) {
			TextColor colour = TextColor.fromLegacyFormat(formatting);

			if (colour != null) {
				colours.putIfAbsent(colour, formatting);
			}
		}

		return Map.copyOf(colours);
	}

	private LegacyText() {
	}

	/**
	 * The line as codes plus text, with each character range in {@code holes} replaced by the matching
	 * token.
	 *
	 * <p>The holes are where a template's placeholders go. Substituting during the walk rather than
	 * afterwards is what keeps {@code raw} and {@code segments} consistent when a placeholder sits on
	 * a colour boundary — which it usually does, because the server changes colour to draw the value.
	 *
	 * @param holes  half-open ranges of {@code styled}'s plain text, in ascending order, not overlapping
	 * @param tokens the replacement for each hole, same length as {@code holes}
	 */
	public static Encoded encode(StyledText styled, List<int[]> holes, List<String> tokens) {
		StringBuilder raw = new StringBuilder();
		List<Run> runs = new ArrayList<>();
		StringBuilder run = new StringBuilder();
		String plain = styled.plain();
		boolean lossy = false;
		boolean styledSoFar = false;
		int runStart = 0;
		Style runStyle = null;
		int hole = 0;
		int i = 0;

		while (i <= plain.length()) {
			String text = null;
			Style style = null;
			int next = i + 1;

			if (i < plain.length()) {
				// A hole is one cell drawn in the style of its first character, whatever happens
				// inside it: the value is the server's, is copied across verbatim at render time, and
				// its own colour changes are not the template's business.
				if (hole < holes.size() && i == holes.get(hole)[0]) {
					text = tokens.get(hole);
					next = holes.get(hole)[1];
					hole++;
				} else {
					text = String.valueOf(plain.charAt(i));
				}

				style = styled.styleAt(i);
			}

			if (runStyle != null && (style == null || !style.equals(runStyle)) && !run.isEmpty()) {
				String codes = codes(runStyle);

				// A run with no style of its own, after one that had a style, needs a reset or it
				// would silently inherit the colour in front of it. A plain opening run needs no
				// code at all, which is the common case.
				if (codes.isEmpty() && styledSoFar) {
					codes = ChatFormatting.RESET.toString();
				}

				lossy |= isLossy(runStyle);
				styledSoFar |= !codes.isEmpty();
				raw.append(codes).append(run);
				runs.add(new Run(runStart, i, codes, run.toString(), runStyle));
				run.setLength(0);
				runStart = i;
			}

			if (style == null) {
				break;
			}

			runStyle = style;
			run.append(text);
			i = next;
		}

		return new Encoded(raw.toString(), List.copyOf(runs), lossy);
	}

	/** The whole line, nothing replaced. */
	public static Encoded encode(StyledText styled) {
		return encode(styled, List.of(), List.of());
	}

	/**
	 * The codes that produce this style from scratch.
	 *
	 * <p>From scratch, not as a delta from the run before, because that is how the corpus reads: a
	 * {@code segments} entry carries one {@code color} field that stands on its own. A colour code
	 * clears the decorations in legacy rendering, so the colour goes first and the decorations after
	 * it; a run with decorations but no colour opens with {@code §r} so it does not inherit whatever
	 * came before.
	 */
	public static String codes(Style style) {
		if (style == null) {
			return "";
		}

		StringBuilder codes = new StringBuilder();
		TextColor color = style.getColor();
		boolean decorated = style.isBold() || style.isItalic() || style.isUnderlined()
			|| style.isStrikethrough() || style.isObfuscated();

		if (color != null) {
			codes.append(colorCode(color));
		} else if (decorated) {
			codes.append(ChatFormatting.RESET);
		}

		append(codes, style.isBold(), ChatFormatting.BOLD);
		append(codes, style.isItalic(), ChatFormatting.ITALIC);
		append(codes, style.isUnderlined(), ChatFormatting.UNDERLINE);
		append(codes, style.isStrikethrough(), ChatFormatting.STRIKETHROUGH);
		append(codes, style.isObfuscated(), ChatFormatting.OBFUSCATED);

		return codes.toString();
	}

	private static void append(StringBuilder codes, boolean on, ChatFormatting formatting) {
		if (on) {
			// ChatFormatting's own toString is its code, prefix included — the same string the server
			// would have sent to ask for it.
			codes.append(formatting);
		}
	}

	/** {@code §a} for one of the sixteen, {@code §#55FF55} for anything else. */
	private static String colorCode(TextColor color) {
		ChatFormatting formatting = LEGACY_COLOURS.get(color);

		if (formatting != null) {
			return formatting.toString();
		}

		return "§#" + String.format(Locale.ROOT, "%06X", color.getValue() & 0xFFFFFF);
	}

	/** Whether this style holds something {@code §} codes cannot express. */
	private static boolean isLossy(Style style) {
		if (style == null) {
			return false;
		}

		TextColor color = style.getColor();

		if (color != null && !LEGACY_COLOURS.containsKey(color)) {
			return true;
		}

		// getFont never returns null — it falls back to the default font — so the comparison is against
		// a blank style's font rather than against nothing.
		return !style.getFont().equals(Style.EMPTY.getFont())
			|| style.getShadowColor() != null
			|| style.getClickEvent() != null
			|| style.getHoverEvent() != null
			|| style.getInsertion() != null;
	}

	/**
	 * Every attribute of every run, for the lines whose colouring {@code §} codes could not carry.
	 *
	 * <p>Flattened per run rather than dumped as the component tree it arrived as. The tree is an
	 * accident of how the server built the message — SkyBlock nests components for reasons that have
	 * nothing to do with the sentence — while the runs are what is actually on screen, and are the
	 * shape a {@code segments} array is written in.
	 */
	public static JsonArray styleRuns(List<Run> runs) {
		JsonArray array = new JsonArray();

		for (Run run : runs) {
			JsonObject json = new JsonObject();
			json.addProperty("text", run.text());

			Style style = run.style();

			if (style == null) {
				array.add(json);
				continue;
			}

			if (style.getColor() != null) {
				json.addProperty("color", style.getColor().serialize());
			}

			flag(json, "bold", style.isBold());
			flag(json, "italic", style.isItalic());
			flag(json, "underlined", style.isUnderlined());
			flag(json, "strikethrough", style.isStrikethrough());
			flag(json, "obfuscated", style.isObfuscated());

			if (!style.getFont().equals(Style.EMPTY.getFont())) {
				json.addProperty("font", style.getFont().toString());
			}

			if (style.getShadowColor() != null) {
				json.addProperty("shadow_color", style.getShadowColor());
			}

			if (style.getInsertion() != null) {
				json.addProperty("insertion", style.getInsertion());
			}

			if (style.getClickEvent() != null) {
				json.addProperty("click_event", style.getClickEvent().toString());
			}

			if (style.getHoverEvent() != null) {
				json.addProperty("hover_event", style.getHoverEvent().toString());
			}

			array.add(json);
		}

		return array;
	}

	private static void flag(JsonObject json, String key, boolean value) {
		if (value) {
			json.addProperty(key, true);
		}
	}

	/**
	 * The same text with anything invisible spelled out as {@code \\uXXXX}.
	 *
	 * <p>Written next to a line whenever it holds a character nobody can see, because that is the one
	 * failure this project cannot diagnose by looking: a record reading {@code "❤ Health"} never
	 * matches a line drawn with {@code U+E010}, and both look identical in every log and every editor.
	 * Hypixel's private-use icon font, non-breaking spaces and stray control characters all land here.
	 *
	 * <p>Visible symbols do not. {@code ❤}, {@code ⏣} and {@code ⸕} are exactly how the corpus is
	 * written and exactly what a translator types, and escaping them would bury the handful of
	 * characters worth pointing at under a page of noise.
	 */
	public static String escape(String text) {
		StringBuilder escaped = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (isInvisible(c)) {
				escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
			} else {
				escaped.append(c);
			}
		}

		return escaped.toString();
	}

	/** Whether nobody could tell this character from another one by looking at the screen. */
	private static boolean isInvisible(char c) {
		if (c == ' ' || c == '\n' || c == '§') {
			return false;
		}

		if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
			return true;
		}

		int type = Character.getType(c);

		return type == Character.PRIVATE_USE || type == Character.FORMAT
			|| type == Character.UNASSIGNED || type == Character.SURROGATE;
	}

	/** Whether {@link #escape} would change anything — the cheap test for "this line hides something". */
	public static boolean hasInvisible(String text) {
		return !escape(text).equals(text);
	}
}
