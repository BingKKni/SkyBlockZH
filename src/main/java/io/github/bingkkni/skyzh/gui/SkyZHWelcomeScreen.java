package io.github.bingkkni.skyzh.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

/** The first-run choices shown after the player has entered Hypixel for the first time. */
public final class SkyZHWelcomeScreen extends Screen {
	private static final URI GITHUB_URI = URI.create("https://github.com/BingKKni/SkyBlockZH");
	private static final int SMALL_WIDTH = 150;
	private static final int HEIGHT = 20;
	private static final int SPACING = 8;
	private static final int TEXT_WIDTH = 500;

	/** Set from command dispatch and consumed at the next client tick, after ChatScreen has closed itself. */
	private static boolean commandScreenRequested;
	private static LocalPlayer commandScreenPlayer;

	private final Screen parent;
	private final boolean showWelcomeTitle;
	private final SkyZHConfig config = SkyZHConfig.get();
	private final List<DependentToggle> dependentToggles = new ArrayList<>();
	private List<FormattedCharSequence> issueLines = List.of();
	private List<ClickRegion> githubRegions = List.of();
	private int issueTop;
	private CycleButton<Boolean> helpImproveButton;

	private SkyZHWelcomeScreen(Screen parent, boolean showWelcomeTitle) {
		super(Component.translatable("skyzh.options.title"));
		this.parent = parent;
		this.showWelcomeTitle = showWelcomeTitle;
	}

	/**
	 * Queues the compact settings view for the next client tick.
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
				ClientGui.setScreen(minecraft, new SkyZHWelcomeScreen(null, false));
				return;
			}
		}

		maybeShowFirstRun(minecraft);
	}

	/** Opens the welcome view only after an in-world Hypixel connection is verified. */
	public static void maybeShowFirstRun(Minecraft minecraft) {
		SkyZHConfig config = SkyZHConfig.get();
		if (config.welcomeShown || minecraft.player == null || minecraft.level == null
			|| !HypixelServer.isHypixel() || ClientGui.screen(minecraft) != null) {
			return;
		}

		// Record it before opening the modal so reconnects and an Esc close do not repeat the first-run prompt.
		config.welcomeShown = true;
		config.save();
		ClientGui.setScreen(minecraft, new SkyZHWelcomeScreen(null, true));
	}

	@Override
	protected void init() {
		this.dependentToggles.clear();
		int buttonWidth = Math.min(SMALL_WIDTH, Math.max(110, (this.width - 24 - SPACING) / 2));
		int baseTop = Math.max(30, this.height / 4 - 20);
		int titleTop = this.showWelcomeTitle ? baseTop : Math.max(30, baseTop - 18);
		this.issueTop = titleTop + (this.showWelcomeTitle ? 22 : 0);
		int issueWidth = Math.min(TEXT_WIDTH, this.width - 24);
		this.issueLines = this.font.split(issueText(), issueWidth);
		this.githubRegions = githubRegions();

		int selectionTop = this.issueTop + this.issueLines.size() * 9 + 14;
		GridLayout grid = new GridLayout().spacing(SPACING);
		grid.addChild(
			dependentToggle("updateCheck", buttonWidth, this.config.updateCheck, value -> this.config.updateCheck = value),
			0, 0
		);
		grid.addChild(
			dependentToggle("translateSkyBlockName", buttonWidth, this.config.translateSkyBlockName,
				value -> this.config.translateSkyBlockName = value),
			0, 1
		);
		grid.addChild(
			dependentToggle("originalTips", buttonWidth, this.config.originalTips, value -> this.config.originalTips = value),
			1, 0
		);
		this.helpImproveButton = toggle("helpImproveTranslation", buttonWidth, this.config.helpImproveTranslation,
			value -> this.config.helpImproveTranslation = value);
		grid.addChild(this.helpImproveButton, 1, 1);
		grid.arrangeElements();
		FrameLayout.centerInRectangle(grid, 0, selectionTop + 15, this.width, grid.getHeight());
		grid.visitWidgets(this::addRenderableWidget);
		refreshButtonState();

		addRenderableWidget(
			Button.builder(Component.translatable("skyzh.welcome.done"), button -> onClose())
				.tooltip(Tooltip.create(Component.translatable("skyzh.welcome.done.tooltip")))
				.bounds((this.width - buttonWidth) / 2, grid.getY() + grid.getHeight() + SPACING * 2, buttonWidth, HEIGHT)
				.build()
		);
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

	private void refreshButtonState() {
		for (DependentToggle dependent : this.dependentToggles) {
			dependent.button().active = this.config.enabled;
			dependent.button().setTooltip(Tooltip.create(Component.translatable(
				this.config.enabled ? "skyzh.option." + dependent.key() + ".tooltip" : "skyzh.option.requiresEnabled"
			)));
		}

		this.helpImproveButton.active = false;
		this.helpImproveButton.setTooltip(Tooltip.create(Component.translatable(
			this.config.enabled ? "skyzh.option.helpImproveTranslation.tooltip" : "skyzh.option.requiresEnabled"
		)));
	}

	private static Component toggleLabel(Component name, boolean value) {
		return name.copy().append(": ").append(Component.translatable(value ? "options.on" : "options.off"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int titleTop = this.issueTop - (this.showWelcomeTitle ? 22 : 0);
		if (this.showWelcomeTitle) {
			graphics.centeredText(this.font, Component.translatable("skyzh.welcome.title"), this.width / 2, titleTop, ARGB.white(1.0f));
		}

		int lineY = this.issueTop;
		for (FormattedCharSequence line : this.issueLines) {
			graphics.centeredText(this.font, line, this.width / 2, lineY, ARGB.white(1.0f));
			lineY += 9;
		}
		graphics.centeredText(
			this.font, Component.translatable("skyzh.welcome.choose"), this.width / 2, lineY + 5, ARGB.white(1.0f)
		);

		if (isGithubHit(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && isGithubHit(event.x(), event.y())) {
			clickUrlAction(this.minecraft, this, GITHUB_URI);
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private boolean isGithubHit(double mouseX, double mouseY) {
		for (ClickRegion region : this.githubRegions) {
			if (region.contains(mouseX, mouseY)) {
				return true;
			}
		}

		return false;
	}

	private List<ClickRegion> githubRegions() {
		List<ClickRegion> regions = new ArrayList<>();
		int lineY = this.issueTop;

		for (FormattedCharSequence line : this.issueLines) {
			int currentLineY = lineY;
			int[] cursor = {this.width / 2 - this.font.width(line) / 2};
			int[] linkStart = {-1};
			line.accept((index, style, codePoint) -> {
				if (isGithubStyle(style)) {
					if (linkStart[0] < 0) {
						linkStart[0] = cursor[0];
					}
				} else if (linkStart[0] >= 0) {
					regions.add(new ClickRegion(linkStart[0], currentLineY, cursor[0], currentLineY + 9));
					linkStart[0] = -1;
				}

				cursor[0] += glyphWidth(style, codePoint);
				return true;
			});
			if (linkStart[0] >= 0) {
				regions.add(new ClickRegion(linkStart[0], currentLineY, cursor[0], currentLineY + 9));
			}

			lineY += 9;
		}

		return List.copyOf(regions);
	}

	private int glyphWidth(Style style, int codePoint) {
		return this.font.width(Component.literal(new String(Character.toChars(codePoint))).setStyle(style));
	}

	private static boolean isGithubStyle(Style style) {
		return style.getClickEvent() instanceof ClickEvent.OpenUrl url && GITHUB_URI.equals(url.uri());
	}

	@Override
	public void onClose() {
		this.config.save();
		ClientGui.setScreen(this.minecraft, this.parent);
	}

	private record DependentToggle(CycleButton<Boolean> button, String key) {
	}

	private record ClickRegion(int left, int top, int right, int bottom) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.left && mouseX < this.right && mouseY >= this.top && mouseY < this.bottom;
		}
	}

	static boolean commandScreenRequested() {
		return commandScreenRequested;
	}

	static void clearCommandScreenRequest() {
		commandScreenRequested = false;
		commandScreenPlayer = null;
	}
}
