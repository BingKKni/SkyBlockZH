package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** One font-free lore plan shared by tooltips, packet capture and offline replay. */
public final class LoreTranslation {
	public record Unit(
		int start, List<Component> source, Component rendered, List<TranslationEntry.Matched> matches, boolean complete
	) {
		public boolean translated() {
			return !this.matches.isEmpty();
		}
	}

	private LoreTranslation() {
	}

	/** Input contains lore only: an item name can never consume a continuation. */
	public static List<Unit> plan(List<Component> lines) {
		List<Unit> result = new ArrayList<>();

		for (int i = 0; i < lines.size();) {
			LoreMatcher.Match sentence = LoreMatcher.find(Translator.index(), lines, i);

			if (sentence != null) {
				Component rendered = Translator.skyBlockName(
					sentence.render(Translator.index().terms()), SkyZHConfig.get()
				);
				TranslationEntry.Matched match = new TranslationEntry.Matched(
					sentence.entry(), sentence.core(), sentence.matcher()
				);
				result.add(new Unit(
					i, List.copyOf(lines.subList(i, i + sentence.lines())), rendered, List.of(match), true
				));
				i += sentence.lines();
				continue;
			}

			Component line = lines.get(i);
			Translator.Result head = Translator.translateAvailable(line, Surface.ITEM);

			if (!head.matched() || head.entry().continuation()) {
				Component rendered = line;

				if (!head.matched()) {
					Component list = Translator.translateListAvailable(line, Surface.ITEM);
					rendered = list != null ? list : head.padded();
				}

				result.add(new Unit(i, List.of(line), rendered, List.of(), false));
				i++;
				continue;
			}

			List<TranslationEntry.Matched> joined = new ArrayList<>();
			joined.add(new TranslationEntry.Matched(head.entry(), head.matchedCore(), head.match()));
			int end = i + 1;

			while (end < lines.size() && end - i < LoreMatcher.MAX_LINES) {
				Translator.Result tail = Translator.translateAvailable(lines.get(end), Surface.ITEM);

				if (!tail.matched() || !tail.entry().continuation()) {
					break;
				}

				// A complete LORE sentence owns its lines even if ITEM also calls its head a tail.
				if (LoreMatcher.find(Translator.index(), lines, end) != null) {
					break;
				}

				joined.add(new TranslationEntry.Matched(tail.entry(), tail.matchedCore(), tail.match()));
				end++;
			}

			MutableComponent rendered = Component.empty();

			if (head.head() != null) {
				rendered.append(head.head());
			}

			rendered.append(TranslationEntry.renderJoined(joined, Translator.index().terms()));

			if (head.tail() != null) {
				rendered.append(head.tail());
			}

			result.add(new Unit(
				i, List.copyOf(lines.subList(i, end)), Translator.skyBlockName(rendered, SkyZHConfig.get()),
				List.copyOf(joined), false
			));
			i = end;
		}

		return List.copyOf(result);
	}
}
