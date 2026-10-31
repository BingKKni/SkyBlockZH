package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.SkyZHConfig;
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

/** High yields to custom panels; Force replaces supported panels through vanilla submission hooks. */
public final class ForceSidebar {
	private static boolean filtering;
	private static final StackWalker CALLERS = StackWalker.getInstance(java.util.Set.of(StackWalker.Option.DROP_METHOD_INFO));
	private static final String[] CUSTOM_RENDERERS = {
		"me.owdding.customscoreboard.core.CustomScoreboardRenderer",
		"at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard",
		"com.github.noamm9.features.impl.visual.Scoreboard"
	};

	private ForceSidebar() {}
	private record Row(Component name, Component score, int scoreWidth) {}

	public static boolean ownsDisplay() {
		Minecraft mc = Minecraft.getInstance();
		return ownsDisplay(ForceChat.ownsDisplay() && mc.level != null && ClientGui.hudVisible(mc)
			&& (ClientGui.screen(mc) == null || ClientGui.screen(mc) instanceof ChatScreen), SidebarCompatibility::hasCustomSidebar);
	}

	private static boolean ownsDisplay(boolean eligible, java.util.function.BooleanSupplier customSidebar) {
		DisplayPriority priority = SkyZHConfig.get().displayPriority;
		return eligible && (priority == DisplayPriority.FORCE || priority == DisplayPriority.HIGH && !customSidebar.getAsBoolean());
	}

	/** Scope filtering to the actual frame that will receive a replacement, even on exceptions. */
	public static void withFrame(boolean strict, Runnable extract, Runnable replacement) {
		boolean previous = filtering;
		filtering = strict;
		try {
			extract.run();
			filtering = false;
			replacement.run();
		} finally { filtering = previous; }
	}

	public static boolean suppressCustomDraw() {
		return filtering && customRendererOnStack();
	}

	/** Hidden custom widgets must not keep their click actions while the replacement is shown. */
	public static boolean suppressCustomClick() {
		return SkyZHConfig.get().displayPriority == DisplayPriority.FORCE && ownsDisplay() && customRendererOnStack();
	}

	private static boolean customRendererOnStack() {
		// No third-party class loading or bytecode changes. Only the three verified renderer
		// owners (including their nested callbacks) are classified; other HUDs remain untouched.
		return CALLERS.walk(frames -> frames.limit(64).anyMatch(frame -> isCustomRenderer(frame.getClassName())));
	}

	static boolean isCustomRenderer(String name) {
		for (String owner : CUSTOM_RENDERERS) {
			if (name.startsWith(owner) && (name.length() == owner.length() || name.charAt(owner.length()) == '$')) return true;
		}
		return false;
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
		// Match vanilla's accessibility setting instead of forcing an opaque black panel.
		graphics.fill(left - 2, top - 10, right, top - 1, minecraft.options.getBackgroundColor(0.4F));
		graphics.fill(left - 2, top - 1, right, bottom, minecraft.options.getBackgroundColor(0.3F));
		graphics.text(font, PriorityText.protect(title), left + width / 2 - titleWidth / 2, top - 9, -1, false);
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int y = top + i * 9;
			graphics.text(font, PriorityText.protect(row.name()), left, y, -1, false);
			graphics.text(font, PriorityText.protect(row.score()), right - row.scoreWidth(), y, -1, false);
		}
	}
}
