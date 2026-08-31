package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.HudText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Where {@link HudText} attaches on 26.2: the HUD is {@code Hud}, split out of {@code Gui} in this
 * version. The 26.1.x copy of this file is the same three redirects against {@code Gui}.
 *
 * <p>Read {@link HudText} for what these do and why the coordinates are recomputed.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	private static final String TEXT_WITH_BACKDROP =
		"Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V";

	@Redirect(method = "extractOverlayMessage", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateActionBar(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		HudText.actionBar(graphics, font, text, x, y, width, color);
	}

	/** Both calls in this method — the title and the subtitle under it. */
	@Redirect(method = "extractTitle", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateTitle(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		HudText.title(graphics, font, text, x, y, width, color);
	}

	@Redirect(method = "extractSelectedItemName", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateSelectedItemName(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		HudText.selectedItemName(graphics, font, text, x, y, width, color);
	}
}
