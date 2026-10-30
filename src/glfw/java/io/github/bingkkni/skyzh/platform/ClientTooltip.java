package io.github.bingkkni.skyzh.platform;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.Identifier;

/** Tooltip API shared by 26.1 and 26.2. */
public final class ClientTooltip {
	private ClientTooltip() {}
	public static void draw(GuiGraphicsExtractor graphics, Font font, List<ClientTooltipComponent> lines,
		int x, int y, Identifier style) {
		graphics.tooltip(font, lines, x, y, DefaultTooltipPositioner.INSTANCE, style);
	}
}
