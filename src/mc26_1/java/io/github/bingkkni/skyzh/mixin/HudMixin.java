package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.bingkkni.skyzh.hook.HudText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where {@link HudText} attaches on 26.1.x: the HUD is {@code Gui}, which 26.2 split into a
 * {@code Gui} that owns screens and a {@code Hud} that draws. The method names inside it, and the
 * {@code textWithBackdrop} they call, are the same in both — only the class differs, so the 26.2 copy
 * of this file is these three hooks against {@code Hud}.
 *
 * <p>Read {@link HudText} for what these do and why the coordinates are recomputed.
 */
@Mixin(Gui.class)
public abstract class HudMixin {
	private static final String TEXT_WITH_BACKDROP =
		"Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V";

	@WrapOperation(method = "extractOverlayMessage", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateActionBar(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Void> original
	) {
		HudText.actionBar(graphics, font, text, x, y, width, color, original);
	}

	/** Both calls in this method — the title and the subtitle under it. */
	@WrapOperation(method = "extractTitle", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateTitle(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Void> original
	) {
		HudText.title(graphics, font, text, x, y, width, color, original);
	}

	@WrapOperation(method = "extractSelectedItemName", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateSelectedItemName(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Void> original
	) {
		HudText.selectedItemName(graphics, font, text, x, y, width, color, original);
	}
}
