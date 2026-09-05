package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chest and menu titles: translated, optionally labelled with the English, and re-centred.
 *
 * <p>Known issue 1 in its most tractable form. SkyBlock centres a container title by padding it with
 * spaces until it looks middled at the width Minecraft gives the title area — a trick that is
 * measured in English pixels and falls apart the moment the text becomes Chinese, leaving the line
 * hanging to the left with a gap down the right. Here the padding is simply thrown away and the draw
 * position recomputed from the real width of the real text, which is exact to the pixel because this
 * is one of the surfaces where the mod chooses the coordinate rather than a string.
 *
 * <p>A title the server did <em>not</em> pad is left where vanilla puts it. Padding is the evidence
 * that centring was intended; a menu Hypixel deliberately left flush against the left margin, and
 * every ordinary vanilla container, should not be moved.
 *
 * <p>Only one container is open at a time and its title does not change while it is open, so the
 * finished line is memoised against the input rather than rebuilt every frame.
 */
public final class ContainerTitle {
	/**
	 * @param text     the line to draw
	 * @param centered whether the server was centring this title and the caller should recompute x
	 */
	public record Rendered(Component text, boolean centered) {}

	private static String memoKey;
	private static Rendered memo;

	private ContainerTitle() {
	}

	public static Rendered of(Font font, Component title, int available) {
		if (!HypixelServer.canTranslate()) {
			return new Rendered(title, false);
		}

		SkyZHConfig config = SkyZHConfig.get();
		String key = title.getString() + ' ' + available + ' ' + SkyZHConfig.generation();

		synchronized (ContainerTitle.class) {
			if (key.equals(memoKey)) {
				return memo;
			}
		}

		Translator.Result result = Translator.translate(title, Surface.GUI_TITLE);
		Rendered rendered;

		if (!result.matched()) {
			rendered = new Rendered(result.padded(), false);
		} else {
			MutableComponent line = result.core();

			if (config.showOriginal) {
				line = OriginalLabel.fit(font, line, title, available);
			}

			rendered = new Rendered(line, result.centredByServer());
		}

		synchronized (ContainerTitle.class) {
			memoKey = key;
			memo = rendered;
		}

		return rendered;
	}
}
