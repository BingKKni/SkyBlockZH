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
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
	private static final Pattern DUNGEON_STARS = Pattern.compile("( [✪★☆➊➋➌➍➎]+)$");
	private static final Pattern STACK_COUNT = Pattern.compile(" x[0-9][0-9,]*$");
	private static final Pattern ICON_TOKEN = Pattern.compile(Capture.of("icon").regex());
	// Reforge prefixes and their Chinese live in _shared/Terms.json under type "reforge". Only names
	// written there count; a broader first-word heuristic would turn an ordinary item's first word
	// into a reforge prefix.

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
		NameParts parts = nameParts(plain);
		int contentStart = parts.contentStart();
		int itemStart = parts.itemStart();
		int starStart = parts.suffixStart();
		String translatedPrefix = parts.translatedPrefix();
		int prefixLength = parts.prefixLength();

		if (!parts.decorated() || itemStart >= starStart) return whole;
		Translator.Result remainder = Translator.translate(styled.slice(itemStart, starStart), Surface.ITEM);
		boolean preserved = Translator.index().preserved(Surface.ITEM, plain.substring(itemStart, starStart));
		if ((!remainder.matched() && !preserved) || remainder.head() != null || remainder.tail() != null) return whole;

		MutableComponent result = Component.empty();
		if (contentStart > 0) result.append(styled.slice(0, contentStart));
		if (translatedPrefix != null) {
			result.append(Component.literal(translatedPrefix).setStyle(styled.styleAt(contentStart)))
				.append(styled.slice(contentStart + prefixLength, itemStart));
		}
		result.append(remainder.padded());
		if (starStart < plain.length()) result.append(styled.slice(starStart, plain.length()));
		return new Translator.Result(result, null, null, remainder.entry());
	}

	/** Capture checks the same undecorated name that the renderer translates, only on first lines. */
	public static StyledText itemNameCore(StyledText styled) {
		if (Translator.locate(styled, Surface.ITEM).matched()) return styled;
		NameParts parts = nameParts(styled.canonical());
		if (!parts.decorated() || parts.itemStart() >= parts.suffixStart()) return styled;
		StyledText core = styled.sub(parts.itemStart(), parts.suffixStart());
		Translator.Located found = Translator.locate(core, Surface.ITEM);
		boolean complete = found.matched() && found.core().length() == core.length();
		return complete || Translator.index().preserved(Surface.ITEM, core.canonical()) ? core : styled;
	}

	private record NameParts(int contentStart, int itemStart, int suffixStart,
		String translatedPrefix, int prefixLength, boolean decorated) {}

	private static NameParts nameParts(String plain) {
		int contentStart = leadingIconEnd(plain);
		int suffixStart = plain.length();
		var count = STACK_COUNT.matcher(plain);

		if (count.find()) {
			suffixStart = count.start();
		}

		int starStart = suffixStart;
		var stars = DUNGEON_STARS.matcher(plain.substring(0, suffixStart));

		if (stars.find() && stars.start() >= contentStart) {
			starStart = stars.start();
		}

		String translatedPrefix = null;
		int prefixLength = 0;

		// A catalog item whose own name starts with a reforge word (Hyper Catalyst, Heavy Helmet) is not
		// reforged: splitting it would draw the adjective as a reforge in front of some other item.
		if (!ItemNames.isBaseName(plain.substring(contentStart, starStart))) {
			// Longest prefix first: "Deep Fried" must win over any one-word reforge that "Deep" could be.
			for (Map.Entry<String, String> reforge : Translator.index().terms().typed("reforge").entrySet()) {
				String prefix = reforge.getKey();
				int end = contentStart + prefix.length();

				if (plain.length() > end + 1 && plain.startsWith(prefix, contentStart)
					&& plain.charAt(end) == ' ' && prefix.length() > prefixLength) {
					translatedPrefix = reforge.getValue();
					prefixLength = prefix.length();
				}
			}
		}

		int itemStart = translatedPrefix == null ? contentStart : contentStart + prefixLength + 1;

		if (starStart < itemStart) {
			starStart = suffixStart;
		}

		boolean hasStars = starStart < suffixStart;
		boolean hasCount = suffixStart < plain.length();

		return new NameParts(contentStart, itemStart, starStart, translatedPrefix, prefixLength,
			translatedPrefix != null || contentStart > 0 || hasStars || hasCount);
	}

	/** Skips only leading single-glyph tokens, preserving them outside a translated reforge/item name. */
	private static int leadingIconEnd(String plain) {
		int start = 0;

		while (start < plain.length()) {
			int end = plain.indexOf(' ', start);

			if (end < 0) {
				break;
			}

			String token = plain.substring(start, end);

			if (token.codePointCount(0, token.length()) != 1 || !ICON_TOKEN.matcher(token).matches()) {
				break;
			}

			start = end + 1;
		}

		return start;
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
