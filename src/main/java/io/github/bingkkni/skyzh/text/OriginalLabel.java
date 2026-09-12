package io.github.bingkkni.skyzh.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * The English search name kept alongside a translated item, as 象牙化石（Tusk Fossil）.
 *
 * <p>The item name is the search key for the Bazaar and the Auction House. Chat and lore use the
 * same pair only after {@link ItemNames} has proved that the fragment is an actual SkyBlock item —
 * 象牙化石（Tusk Fossil）, 钻石精华（Diamond Essence）. Events, buttons, page labels and states are
 * never inferred from a general term table, so translated UI text does not gain decorative brackets.
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
