package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.PriorityText;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Runs after ordinary extractor modifications, including custom HUD text submissions. */
@Mixin(value = GuiTextRenderState.class, priority = 1)
public abstract class PriorityGuiTextMixin {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 2, require = 0)
	private static FormattedCharSequence skyzh$lateTranslation(FormattedCharSequence source) {
		FormattedCharSequence translated = PriorityText.resolve(source);
		return translated == null ? source : translated;
	}
}
