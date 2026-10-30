package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The opt-in tooltip hook executes after ordinary HEAD list modifiers. */
@Mixin(value = GuiGraphicsExtractor.class, priority = 1)
public abstract class PriorityTooltipMixin {
	@ModifyVariable(
		method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
		at = @At("HEAD"), argsOnly = true, index = 2, require = 0
	)
	private List<Component> skyzh$lateTooltip(List<Component> lines) {
		return SkyZHConfig.get().displayPriority == DisplayPriority.DEFAULT ? lines
			: TooltipTranslator.translate(Minecraft.getInstance().font, lines);
	}
}
