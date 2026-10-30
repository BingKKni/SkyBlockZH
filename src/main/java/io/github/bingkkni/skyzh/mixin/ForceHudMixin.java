package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Submit our sidebar after ordinary HUD extraction, even when its original method was cancelled. */
@Mixin(value = GameRenderer.class, priority = 1)
public abstract class ForceHudMixin {
	@Shadow @Final private GameRenderState gameRenderState;

	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"), require = 0)
	private void skyzh$finalSidebar(DeltaTracker tracker, boolean advanceGameTime, CallbackInfo info) {
		if (!advanceGameTime || !ForceSidebar.ownsDisplay()) return;
		Minecraft mc = Minecraft.getInstance();
		ForceSidebar.renderFinal(new GuiGraphicsExtractor(mc, this.gameRenderState.guiRenderState, 0, 0));
	}
}
