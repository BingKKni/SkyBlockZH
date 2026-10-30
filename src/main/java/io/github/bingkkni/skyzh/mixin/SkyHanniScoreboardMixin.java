package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.PriorityText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoid two overlapping sidebars when Force restores the server-owned one. No hard dependency. */
@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard", remap = false)
public abstract class SkyHanniScoreboardMixin {
	@Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void skyzh$preferServerSidebar(CallbackInfoReturnable<Boolean> info) {
		if (PriorityText.force()) info.setReturnValue(false);
	}
}
