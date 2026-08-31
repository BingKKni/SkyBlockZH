package io.github.bingkkni.skyzh.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.bingkkni.skyzh.hook.NameTag;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where {@link NameTag} attaches on 26.2. Two things differ from the 26.1.x copy of this file: the
 * player constant is {@code EntityTypes.PLAYER} (it was {@code EntityType.PLAYER}), and
 * {@code submitNameTag} takes no {@code double} — 26.1.x passes {@code distanceToCameraSq} and 26.2
 * does not, which changes the descriptor a {@code @ModifyArg} has to match character for character.
 *
 * <p>Read {@link NameTag} for what is translated here and why a player's name never is.
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
		return NameTag.translate(nameTag, skyzh$playerNameTag);
	}
}
