package io.github.bingkkni.skyzh.gui;

import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/** The complete settings screen shared by Mod Menu, commands and first-run guidance. */
public class SkyZHConfigScreen extends Screen {
	private static final int SMALL_WIDTH = 150;
	private static final int HEIGHT = 20;
	private static final int SPACING = 6;

	private static final URI GITHUB_URI = URI.create("https://github.com/BingKKni/SkyBlockZH");

	/** Consumed on the next tick, after ChatScreen has closed itself. */
	private static boolean commandScreenRequested;
	private static LocalPlayer commandScreenPlayer;

	private final Screen parent;
	private final SkyZHConfig config = SkyZHConfig.get();
	private final boolean captureInitiallyEnabled = this.config.captureUntranslated;
	private final List<DependentToggle> dependentToggles = new ArrayList<>();

	public SkyZHConfigScreen(Screen parent) {
		super(Component.translatable("skyzh.options.title"));
		this.parent = parent;
	}

	/**
	 * Queues the settings screen for the next client tick.
	 *
	 * <p>{@code ChatScreen} closes itself after {@code sendCommand} returns. Opening a screen from the
	 * command injection therefore loses to that close in the same call stack; consuming this request
	 * from the tick hook puts the settings screen up after the chat screen is gone.
	 */
	public static void openFromCommand() {
		Minecraft minecraft = Minecraft.getInstance();
		commandScreenPlayer = minecraft == null ? null : minecraft.player;
		commandScreenRequested = true;
	}

	/** Runs once per client tick after the command input path has completed. */
	public static void tick(Minecraft minecraft) {
		if (commandScreenRequested) {
			LocalPlayer requestedPlayer = commandScreenPlayer;
			clearCommandScreenRequest();
			if (requestedPlayer != null && minecraft.player == requestedPlayer && minecraft.level != null) {
				ClientGui.setScreen(minecraft, new SkyZHConfigScreen(null));
				return;
			}
		}

		maybeShowFirstRun(minecraft);
	}

	/** Opens the settings screen only after an in-world Hypixel connection is verified. */
	public static void maybeShowFirstRun(Minecraft minecraft) {
		SkyZHConfig config = SkyZHConfig.get();
		if (config.welcomeShown || minecraft.player == null || minecraft.level == null
			|| !HypixelServer.isHypixel() || ClientGui.screen(minecraft) != null) {
			return;
		}

		// Record it before opening the modal so reconnects and an Esc close do not repeat the first-run prompt.
		config.welcomeShown = true;
		config.save();
		ClientGui.setScreen(minecraft, new SkyZHConfigScreen(null));
	}

	@Override
	protected void init() {
		this.dependentToggles.clear();
		int buttonWidth = Math.min(SMALL_WIDTH, Math.max(1, (this.width - 44 - SPACING) / 2));
		int contentWidth = buttonWidth * 2 + SPACING;
		LinearLayout content = LinearLayout.vertical().spacing(SPACING);
		content.defaultCellSetting().alignHorizontallyCenter();
		content.addChild(centeredText(Component.translatable("skyzh.welcome.title"), contentWidth));
		MultiLineTextWidget issues = centeredText(issueText(), contentWidth);
		issues.active = true;
		issues.setComponentClickHandler(style -> defaultHandleClickEvent(style.getClickEvent(), this.minecraft, this));
		content.addChild(issues);
		content.addChild(centeredText(Component.translatable("skyzh.welcome.choose"), contentWidth));
		GridLayout grid = content.addChild(new GridLayout().spacing(SPACING));
		grid.defaultCellSetting().alignHorizontallyCenter();

		grid.addChild(centeredText(Component.translatable("skyzh.options.general"), contentWidth), 0, 0, 1, 2);
		grid.addChild(toggle("enabled", buttonWidth, this.config.enabled, value -> {
			this.config.enabled = value;
			updateDependentButtons();
		}), 1, 0);
		grid.addChild(
			dependentToggle("updateCheck", buttonWidth, this.config.updateCheck, value -> this.config.updateCheck = value),
			1, 1
		);
		grid.addChild(
			dependentToggle("translateSkyBlockName", buttonWidth, this.config.translateSkyBlockName,
				value -> this.config.translateSkyBlockName = value),
			2, 0
		);
		grid.addChild(
			dependentToggle("originalTips", buttonWidth, this.config.originalTips, value -> this.config.originalTips = value),
			2, 1
		);
		CycleButton<DisplayPriority> priority = CycleButton.<DisplayPriority>builder(
			value -> Component.translatable("skyzh.priority." + value.key()), this.config.displayPriority)
			.withValues(DisplayPriority.values())
			.withTooltip(value -> Tooltip.create(Component.translatable("skyzh.option.displayPriority.tooltip")))
			.create(0, 0, buttonWidth, HEIGHT, Component.translatable("skyzh.option.displayPriority"), (button, value) -> {
				this.config.displayPriority = value;
				SkyZHConfig.bumpGeneration();
				button.setMessage(priorityLabel(value));
			});
		priority.setMessage(priorityLabel(this.config.displayPriority));
		this.dependentToggles.add(new DependentToggle(priority, "displayPriority"));
		grid.addChild(priority, 3, 0);
		CycleButton<Boolean> itemNames = toggle("translateItemNames", buttonWidth, true, value -> {});
		itemNames.active = false;
		grid.addChild(itemNames, 3, 1);
		grid.addChild(centeredText(Component.translatable("skyzh.options.developer"), contentWidth), 4, 0, 1, 2);
		// Capture writes files rather than changing rendered text, so it retains a full row of its own.
		grid.addChild(
			dependentToggle("captureUntranslated", contentWidth, this.config.captureUntranslated,
				value -> this.config.captureUntranslated = value),
			5, 0, 1, 2
		);
		grid.addChild(
			dependentToggle("captureNotifications", buttonWidth, this.config.captureNotifications, value -> {
				this.config.captureNotifications = value;
				CaptureAnnouncer.clear();
			}),
			6, 0
		);
		grid.addChild(
			dependentToggle("autoClearCapture", buttonWidth, this.config.autoClearCapture,
				value -> this.config.autoClearCapture = value),
			6, 1
		);

		// Keep all settings reachable when the welcome text wraps or the window is short.
		ScrollableLayout scroll = new ScrollableLayout(this.minecraft, content, Math.max(1, this.height - 78));
		scroll.arrangeElements();
		FrameLayout.centerInRectangle(scroll, 0, 38, this.width, scroll.getHeight());
		scroll.visitWidgets(this::addRenderableWidget);
		updateDependentButtons();

		addRenderableWidget(
			Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds((this.width - buttonWidth) / 2, this.height - 28, buttonWidth, HEIGHT)
				.build()
		);
	}

	private MultiLineTextWidget centeredText(Component text, int width) {
		return new MultiLineTextWidget(text, this.font).setMaxWidth(width).setCentered(true);
	}

	private Component issueText() {
		return Component.translatable("skyzh.welcome.issue.before")
			.append(Component.literal("[Github]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(GITHUB_URI))
			))
			.append(Component.translatable("skyzh.welcome.issue.after"));
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

	private static Component priorityLabel(DisplayPriority value) {
		return Component.translatable("skyzh.option.displayPriority").append(": ")
			.append(Component.translatable("skyzh.priority." + value.key()));
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

	private record DependentToggle(AbstractWidget button, String key) {
	}

	static boolean commandScreenRequested() {
		return commandScreenRequested;
	}

	static void clearCommandScreenRequest() {
		commandScreenRequested = false;
		commandScreenPlayer = null;
	}
}
