package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.hook.SidebarCompatibility;
import io.github.bingkkni.skyzh.hook.ForceSidebar;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Submit our sidebar after ordinary HUD extraction, even when its original method was cancelled. */
@Mixin(value = GameRenderer.class, priority = 1)
public abstract class ForceHudMixin {
	@Shadow @Final private GameRenderState gameRenderState;

	@WrapMethod(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", require = 0)
	private void skyzh$finalSidebar(DeltaTracker tracker, boolean advanceGameTime, Operation<Void> original) {
		if (!advanceGameTime || !ForceSidebar.ownsDisplay()) { original.call(tracker, advanceGameTime); return; }
		ForceSidebar.withFrame(SkyZHConfig.get().displayPriority == DisplayPriority.FORCE && SidebarCompatibility.hasCustomSidebar(),
			() -> original.call(tracker, advanceGameTime),
			() -> ForceSidebar.renderFinal(new GuiGraphicsExtractor(Minecraft.getInstance(), this.gameRenderState.guiRenderState, 0, 0)));
	}
}
