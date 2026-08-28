package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The three pieces of text the HUD draws with a backdrop: the action bar, the title/subtitle pair,
 * and the name of the item just selected.
 *
 * <p>All three are centred by vanilla against the width of the English, and all three are drawn
 * through {@code textWithBackdrop(font, text, x, y, width, colour)}, where {@code width} is the text
 * width the backdrop box is sized from. Translating without touching those two numbers would leave
 * the Chinese off-centre inside a box cut for different text, so both are recomputed: the box fits
 * the Chinese and the midpoint stays exactly where it was.
 *
 * <p>The {@code Hud} fields behind these — {@code overlayMessageString}, {@code title},
 * {@code subtitle}, {@code lastToolHighlight} — are all left alone.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	private static final String TEXT_WITH_BACKDROP =
		"Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V";

	@Redirect(method = "extractOverlayMessage", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateActionBar(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, Translator.translateWidgets(text, Surface.ACTION_BAR), x, y, width, color);
	}

	/** Both calls in this method — the title and the subtitle under it. */
	@Redirect(method = "extractTitle", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateTitle(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, Translator.translateLine(text, Surface.MISC), x, y, width, color);
	}

	@Redirect(method = "extractSelectedItemName", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
	private void skyzh$translateSelectedItemName(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, Translator.translateLine(text, Surface.ITEM), x, y, width, color);
	}

	private static void draw(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		int translatedWidth = font.width(text);
		// x was left-of-centre by half the old width; keep the same midpoint for the new one.
		graphics.textWithBackdrop(font, text, x + (width - translatedWidth) / 2, y, translatedWidth, color);
	}
}
