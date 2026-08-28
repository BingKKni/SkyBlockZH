package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.ContainerTitle;
import io.github.bingkkni.skyzh.text.TextLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The title line at the top of a chest or menu.
 *
 * <p>{@code ordinal = 0} because {@code extractLabels} draws two labels through the same call — the
 * container's title and then "Inventory" — and only the first is the server's. The screen's own
 * {@code title} field is never written to, so {@code Screen#getTitle} keeps returning the English
 * that other mods key their menu detection off.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	protected int imageWidth;

	@Redirect(
		method = "extractLabels",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
			ordinal = 0
		),
		require = 0
	)
	private void skyzh$translateContainerTitle(
		GuiGraphicsExtractor graphics, Font font, Component title, int x, int y, int color, boolean shadow
	) {
		// Four pixels of breathing room each side, so a title that grew is clipped by the mod rather
		// than by the edge of the panel.
		ContainerTitle.Rendered rendered = ContainerTitle.of(font, title, this.imageWidth - 8);
		int drawX = rendered.centered() ? TextLayout.centeredX(font, rendered.text(), this.imageWidth) : x;

		graphics.text(font, rendered.text(), drawX, y, color, shadow);
	}
}
