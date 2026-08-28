package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The floating text over an entity's head.
 *
 * <p>SkyBlock's NPCs are armour stands wearing a name, and the name is how the game tells a player
 * what to do with them: a yellow {@code CLICK} over the stand, and under it what the NPC is there
 * for. That is instruction text standing in the middle of the world, and until now it was the one
 * piece of SkyBlock's interface the mod never looked at.
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
 * {@link #skyzh$noteEntity} hook runs at the top of the same method, on the same thread, and leaves
 * a note for the one below it. Should that hook ever fail to apply, the note stays false and every
 * tag is translated — the same behaviour as having no filter, which is the harmless way for it to
 * break.
 *
 * <p>No caching. A hub full of NPCs is a hundred or so name tags a frame, fewer than the tab list
 * already puts through this same path every frame, and a line nothing answers for costs one hash
 * lookup once {@link io.github.bingkkni.skyzh.text.TranslationIndex} has remembered the miss.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	private static final String SUBMIT_NAME_DISPLAY =
		"submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V";

	/**
	 * Whether the tag about to be drawn belongs to a player.
	 *
	 * <p>On the renderer rather than on the class, because that is the object both hooks are handed:
	 * one renderer draws one entity's tag at a time, on the render thread, so the note written at the
	 * top of the method is still the right one when the tag is submitted a few lines later.
	 */
	@Unique
	private boolean skyzh$playerNameTag;

	@Inject(method = SUBMIT_NAME_DISPLAY, at = @At("HEAD"), require = 0)
	private void skyzh$noteEntity(
		EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera,
		int light, CallbackInfo callback
	) {
		skyzh$playerNameTag = state.entityType == EntityTypes.PLAYER;
	}

	@ModifyArg(
		method = SUBMIT_NAME_DISPLAY,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
		),
		index = 3,
		require = 0
	)
	private Component skyzh$translateNameTag(Component nameTag) {
		return skyzh$playerNameTag ? nameTag : Translator.translateLine(nameTag, Surface.HOLOGRAM);
	}
}
