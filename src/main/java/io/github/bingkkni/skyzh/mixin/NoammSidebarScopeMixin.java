package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.ForceSidebar;
import kotlin.Pair;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The HUD base calls background separately from draw, so both verified entries need a scope. */
@Pseudo
@Mixin(targets = "com.github.noamm9.features.impl.visual.Scoreboard$hud$1", remap = false)
public abstract class NoammSidebarScopeMixin {
	@WrapMethod(method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Z)Lkotlin/Pair;", require = 0)
	private Pair<Float, Float> skyzh$draw(GuiGraphicsExtractor graphics, boolean example,
		Operation<Pair<Float, Float>> original) {
		return ForceSidebar.withRendererResult(ForceSidebar.NOAMM_DRAW, () -> original.call(graphics, example));
	}

	@WrapMethod(method = "drawBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", require = 0)
	private void skyzh$background(GuiGraphicsExtractor graphics, int mouseX, int mouseY, Operation<Void> original) {
		ForceSidebar.withRenderer(ForceSidebar.NOAMM_BACKGROUND, () -> original.call(graphics, mouseX, mouseY));
	}
}
