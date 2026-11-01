package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Read-only page/side translation. Explicit rows and their live styles remain structural. */
public final class DocumentTranslation {
	private DocumentTranslation() {}

	public static List<Component> lines(Component source) {
		StyledText text = StyledText.of(source);
		List<Component> lines = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.plain().charAt(i) == '\n') {
				lines.add(text.slice(start, i));
				start = i + 1;
			}
		}
		lines.add(text.slice(start, text.length()));
		return List.copyOf(lines);
	}

	public static Component join(List<Component> lines) {
		MutableComponent result = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) result.append("\n");
			result.append(lines.get(i));
		}
		return result;
	}

	/** Complete page/side records win; otherwise each explicit line can use its own template. */
	public static Component available(Component source, Surface surface) {
		Translator.Result whole = Translator.translateAvailable(source, surface);
		if (whole.matched()) return whole.padded();
		List<Component> result = new ArrayList<>();
		for (Component line : lines(source)) result.add(Translator.translateAvailable(line, surface).padded());
		return join(result);
	}

	/** Books wrap at 114px in vanilla; only geometrically supported padding is recomputed. */
	public static Component book(Component source, int width, ToIntFunction<Component> measure) {
		List<Component> original = lines(source);
		List<Component> translated = lines(available(source, Surface.BOOK));
		if (original.size() != translated.size()) return hoverText(join(translated));
		List<Component> result = new ArrayList<>();
		for (int i = 0; i < original.size(); i++) {
			Component en = original.get(i), zh = translated.get(i);
			StyledText text = StyledText.of(en);
			int start = 0, end = text.length();
			while (start < end && text.plain().charAt(start) == ' ') start++;
			while (end > start && text.plain().charAt(end - 1) == ' ') end--;
			int left = measure.applyAsInt(text.slice(0, start));
			int core = measure.applyAsInt(text.slice(start, end));
			int space = Math.max(1, measure.applyAsInt(Component.literal(" ")));
			if (start >= 2 && core > 0 && left + core <= width + space
				&& Math.abs(left + core / 2.0 - width / 2.0) <= space
				&& !StyledText.of(zh).plain().equals(text.plain())) {
				StyledText output = StyledText.of(zh);
				int from = 0, to = output.length();
				while (from < to && output.plain().charAt(from) == ' ') from++;
				while (to > from && output.plain().charAt(to - 1) == ' ') to--;
				Component body = output.slice(from, to);
				int count = Math.max(0, Math.round((width - measure.applyAsInt(body)) / (2f * space)));
				zh = Component.literal(" ".repeat(count)).append(body);
			}
			result.add(zh);
		}
		return hoverText(join(result));
	}

	/** Only the book's own tooltip records; commands, page targets and other hover kinds stay live. */
	private static Component hoverText(Component source) {
		MutableComponent result = Component.empty();
		source.visit((style, text) -> {
			if (style.getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText hover) {
				Component translated = available(hover.value(), Surface.BOOK);
				style = style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(translated));
			}
			result.append(Component.literal(text).setStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return result;
	}
}
