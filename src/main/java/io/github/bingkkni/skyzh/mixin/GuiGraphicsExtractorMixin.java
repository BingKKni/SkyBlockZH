package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Item names and lore, in containers and in the player's own inventory alike.
 *
 * <p>This is the funnel every item tooltip passes through: the {@code ItemStack} overload builds its
 * lines with {@code Screen#getTooltipFromItem} and hands them here. Replacing the list at this point
 * translates what is drawn without touching the stack, its components, or the list vanilla built —
 * and lines other mods contributed have already been added by then, so nothing is lost by
 * translating after them rather than before.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
	@ModifyVariable(
		method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
		at = @At("HEAD"),
		argsOnly = true,
		index = 2,
		require = 0
	)
	private List<Component> skyzh$translateTooltip(List<Component> lines) {
		return TooltipTranslator.translate(Minecraft.getInstance().font, lines);
	}
}
