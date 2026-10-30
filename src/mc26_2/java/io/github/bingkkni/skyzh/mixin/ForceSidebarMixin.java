package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Hud.class, priority = Integer.MAX_VALUE)
public abstract class ForceSidebarMixin {
	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo info) {
		if (ForceSidebar.render(graphics, objective)) info.cancel();
	}
}
