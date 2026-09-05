package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * The sidebar — the scoreboard down the right of the screen.
 *
 * <p>Both hooks sit on the <em>source</em> of the text rather than on the call that draws it, which
 * matters here more than anywhere else in the mod. The sidebar method measures the title and every
 * line to decide how wide the panel is, where its left edge falls and where the centred title starts,
 * and it does all of that <em>after</em> reading them. Translating at the source means those
 * measurements are taken of the Chinese; translating at the draw call would size the panel for English
 * and leave the Chinese sitting in a box cut for other text.
 *
 * <p>Nothing is written back: the {@code Objective} keeps its display name and the {@code Scoreboard}
 * its entries, so a mod reading the sidebar to work out where the player is still reads English.
 *
 * <p>The sidebar is also the one surface in the game that genuinely animates. Hypixel re-sends the
 * objective's display name every tick with the highlight one letter further along, which is the
 * shimmer over SKYBLOCK. That survives because nothing on this path caches a rendered line — the
 * lookup cache remembers only <em>which record</em> matches a piece of text, and the text is what
 * stays the same from tick to tick while the colours move. Re-colouring happens fresh every frame.
 *
 * <p><b>Why this is not in the mixin.</b> The sidebar lives on {@code Gui} up to 26.1.2 and on
 * {@code Hud} from 26.2 on, so the attachment is written once per target while this — the part with
 * the reasoning in it — is written once. See {@link HudText} for the same split.
 */
public final class SidebarText {
	private SidebarText() {
	}

	/** The heading, which on Hypixel is the animated SKYBLOCK banner. */
	public static Component title(Objective objective) {
		Component source = objective.getDisplayName();
		return HypixelServer.canTranslate() ? Translator.translateLine(source, Surface.SCOREBOARD) : source;
	}

	/**
	 * One row, assembled from its team's prefix and suffix.
	 *
	 * <p>Hypixel splits a line across those two halves arbitrarily, so the formatted name is the first
	 * point at which a whole line exists to be matched against.
	 */
	public static MutableComponent row(Team team, Component name) {
		MutableComponent source = PlayerTeam.formatNameForTeam(team, name);
		return HypixelServer.canTranslate() ? Translator.translate(source, Surface.SCOREBOARD).padded() : source;
	}
}
