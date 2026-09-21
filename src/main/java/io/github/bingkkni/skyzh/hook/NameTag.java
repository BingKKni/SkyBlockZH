package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Glyphs;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import io.github.bingkkni.skyzh.HoldOriginal;
import io.github.bingkkni.skyzh.SkyZHConfig;
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
 * <p>Authored hologram text is restricted to invisible armour stands. Explicit role records may
 * translate green NPC labels, but unknown green proper names are left alone. Non-player living
 * entities have a separate, bounded health-bar path: only a whole mob name already in the term table
 * is replaced, never level or health. Capture omits dynamic bars, pet tags and race leaderboard rows
 * rather than recording each changing value as a new translation task.
 *
 * <p>Should the entity-note hook stop applying, the flag stays false and name tags stay untouched.
 * That fail-closed behaviour is intentional: translating a player's name or an unknown proper name
 * by accident is worse than leaving one hologram in English.
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
	private static final Pattern MOB_BAR = Pattern.compile(
		"^(?:﴾\\s*)?(?:\\[Lv[0-9]+\\]\\s*)?[\\p{Co}\\p{S}]*\\s*([A-Za-z][A-Za-z '\\-]+?)\\s+(?:[0-9][0-9,.]*[kKmMbBtT]?(?:/[0-9][0-9,.]*[kKmMbBtT]?)?|\\?+)❤(?:\\s*﴿)?\\s*$"
	);
	private static final Pattern PET_TAG = Pattern.compile("^(?:#[0-9]+ )?\\[Lv(?:l )?[0-9]+\\] .+");
	private static final Pattern RANKED_PLAYER = Pattern.compile(
		"^\\[(?:VIP|MVP|YOUTUBE|ADMIN|MOD|HELPER|GM|OWNER|MOJANG|EVENTS|MCP|PIG)\\+{0,3}\\] [A-Za-z0-9_]{1,16}$"
	);
	private static final Pattern LEADERBOARD = Pattern.compile("^[0-9]+\\. [A-Za-z0-9_]{1,16} - [0-9:.]+$");
	/**
	 * One bare token that reads as a Minecraft username rather than a word: a digit or underscore in
	 * it, a lowercase start, or a capital in the middle ({@code ZillPap}, {@code kimisfvs},
	 * {@code LittlePecker2}). A pet leaderboard and a museum entrance both hang player names in the
	 * air, and none of them is text anybody translates. Game words are capitalised whole
	 * ({@code Start}, {@code HOTSPOT}) and never match.
	 */
	private static final Pattern PLAYER_LIKE = Pattern.compile(
		"^(?:[a-z][A-Za-z0-9_]{0,15}|[A-Za-z0-9_]*[0-9_][A-Za-z0-9_]*|[A-Z][a-z0-9_]*[a-z][A-Z][A-Za-z0-9_]*)$"
	);

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
		return !plain.isEmpty() && !MOB_HEALTH.matcher(plain).find()
			&& !MOB_BAR.matcher(plain).matches() && !PET_TAG.matcher(plain).matches()
			&& !LEADERBOARD.matcher(plain).matches() && !RANKED_PLAYER.matcher(plain).matches()
			&& (plain.length() > 16 || !PLAYER_LIKE.matcher(plain).matches())
			&& (!(npcName(styled, plain) || Translator.index().isNpcName(plain))
				|| Translator.locate(styled, Surface.HOLOGRAM).matched());
	}

	/**
	 * Green name-shaped text is conservatively treated as a proper name unless an explicit hologram
	 * record answers. Colour does not itself prove that a role such as Lift Operator is a proper name.
	 *
	 * <p>The corpus's own NPC list is the other half of that rule and does not depend on colour: Hub
	 * NPCs wear white, aqua, red and purple nameplates, and every one of them is a name the project
	 * keeps in English. A role-type name the glossary does translate ({@code Combat Merchant},
	 * {@code Lift Operator}) still translates, because a hologram record answers for it.
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

	/** Only a closed mob-name term can change; level, health and their live styles stay intact. */
	public static Component mobName(Component source) {
		StyledText styled = StyledText.of(source);
		Matcher bar = MOB_BAR.matcher(styled.canonical());
		if (!bar.matches()) {
			return source;
		}
		String zh = Translator.index().terms().translate("mob_name", bar.group(1));
		if (zh == null || zh.equals(bar.group(1))) {
			return source;
		}
		return Component.empty().append(styled.slice(0, bar.start(1)))
			.append(Component.literal(zh).setStyle(styled.styleAt(bar.start(1))))
			.append(styled.slice(bar.end(1), styled.length()));
	}

	public static Component translate(Component nameTag, boolean hologramNameTag) {
		return translate(nameTag, hologramNameTag, false);
	}

	/** Players and named items never enter the living-mob branch. */
	public static Component translate(Component nameTag, boolean hologramNameTag, boolean livingMob) {
		if ((!hologramNameTag && !livingMob) || !HypixelServer.canTranslate()
			|| !SkyZHConfig.get().enabled || HoldOriginal.active()) {
			return nameTag;
		}
		Component mob = mobName(nameTag);
		if (mob != nameTag) {
			return mob;
		}
		return hologramNameTag && eligible(nameTag)
			? Translator.translateLine(nameTag, Surface.HOLOGRAM) : nameTag;
	}
}
