package io.github.bingkkni.skyzh.text;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * The English kept alongside the Chinese, as 收藏品（Collections）.
 *
 * <p>Container titles and item names were the first two callers: a title so a player can tell which
 * of Hypixel's menus they are in, and an item name because the name <em>is</em> the search key for
 * the Bazaar and the Auction House. Chat NPC lines and lore lines ask for the same pair when a
 * fragment is itself a known name — 象牙化石（Tusk Fossil）, 钻石精华（Diamond Essence） — so a player
 * reading dialogue can still type the English into a search box.
 *
 * <p>Bracketing is full-width （） and the label takes the colour of the Chinese in front of it, so
 * the pair reads as one name rather than as a name with a note stuck to it. Applying this twice is
 * a no-op: a tooltip name that was already labelled as a term does not become
 * 象牙化石（Tusk Fossil）（Tusk Fossil）.
 */
public final class OriginalLabel {
	private OriginalLabel() {
	}

	/**
	 * Chinese followed by the English, with nothing to fit inside — an item tooltip is as wide as
	 * its widest line and grows to hold this.
	 *
	 * <p>Returns the Chinese untouched when the record left the line in English, since
	 * "Bazaar（Bazaar）" is noise rather than a translation aid.
	 */
	public static MutableComponent append(MutableComponent chinese, Component original) {
		String trimmed = plain(original);

		if (skip(chinese, trimmed)) {
			return chinese;
		}

		return bracket(chinese, trimmed);
	}

	/**
	 * The same pair, cut to fit a width that cannot grow.
	 *
	 * <p>A container title has a fixed area and no second line to spill onto, so a pair that will not
	 * fit loses the tail of the English to an ellipsis, and a pair that will not fit at all loses the
	 * English entirely. The Chinese is the point of the mod; the English is the courtesy.
	 */
	public static MutableComponent fit(Font font, MutableComponent chinese, Component original, int available) {
		String trimmed = plain(original);

		if (skip(chinese, trimmed)) {
			return chinese;
		}

		MutableComponent full = bracket(chinese, trimmed);

		if (font.width(full) <= available) {
			return full;
		}

		for (int length = trimmed.length() - 1; length > 0; length--) {
			MutableComponent shortened = bracket(chinese, trimmed.substring(0, length) + "…");

			if (font.width(shortened) <= available) {
				return shortened;
			}
		}

		return chinese;
	}

	private static boolean skip(MutableComponent chinese, String trimmed) {
		if (trimmed.isEmpty()) {
			return true;
		}

		String text = chinese.getString();

		return text.equals(trimmed) || text.contains("（" + trimmed + "）");
	}

	/**
	 * The line's characters with its formatting gone.
	 *
	 * <p>{@code Component#getString()} is not enough: SkyBlock writes a colour as a literal
	 * {@code §9} inside the string as often as it writes it as a {@code Style}, and those codes would
	 * come through into the brackets as visible characters. {@link StyledText} understands both.
	 */
	private static String plain(Component original) {
		return StyledText.of(original).plain().trim();
	}

	private static MutableComponent bracket(MutableComponent chinese, String english) {
		Style style = StyledText.of(chinese).styleAt(0);

		return chinese.copy().append(Component.literal("（" + english + "）").setStyle(style));
	}
}
