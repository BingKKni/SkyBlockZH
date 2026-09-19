package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Glyphs;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The floating text over an entity's head.
 *
 * <p>SkyBlock's NPCs are armour stands wearing a name, and the name is how the game tells a player
 * what to do with them: a yellow {@code CLICK} over the stand, and under it what the NPC is there
 * for. That is instruction text standing in the middle of the world.
 *
 * <p>The hook is on the submission, not on the render state. {@code EntityRenderState.nameTag} is
 * built during extraction and is a field other things may read; the argument passed to
 * {@code submitNameTag} is the last thing that happens to the text before it becomes glyphs, so
 * changing it there changes pixels and nothing else. Both calls in the method go through it — the
 * score under the name as well as the name — and the score is a number no record will answer for.
 *
 * <p><b>Only an armour-stand hologram.</b> Player names, mob names and health, named dropped items and
 * other entities all reach the same vanilla submission method. SkyBlock's authored hologram layers
 * are invisible armour stands, so each version's mixin admits that render-state shape and rejects every
 * other entity. Content filters then reject dynamic mob-health layers and the green proper-name line
 * above an NPC, while keeping the yellow role/instruction line above it. Runtime capture uses these
 * same predicates, so text this hook deliberately leaves alone cannot become translation work on
 * disk.
 *
 * <p>Should the entity-note hook stop applying, the flag stays false and name tags stay untouched.
 * That fail-closed behaviour is intentional: translating a player's or mob's name by accident is
 * worse than leaving one hologram in English.
 *
 * <p>No caching. A hub full of NPCs is a hundred or so name tags a frame, fewer than the tab list
 * already puts through this same path every frame, and a line nothing answers for costs one hash
 * lookup once {@link io.github.bingkkni.skyzh.text.TranslationIndex} has remembered the miss.
 *
 * <p><b>Why this is not in the mixin.</b> The target descriptor changed in 26.2:
 * {@code submitNameTag} lost a {@code double} parameter. A {@code @ModifyArg} has to match that
 * descriptor exactly, and a {@code require = 0} hook answers a stale descriptor by silently not
 * applying. The descriptor is therefore written once per target; the decision about what text to
 * translate is written here.
 */
public final class NameTag {
	/** Entity metadata indices fixed by the vanilla protocol for both supported versions. */
	private static final int SHARED_FLAGS = 0;
	private static final int CUSTOM_NAME = 2;
	private static final int CUSTOM_NAME_VISIBLE = 3;

	/** A dynamic mob bar, not authored text: {@code Glacite Walker 1.2M❤}. */
	private static final Pattern MOB_HEALTH = Pattern.compile(
		"(?:^|\\s)\\d[\\d,.]*[kKmMbBtT]?(?:/\\d[\\d,.]*[kKmMbBtT]?)?❤(?:\\s*[﴾﴿])?\\s*$"
	);

	/** NPC proper names use one plain green run; their yellow role/instruction line does not. */
	private static final Pattern NPC_NAME = Pattern.compile(
		"[\\p{L}\\d_'’.\\-]+(?:\\s+[\\p{L}\\d_'’.\\-]+){0,7}"
	);
	private static final TextColor NPC_NAME_COLOUR = TextColor.fromLegacyFormat(ChatFormatting.GREEN);

	private NameTag() {
	}

	/**
	 * Whether one metadata value can make an entity become a capture-eligible hologram.
	 *
	 * <p>The invisibility bit is inside the shared-flags byte rather than a boolean value. Checking the
	 * exact protocol indices makes split name/visibility/invisibility packets converge without waking
	 * capture for armour-stand poses or unrelated entity booleans.
	 */
	public static boolean metadataAffectsHologram(int id) {
		return id == SHARED_FLAGS || id == CUSTOM_NAME || id == CUSTOM_NAME_VISIBLE;
	}

	/**
	 * Whether this visible armour-stand name is authored hologram text worth translating or capturing.
	 * The icon spelling is canonicalised so the server's private-use health glyph is treated exactly
	 * like the ordinary heart used by corpus and tests.
	 */
	public static boolean eligible(Component nameTag) {
		return nameTag != null && eligible(StyledText.of(nameTag));
	}

	/** The component-free form used by capture's final classification gate. */
	public static boolean eligible(StyledText styled) {
		String plain = Glyphs.canonical(styled.plain()).trim();
		return !plain.isEmpty() && !MOB_HEALTH.matcher(plain).find() && !npcName(styled, plain);
	}

	/**
	 * Hypixel renders an NPC's proper name as an unformatted green name and puts the useful role or
	 * interaction prompt on another, differently styled hologram. Looking at the words cannot separate
	 * {@code Jotraeline Greatforge} from {@code Drill Mechanic}; the live style can.
	 */
	private static boolean npcName(StyledText styled, String plain) {
		if (!NPC_NAME.matcher(plain).matches()) {
			return false;
		}

		for (int i = 0; i < styled.length(); i++) {
			if (Character.isWhitespace(styled.plain().charAt(i))) {
				continue;
			}

			Style style = styled.styleAt(i);
			if (!NPC_NAME_COLOUR.equals(style.getColor()) || style.isBold() || style.isItalic()) {
				return false;
			}
		}

		return true;
	}

	/** @param hologramNameTag whether the entity being drawn is an invisible-armour-stand hologram */
	public static Component translate(Component nameTag, boolean hologramNameTag) {
		return !hologramNameTag || !HypixelServer.canTranslate() || !eligible(nameTag)
			? nameTag : Translator.translateLine(nameTag, Surface.HOLOGRAM);
	}
}
