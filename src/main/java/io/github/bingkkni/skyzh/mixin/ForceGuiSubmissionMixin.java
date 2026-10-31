package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** All vanilla GUI text, rectangles, images and item submissions pass this gate before enqueueing. */
@Mixin(value = GuiRenderState.class, priority = Integer.MAX_VALUE)
public abstract class ForceGuiSubmissionMixin {
	@Inject(method = "findAppropriateNode", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$skipCustomSidebar(ScreenArea area, CallbackInfoReturnable<Boolean> info) {
		if (ForceSidebar.suppressCustomDraw()) info.setReturnValue(false);
	}
}
