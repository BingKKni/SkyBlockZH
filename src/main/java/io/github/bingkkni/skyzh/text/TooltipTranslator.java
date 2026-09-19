package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.HoldOriginal;
import io.github.bingkkni.skyzh.OriginalTips;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

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

	/** The terminal tests English initials; hiding the original names would hide the question's data. */
	public static boolean requiresOriginalName(Component title) {
		return title != null && StyledText.of(title).plain().trim().matches("What starts with: '[A-Z]'\\?");
	}

	public static List<Component> translate(Font font, List<Component> lines) {
		SkyZHConfig config = SkyZHConfig.get();

		if (!HypixelServer.canTranslate() || !config.enabled || HoldOriginal.active() || lines.isEmpty()) {
			return lines;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft == null ? null : ClientGui.screen(minecraft);
		boolean terminalNames = screen instanceof AbstractContainerScreen<?> && requiresOriginalName(screen.getTitle());
		String hintKey = config.originalTips && screen != null ? HoldOriginal.keyName() : null;
		// A tooltip can be identical in a shop and in a terminal; the display policy is part of its key.
		StringBuilder key = new StringBuilder().append(hintKey).append(':').append(terminalNames).append('\n');

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
		Component itemName = lines.getFirst();
		Translator.Result translatedName = translateItemName(itemName);
		boolean hint = hintKey != null && !terminalNames && OriginalTips.eligibleName(itemName, translatedName);

		if (terminalNames) {
			result.add(itemName);
		} else {
			result.addAll(TextLayout.wrap(font, translatedName.padded(), width));
		}

		for (LoreTranslation.Unit unit : LoreTranslation.plan(lines.subList(1, lines.size()))) {
			if (unit.translated()) {
				result.addAll(TextLayout.wrap(font, unit.rendered(), width));
			} else {
				result.add(unit.rendered());
			}
		}

		if (hint) {
			result.add(OriginalTips.loreHint(hintKey));
		}
		List<Component> immutable = List.copyOf(result);

		synchronized (CACHE) {
			CACHE.put(cacheKey, immutable);
		}

		return immutable;
	}
}
