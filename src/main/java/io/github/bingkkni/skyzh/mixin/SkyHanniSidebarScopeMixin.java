package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.ForceSidebar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Only the verified custom-scoreboard entry, never SkyHanni's other HUDs. */
@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard", remap = false)
public abstract class SkyHanniSidebarScopeMixin {
	@WrapMethod(method = "onGuiRenderOverlay()V", require = 0)
	private void skyzh$scope(Operation<Void> original) {
		ForceSidebar.withRenderer(ForceSidebar.SKYHANNI, () -> original.call());
	}
}
