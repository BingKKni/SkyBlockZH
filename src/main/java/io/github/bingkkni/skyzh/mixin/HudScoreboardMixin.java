package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The sidebar — the scoreboard down the right of the screen.
 *
 * <p>Both hooks sit on the <em>source</em> of the text rather than on the call that draws it, which
 * matters here more than anywhere else in the mod. {@code displayScoreboardSidebar} measures the
 * title and every line to decide how wide the panel is, where its left edge falls and where the
 * centred title starts, and it does all of that <em>after</em> reading them. Translating at the
 * source means those measurements are taken of the Chinese; translating at the draw call would size
 * the panel for English and leave the Chinese sitting in a box cut for other text.
 *
 * <p>Nothing is written back: the {@code Objective} keeps its display name and the {@code Scoreboard}
 * its entries, so a mod reading the sidebar to work out where the player is still reads English.
 *
 * <p>The sidebar is also the one surface in the game that genuinely animates. Hypixel re-sends the
 * objective's display name every tick with the highlight one letter further along, which is the
 * shimmer over SKYBLOCK. That survives because nothing on this path caches a rendered line — the
 * lookup cache remembers only <em>which record</em> matches a piece of text, and the text is what
 * stays the same from tick to tick while the colours move. Re-colouring happens fresh every frame.
 */
@Mixin(Hud.class)
public abstract class HudScoreboardMixin {
	@Redirect(
		method = "displayScoreboardSidebar",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"
		),
		require = 0
	)
	private Component skyzh$translateSidebarTitle(Objective objective) {
		return Translator.translateLine(objective.getDisplayName(), Surface.SCOREBOARD);
	}

	/**
	 * The lambda that builds one row. Each row's text is assembled here from its team's prefix and
	 * suffix — Hypixel splits a line across those two halves arbitrarily, so this is the first point
	 * at which a whole line exists to be matched against.
	 *
	 * <p>Targeting a lambda by its generated name is the fragile part of this class, which is why it
	 * is {@code require = 0} like the rest: if a future Minecraft renumbers it, sidebar rows stay
	 * English and nothing else changes.
	 */
	@Redirect(
		method = "lambda$displayScoreboardSidebar$1",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"
		),
		require = 0
	)
	private MutableComponent skyzh$translateSidebarLine(Team team, Component name) {
		return Translator.translate(PlayerTeam.formatNameForTeam(team, name), Surface.SCOREBOARD).padded();
	}
}
