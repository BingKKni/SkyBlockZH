package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

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
 * <p>The HUD fields behind these — {@code overlayMessageString}, {@code title}, {@code subtitle},
 * {@code lastToolHighlight} — are all left alone.
 *
 * <p><b>Why this is not in the mixin.</b> The class that draws the HUD is {@code Gui} up to 26.1.2 and
 * {@code Hud} from 26.2 on, so the {@code @Mixin} that attaches to it has to be written twice, once
 * per target. What it does when it fires does not differ at all, and duplicating <em>that</em> would
 * mean two copies of the centring argument above, drifting apart the first time one of them is
 * corrected. So the per-version files under {@code mixin/} hold the annotations and one delegating
 * line each, and everything worth reading is here.
 */
public final class HudText {
	private HudText() {
	}

	/**
	 * The action bar. {@code translateWidgets} rather than {@code translateLine} because SkyBlock's
	 * action bar is several readouts on one line — health, mana, defence — each of which is its own
	 * record.
	 */
	public static void actionBar(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, text, Surface.ACTION_BAR, x, y, width, color);
	}

	/** The big centred title and the subtitle under it — both calls in vanilla's title method. */
	public static void title(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, text, Surface.MISC, x, y, width, color);
	}

	/** The item name that appears above the hotbar on selecting a slot. */
	public static void selectedItemName(
		GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color
	) {
		draw(graphics, font, text, Surface.ITEM, x, y, width, color);
	}

	private static void draw(
		GuiGraphicsExtractor graphics, Font font, Component text, Surface surface, int x, int y, int width, int color
	) {
		if (!HypixelServer.canTranslate()) {
			graphics.textWithBackdrop(font, text, x, y, width, color);
			return;
		}

		Component translated = surface == Surface.ACTION_BAR
			? Translator.translateWidgets(text, surface) : Translator.translateLine(text, surface);
		int translatedWidth = font.width(translated);
		// x was left-of-centre by half the old width; keep the same midpoint for the new one.
		graphics.textWithBackdrop(font, translated, x + (width - translatedWidth) / 2, y, translatedWidth, color);
	}
}
