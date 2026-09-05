package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player list behind the Tab key.
 *
 * <p>SkyBlock does not use it for players. The rows are fake entries whose "names" are the readout —
 * {@code Commissions:}, {@code Mining Speed: 450}, a bar of powder counts — which is why the corpus
 * keeps them in {@code TabList/} and not in {@code ScoreBoard/}: they are a different surface with a
 * different line shape, and the label text is only part of a row.
 *
 * <p>Both hooks sit on the <em>source</em> of the text rather than on the call that draws it, for the
 * same reason {@link HudScoreboardMixin} does. {@code extractRenderState} walks every row once to
 * find the widest, and that measurement decides how wide each column is and where the list is
 * centred on screen; the same call's result is what ends up being drawn. Translating there means the
 * columns are cut for the Chinese. Translating at the draw call would size them for English and drop
 * the Chinese into boxes made for other text.
 *
 * <p>Nothing is written back. {@code PlayerInfo} keeps its display name and the header and footer
 * fields keep theirs, so a mod reading the tab list to work out where the player is — which is how
 * most of them detect the current SkyBlock area — still reads English.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	/**
	 * One row. The redirected call is the only place a row's text is produced: its result is measured
	 * for the column width and then stored on the entry that gets drawn, so a single hook keeps the
	 * two in step.
	 */
	@Redirect(
		method = "extractRenderState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"
		),
		require = 0
	)
	private Component skyzh$translateRow(PlayerTabOverlay overlay, PlayerInfo info) {
		Component source = overlay.getNameForDisplay(info);
		return HypixelServer.canTranslate() ? Translator.translateRow(source, Surface.TABLIST) : source;
	}

	/**
	 * The header above the list and the footer below it, both of which arrive as one component with
	 * newlines in it and are split here before being centred. Translating in front of the split lets
	 * vanilla re-break and re-centre the Chinese itself.
	 */
	@Redirect(
		method = "extractRenderState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"
		),
		require = 0
	)
	private List<FormattedCharSequence> skyzh$translateHeaderAndFooter(Font font, FormattedText text, int width) {
		if (HypixelServer.canTranslate() && text instanceof Component component) {
			return font.split(Translator.translateBlock(component, Surface.TABLIST), width);
		}

		return font.split(text, width);
	}
}
