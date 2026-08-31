package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.SidebarText;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Where {@link SidebarText} attaches on 26.1.x: the sidebar is drawn by {@code Gui}, which 26.2 split
 * into a {@code Gui} that owns screens and a {@code Hud} that draws. The 26.2 copy of this file is
 * these same two redirects against {@code Hud}.
 *
 * <p>Read {@link SidebarText} for why both hooks are on the source of the text rather than on the
 * call that draws it.
 */
@Mixin(Gui.class)
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
		return SidebarText.title(objective);
	}

	/**
	 * The lambda that builds one row.
	 *
	 * <p>Targeting a lambda by its generated name is the fragile part of this class, which is why it
	 * is {@code require = 0} like the rest: if a future Minecraft renumbers it, sidebar rows stay
	 * English and nothing else changes. It is {@code $1} in both 26.1.x and 26.2 — checked against the
	 * real jars, not assumed, since nothing about a wrong guess here would show up at build time.
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
		return SidebarText.row(team, name);
	}
}
