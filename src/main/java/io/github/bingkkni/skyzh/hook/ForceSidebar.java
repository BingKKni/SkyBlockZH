package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.platform.ClientGui;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.scores.DisplaySlot;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;

/** Server-owned sidebar in Force mode, independent of cancellable vanilla sidebar render hooks. */
public final class ForceSidebar {
	private ForceSidebar() {}
	private record Row(Component name, Component score, int scoreWidth) {}

	public static boolean ownsDisplay() {
		Minecraft mc = Minecraft.getInstance();
		return ForceChat.ownsDisplay() && mc.level != null && ClientGui.hudVisible(mc)
			&& (ClientGui.screen(mc) == null || ClientGui.screen(mc) instanceof ChatScreen);
	}

	public static void renderFinal(GuiGraphicsExtractor graphics) {
		if (!ownsDisplay()) return;
		Objective objective = Minecraft.getInstance().level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		if (!HypixelServer.isSkyBlockObjective(objective)) return;
		graphics.nextStratum();
		render(graphics, objective);
	}

	private static void render(GuiGraphicsExtractor graphics, Objective objective) {
		Minecraft minecraft = Minecraft.getInstance();
		var font = minecraft.font;
		var board = objective.getScoreboard();
		var format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		var rows = board.listPlayerScores(objective).stream().filter(entry -> !entry.isHidden())
			.sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed()
				.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER)).limit(15)
			.map(entry -> {
				Component name = SidebarText.row(board.getPlayersTeam(entry.owner()), entry.ownerName());
				Component score = entry.formatValue(format);
				return new Row(name, score, font.width(score));
			}).toList();
		Component title = SidebarText.title(objective);
		int titleWidth = font.width(title);
		int width = titleWidth;
		for (Row row : rows) width = Math.max(width, font.width(row.name())
			+ (row.scoreWidth() > 0 ? font.width(": ") + row.scoreWidth() : 0));
		int bottom = graphics.guiHeight() / 2 + rows.size() * 9 / 3;
		int top = bottom - rows.size() * 9;
		int left = graphics.guiWidth() - width - 3;
		int right = graphics.guiWidth() - 1;
		// An opaque final panel keeps independently drawn HUD text from bleeding through.
		graphics.fill(left - 2, top - 10, right, bottom, 0xFF181818);
		graphics.text(font, PriorityText.protect(title), left + width / 2 - titleWidth / 2, top - 9, -1, false);
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int y = top + i * 9;
			graphics.text(font, PriorityText.protect(row.name()), left, y, -1, false);
			graphics.text(font, PriorityText.protect(row.score()), right - row.scoreWidth(), y, -1, false);
		}
	}
}
