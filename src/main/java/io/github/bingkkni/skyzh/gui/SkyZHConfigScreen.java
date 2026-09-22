package io.github.bingkkni.skyzh.gui;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/** The complete settings screen reached through Mod Menu. */
public class SkyZHConfigScreen extends Screen {
	private static final int SMALL_WIDTH = 150;
	private static final int WIDE_WIDTH = 308;
	private static final int HEIGHT = 20;
	private static final int SPACING = 8;

	private final Screen parent;
	private final SkyZHConfig config = SkyZHConfig.get();
	private final boolean captureInitiallyEnabled = this.config.captureUntranslated;
	private final List<DependentToggle> dependentToggles = new ArrayList<>();

	public SkyZHConfigScreen(Screen parent) {
		super(Component.translatable("skyzh.options.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.dependentToggles.clear();
		GridLayout grid = new GridLayout().spacing(SPACING);

		grid.addChild(toggle("enabled", SMALL_WIDTH, this.config.enabled, value -> {
			this.config.enabled = value;
			updateDependentButtons();
		}), 0, 0);
		grid.addChild(
			dependentToggle("updateCheck", SMALL_WIDTH, this.config.updateCheck, value -> this.config.updateCheck = value),
			0, 1
		);
		grid.addChild(
			dependentToggle("translateSkyBlockName", SMALL_WIDTH, this.config.translateSkyBlockName,
				value -> this.config.translateSkyBlockName = value),
			1, 0
		);
		grid.addChild(
			dependentToggle("originalTips", SMALL_WIDTH, this.config.originalTips, value -> this.config.originalTips = value),
			1, 1
		);
		// Capture writes files rather than changing rendered text, so it retains a full row of its own.
		grid.addChild(
			dependentToggle("captureUntranslated", WIDE_WIDTH, this.config.captureUntranslated,
				value -> this.config.captureUntranslated = value),
			2, 0, 1, 2
		);
		grid.addChild(
			dependentToggle("captureNotifications", SMALL_WIDTH, this.config.captureNotifications, value -> {
				this.config.captureNotifications = value;
				CaptureAnnouncer.clear();
			}),
			3, 0
		);
		grid.addChild(
			dependentToggle("autoClearCapture", SMALL_WIDTH, this.config.autoClearCapture,
				value -> this.config.autoClearCapture = value),
			3, 1
		);

		grid.arrangeElements();
		FrameLayout.centerInRectangle(grid, 0, 0, this.width, this.height);
		grid.visitWidgets(this::addRenderableWidget);
		updateDependentButtons();

		addRenderableWidget(
			Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds((this.width - SMALL_WIDTH) / 2, grid.getY() + grid.getHeight() + SPACING * 2, SMALL_WIDTH, HEIGHT)
				.build()
		);
	}

	private CycleButton<Boolean> dependentToggle(String key, int width, boolean initial, Consumer<Boolean> sink) {
		CycleButton<Boolean> button = toggle(key, width, initial, sink);
		this.dependentToggles.add(new DependentToggle(button, key));
		return button;
	}

	private CycleButton<Boolean> toggle(String key, int width, boolean initial, Consumer<Boolean> sink) {
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

	private void updateDependentButtons() {
		for (DependentToggle dependent : this.dependentToggles) {
			dependent.button().active = this.config.enabled;
			dependent.button().setTooltip(Tooltip.create(Component.translatable(
				this.config.enabled ? "skyzh.option." + dependent.key() + ".tooltip" : "skyzh.option.requiresEnabled"
			)));
		}
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
		boolean flushCapture = this.captureInitiallyEnabled && !this.config.captureUntranslated;
		this.config.save();
		if (flushCapture) {
			TextCapture.flush();
		}
		ClientGui.setScreen(this.minecraft, this.parent);
	}

	private record DependentToggle(CycleButton<Boolean> button, String key) {
	}
}
