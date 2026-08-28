package io.github.bingkkni.skyzh.gui;

import io.github.bingkkni.skyzh.SkyZHConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/**
 * The settings screen, reached through Mod Menu.
 *
 * <p>Laid out on an explicit {@link GridLayout} rather than the options list vanilla screens use,
 * because the rows are what carry the meaning here: the master switch owns the top row because
 * nothing under it does anything while it is off, the two switches that only change how an already
 * translated line reads share the row beneath it, and the capture switch owns the bottom row on its
 * own. An options list packs widgets in the order they were handed to it and would break that
 * grouping the moment an option is added, so the grid is addressed by coordinate instead. The two
 * half-width cells add up to the full-width one, spacing included, so every row ends flush.
 *
 * <p>Mod Menu is a soft dependency and this class is only ever reached through it. Everything here
 * also exists in {@code config/skyzh.json}, which is the supported way to change these settings
 * without it.
 */
public class SkyZHConfigScreen extends Screen {
	private static final int SMALL_WIDTH = 150;
	private static final int WIDE_WIDTH = 308;
	private static final int HEIGHT = 20;
	private static final int SPACING = 8;

	private final Screen parent;
	private final SkyZHConfig config = SkyZHConfig.get();

	public SkyZHConfigScreen(Screen parent) {
		super(Component.translatable("skyzh.options.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		GridLayout grid = new GridLayout().spacing(SPACING);

		grid.addChild(toggle("enabled", WIDE_WIDTH, this.config.enabled, value -> this.config.enabled = value), 0, 0, 1, 2);
		grid.addChild(
			toggle("translateSkyBlockName", SMALL_WIDTH, this.config.translateSkyBlockName,
				value -> this.config.translateSkyBlockName = value),
			1, 0
		);
		grid.addChild(
			toggle("showOriginal", SMALL_WIDTH, this.config.showOriginal, value -> this.config.showOriginal = value),
			1, 1
		);
		// A row to itself, under the three that change what a player sees, because it changes nothing
		// a player sees: it is the switch that writes files to their disk, and it belongs where nobody
		// flips it by accident while looking for the translation settings.
		grid.addChild(
			toggle("captureUntranslated", WIDE_WIDTH, this.config.captureUntranslated,
				value -> this.config.captureUntranslated = value),
			2, 0, 1, 2
		);

		grid.arrangeElements();
		FrameLayout.centerInRectangle(grid, 0, 0, this.width, this.height);
		grid.visitWidgets(this::addRenderableWidget);

		addRenderableWidget(
			Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds((this.width - SMALL_WIDTH) / 2, grid.getY() + grid.getHeight() + SPACING * 2, SMALL_WIDTH, HEIGHT)
				.build()
		);
	}

	private CycleButton<Boolean> toggle(String key, int width, boolean initial, java.util.function.Consumer<Boolean> sink) {
		Component name = Component.translatable("skyzh.option." + key);
		CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
			.withTooltip(value -> Tooltip.create(Component.translatable("skyzh.option." + key + ".tooltip")))
			.create(0, 0, width, HEIGHT, name, (changed, value) -> {
				sink.accept(value);
				changed.setMessage(toggleLabel(name, value));
			});
		button.setMessage(toggleLabel(name, initial));
		return button;
	}

	private static Component toggleLabel(Component name, boolean value) {
		return name.copy().append(": ").append(Component.translatable(value ? "options.on" : "options.off"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 20, ARGB.white(1.0f));
	}

	@Override
	public void onClose() {
		// Writing on close rather than on every click: the file is the source of truth for players
		// without Mod Menu, and one save per visit keeps it from being rewritten mid-decision.
		this.config.save();
		this.minecraft.gui.setScreen(this.parent);
	}
}
