package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.ForceSidebar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Optional marker scope only: no settings, data, arguments or renderer behavior are changed. */
@Pseudo
@Mixin(targets = "me.owdding.customscoreboard.core.CustomScoreboardRenderer", remap = false)
public abstract class CustomSidebarScopeMixin {
	@WrapMethod(method = "onRender(Ltech/thatgravyboat/skyblockapi/api/events/render/RenderHudEvent;)V", require = 0)
	private void skyzh$scope(@Coerce Object event, Operation<Void> original) {
		ForceSidebar.withRenderer(ForceSidebar.CUSTOM, () -> original.call(event));
	}
}
