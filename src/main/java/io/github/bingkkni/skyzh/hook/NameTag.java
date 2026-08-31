package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.network.chat.Component;

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
 * <p><b>Never a player's name.</b> A Minecraft name is sixteen characters of anybody's choosing, so
 * somebody is called Blacksmith, and this surface is exactly where the corpus keeps the word
 * Blacksmith. Whose name a tag is cannot be read off the tag, so it is read off the entity: the
 * per-version mixin notes at the top of the method whether the entity being drawn is a player, and
 * passes that here. Should that note ever fail to be written — a hook that stopped applying — it stays
 * false and every tag is translated, which is the same behaviour as having no filter and the harmless
 * way for it to break.
 *
 * <p>No caching. A hub full of NPCs is a hundred or so name tags a frame, fewer than the tab list
 * already puts through this same path every frame, and a line nothing answers for costs one hash
 * lookup once {@link io.github.bingkkni.skyzh.text.TranslationIndex} has remembered the miss.
 *
 * <p><b>Why this is not in the mixin.</b> Two things about the target moved in 26.2: the player entity
 * type constant went from {@code EntityType.PLAYER} to {@code EntityTypes.PLAYER}, and
 * {@code submitNameTag} lost a {@code double} parameter — which is a change to the descriptor string a
 * {@code @ModifyArg} has to match exactly, and one that a {@code require = 0} hook would answer by
 * silently not applying. So the descriptor and the constant are written once per target, and the
 * decision about what to translate is written here.
 */
public final class NameTag {
	private NameTag() {
	}

	/**
	 * @param playerNameTag whether this tag belongs to a player, in which case it is somebody's name
	 *                      and not text to translate
	 */
	public static Component translate(Component nameTag, boolean playerNameTag) {
		return playerNameTag ? nameTag : Translator.translateLine(nameTag, Surface.HOLOGRAM);
	}
}
