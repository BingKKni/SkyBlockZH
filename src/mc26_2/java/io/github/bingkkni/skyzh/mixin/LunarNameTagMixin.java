package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.compat.LunarClient;
import io.github.bingkkni.skyzh.hook.NameTag;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lunar caches names at extraction RETURN and cancels vanilla name submission. Target vanilla only. */
@Mixin(EntityRenderer.class)
public abstract class LunarNameTagMixin {
	@Unique private NameTag.Extraction skyzh$nameExtraction;

	@WrapMethod(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", require = 0)
	private void skyzh$extractDisplayName(Entity entity, EntityRenderState state, float partialTicks, Operation<Void> original) {
		if (!LunarClient.present() || !HypixelServer.canTranslate()) {
			original.call(entity, state, partialTicks);
			return;
		}
		NameTag.Extraction previous = this.skyzh$nameExtraction;
		try (NameTag.Extraction extraction = new NameTag.Extraction(state)) {
			this.skyzh$nameExtraction = extraction;
			original.call(entity, state, partialTicks);
		} finally {
			this.skyzh$nameExtraction = previous;
		}
	}

	@Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
		at = @At("RETURN"), require = 0)
	private void skyzh$displayName(Entity entity, EntityRenderState state, float partialTicks,
		double nameDistance, double scoreDistance, CallbackInfo info) {
		NameTag.Extraction extraction = this.skyzh$nameExtraction;
		if (extraction == null || !extraction.owns(state) || state.nameTag == null) return;
		boolean hologram = state instanceof ArmorStandRenderState && state.isInvisible;
		boolean mob = state instanceof LivingEntityRenderState
			&& !(state instanceof AvatarRenderState) && !(state instanceof ArmorStandRenderState);
		// Identities (including player NPCs), pets and unknown proper names keep the existing gate.
		if (hologram || mob) extraction.display(NameTag.translate(state.nameTag, hologram, mob));
	}
}
