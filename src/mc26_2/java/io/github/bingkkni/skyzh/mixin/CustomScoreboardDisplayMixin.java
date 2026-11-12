package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.hook.SidebarCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Optional 26.2–26.3 layout scope; the renderer keeps its rows, visibility and interactions. */
@Pseudo
@Mixin(targets = "me.owdding.customscoreboard.core.CustomScoreboardRenderer", remap = false)
public abstract class CustomScoreboardDisplayMixin {
	@Shadow public abstract void updateDisplay();
	@Unique private boolean skyzh$translated;
	@Unique private int skyzh$generation = -1;
	@Unique private Object skyzh$level;
	@Unique private SidebarCompatibility.Layout skyzh$view = new SidebarCompatibility.Layout();

	@WrapMethod(method = "updateDisplay", require = 0)
	private void skyzh$layout(Operation<Void> original) {
		io.github.bingkkni.skyzh.hook.PriorityText.refreshSidebar();
		SidebarCompatibility.Layout view = new SidebarCompatibility.Layout();
		SidebarCompatibility.display(view, () -> original.call());
		view.seal();
		this.skyzh$view = view;
	}

	@WrapMethod(method = "onRender", require = 0)
	private void skyzh$render(@Coerce Object event, Operation<Void> original) {
		boolean translated = HypixelServer.canTranslate();
		int generation = SkyZHConfig.generation();
		Object level = net.minecraft.client.Minecraft.getInstance().level;
		// Widget widths and their translation decisions must be invalidated together.
		if (this.skyzh$translated != translated || this.skyzh$generation != generation || this.skyzh$level != level) {
			this.skyzh$level = level;
			this.skyzh$translated = translated;
			this.skyzh$generation = generation;
			updateDisplay();
		}
		SidebarCompatibility.display(this.skyzh$view, () -> original.call(event));
	}
}
