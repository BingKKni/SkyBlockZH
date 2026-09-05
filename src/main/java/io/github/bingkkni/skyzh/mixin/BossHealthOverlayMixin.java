package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TextLayout;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The bar at the top of the screen — SkyBlock's event timers live here.
 *
 * <p>Vanilla centres the label by measuring the English and drawing at
 * {@code guiWidth / 2 - width / 2}. The Chinese is a different width, so the position is recomputed
 * from the text actually being drawn; the {@code BossEvent} keeps its original name and any mod
 * reading boss bars still reads English.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
	@Redirect(
		method = "extractRenderState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"
		),
		require = 0
	)
	private void skyzh$translateBossBarName(
		GuiGraphicsExtractor graphics, Font font, Component name, int x, int y, int color
	) {
		if (!HypixelServer.canTranslate()) {
			graphics.text(font, name, x, y, color);
			return;
		}

		Component translated = Translator.translateLine(name, Surface.BOSS_BAR);
		graphics.text(font, translated, TextLayout.centeredX(font, translated, graphics.guiWidth()), y, color);
	}
}
