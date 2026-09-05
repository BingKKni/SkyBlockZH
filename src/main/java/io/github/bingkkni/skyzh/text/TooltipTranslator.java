package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Item name and lore, translated as a block rather than line by line.
 *
 * <p>Lore needs the whole list at once for two reasons. A sentence Hypixel broke across two lore
 * lines is stored in the corpus as one record on the first of those lines, with the second marked
 * {@code continuation} — so translating the first line means <em>removing</em> the second, which
 * only makes sense with both in hand. And once a sentence is whole again it has to be re-broken to
 * fit, which needs to know how wide the tooltip was going to be, which is a property of the list.
 *
 * <p>Tooltips re-render every frame, so the finished list is cached against the text that produced
 * it. Hovering an item costs one pass and then map lookups until the pointer moves.
 */
public final class TooltipTranslator {
	private static final int CACHE_SIZE = 64;
	// Only names confirmed in the corpus glossary belong here. A broader first-word heuristic would
	// turn an ordinary item's first word into a reforge prefix.
	private static final Map<String, String> ITEM_REFORGE_PREFIXES = Map.of(
		"Fleet", "迅捷",
		"Auspicious", "吉兆"
	);

	/**
	 * A floor under the width lines are re-broken at, in pixels — about ten Chinese characters.
	 *
	 * <p>The width normally comes from the English tooltip, which is the right answer: a translation
	 * should not make a box wider than the one the game drew. But a tooltip whose lines are all blank
	 * or a couple of characters long would set that width near zero and chop the Chinese into a
	 * column one character wide. Below this floor the box is allowed to grow instead.
	 */
	private static final int MIN_WRAP_WIDTH = 96;

	private static final Map<String, List<Component>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private static int cachedGeneration = -1;

	private TooltipTranslator() {
	}

	/**
	 * Translates a tooltip's first-line item name, including the known reforge prefix shape.
	 *
	 * <p>The retry is deliberately here rather than in {@link Translator}: lore uses the same
	 * {@link Surface#ITEM} surface, while only a tooltip's first line is an item name. The remainder
	 * must match as one complete line; stripping a word must not make an ordinary item name match a
	 * looser line shape.
	 */
	public static Translator.Result translateItemName(Component source) {
		Translator.Result whole = Translator.translate(source, Surface.ITEM);

		if (whole.matched()) {
			return whole;
		}

		StyledText styled = StyledText.of(source);
		String plain = styled.canonical();
		int space = plain.indexOf(' ');

		if (space <= 0) {
			return whole;
		}

		String translatedPrefix = ITEM_REFORGE_PREFIXES.get(plain.substring(0, space));

		if (translatedPrefix == null) {
			return whole;
		}

		int remainderStart = space + 1;
		Translator.Result remainder = Translator.translate(
			styled.slice(remainderStart, styled.length()), Surface.ITEM
		);

		if (!remainder.matched() || remainder.head() != null || remainder.tail() != null) {
			return whole;
		}

		// The reforge uses Fleet's live style; its following space keeps any separate live style.
		return new Translator.Result(
			Component.literal(translatedPrefix).setStyle(styled.styleAt(0))
				.append(styled.slice(space, remainderStart))
				.append(remainder.padded()),
			null,
			null,
			remainder.entry()
		);
	}

	public static List<Component> translate(Font font, List<Component> lines) {
		SkyZHConfig config = SkyZHConfig.get();

		if (!HypixelServer.canTranslate() || lines.isEmpty()) {
			return lines;
		}

		StringBuilder key = new StringBuilder();

		for (Component line : lines) {
			StyledText styled = StyledText.of(line);
			key.append(styled.plain()).append('\n');

			for (int i = 0; i < styled.length(); i++) {
				key.append(styled.styleAt(i).hashCode()).append(',');
			}

			key.append('\n');
		}

		String cacheKey = key.toString();

		synchronized (CACHE) {
			if (cachedGeneration != SkyZHConfig.generation()) {
				CACHE.clear();
				cachedGeneration = SkyZHConfig.generation();
			}

			List<Component> cached = CACHE.get(cacheKey);

			if (cached != null) {
				return cached;
			}
		}

		// The width the tooltip would have had in English, floored so a tooltip of blank or one-word
		// lines cannot squeeze the Chinese into a column. Chinese is usually the narrower of the two,
		// so this is mostly a ceiling nothing reaches — it matters for the merged sentences, which
		// are now one line where the game had two and would otherwise stretch the box.
		int width = MIN_WRAP_WIDTH;

		for (Component line : lines) {
			width = Math.max(width, font.width(line));
		}

		List<Component> result = new ArrayList<>(lines.size());
		boolean merging = false;
		boolean name = true;

		for (int i = 0; i < lines.size(); i++) {
			Component line = lines.get(i);
			Translator.Result translated = name
				? translateItemName(line)
				: Translator.translate(line, Surface.ITEM, config.showOriginal);

			if (name) {
				// The first line of a tooltip is the item's name, and the name is what a player types
				// into the Bazaar and the Auction House. Translating it away would take the search key
				// with it, so the English is kept beside the Chinese exactly as a container title
				// keeps it — under the same switch, in the same brackets. See OriginalLabel.
				name = false;

				if (translated.matched() && config.showOriginal) {
					// Not re-wrapped: a name is one line in every language, and the tooltip is welcome
					// to be as wide as the pair needs.
					result.add(OriginalLabel.append(translated.padded(), line));
					merging = true;
					continue;
				}
			}

			if (translated.matched() && translated.entry().continuation()) {
				if (merging) {
					// The sentence this line ended is already complete on the line above. Still
					// merging, so a sentence Hypixel broke across three lines loses both tails.
					continue;
				}

				// The line above is still English, so its other half has to stay too.
				result.add(line);
				continue;
			}

			if (translated.matched()) {
				List<TranslationEntry.Matched> joined = new ArrayList<>();
				joined.add(new TranslationEntry.Matched(translated.entry(), translated.matchedCore(), translated.match()));

				int tail = i + 1;

				while (tail < lines.size()) {
					Translator.Result continuation = Translator.translate(lines.get(tail), Surface.ITEM);

					if (!continuation.matched() || !continuation.entry().continuation()) {
						break;
					}

					joined.add(new TranslationEntry.Matched(
						continuation.entry(), continuation.matchedCore(), continuation.match()
					));
					tail++;
				}

				if (joined.size() == 1) {
					result.addAll(TextLayout.wrap(font, translated.padded(), width));
				} else {
					Component rendered = Translator.skyBlockName(
						TranslationEntry.renderJoined(joined, Translator.index().terms(), config.showOriginal), config
					);
					Component padded = pad(translated.head(), rendered, translated.tail());

					result.addAll(TextLayout.wrap(font, padded, width));
					i = tail - 1;
				}
			} else {
				// No record answers for the whole line, which is the normal state of an enchantment
				// line: it lists whichever enchantments this item carries, and the corpus holds them
				// one at a time. Tried only after the whole line has failed, so a record that spells
				// a comma out still wins.
				Component list = Translator.translateList(line, Surface.ITEM);

				result.add(list != null ? list : translated.padded());
			}

			merging = translated.matched();
		}

		List<Component> immutable = List.copyOf(result);

		synchronized (CACHE) {
			CACHE.put(cacheKey, immutable);
		}

		return immutable;
	}

	private static Component pad(Component head, Component core, Component tail) {
		if (head == null && tail == null) {
			return core;
		}

		MutableComponent result = Component.empty();

		if (head != null) {
			result.append(head);
		}

		result.append(core);

		if (tail != null) {
			result.append(tail);
		}

		return result;
	}
}
