package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.bingkkni.skyzh.hook.HudText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Where {@link HudText} attaches on 26.2: the HUD is {@code Hud}, split out of {@code Gui} in this
 * version. The 26.1.x copy of this file is the same three hooks against {@code Gui}.
 *
 * <p>Read {@link HudText} for what these do and why the coordinates are recomputed.
 */
@Mixin(Hud.class)
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
