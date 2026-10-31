package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CustomScoreboard forwards its panel clicks through vanilla widgets even when its draws are hidden. */
@Mixin(value = AbstractWidget.class, priority = Integer.MAX_VALUE)
public abstract class ForceSidebarWidgetMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$skipHiddenSidebarClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> info) {
		if (ForceSidebar.suppressCustomClick()) info.setReturnValue(false);
	}
}
