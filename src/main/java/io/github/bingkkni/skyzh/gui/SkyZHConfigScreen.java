package io.github.bingkkni.skyzh.gui;

import io.github.bingkkni.skyzh.ClientPacks;
import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZH;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.SkyZHConfig.UpdateChannel;
import io.github.bingkkni.skyzh.SkyZHConfig.UpdateInstall;
import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The complete settings screen shared by Mod Menu, commands and first-run guidance.
 *
 * <p>Laid out the way MoulConfig lays out SkyHanni's: a title bar, categories down the left, a search
 * field at the top right, and one card per option with its description beside its control. Drawn by
 * hand rather than built from vanilla layouts, because a card is a description and a control side by
 * side, and vanilla has no widget for either a toggle switch or a dropdown.
 */
public class SkyZHConfigScreen extends Screen {
	private static final URI GITHUB_URI = URI.create("https://github.com/BingKKni/SkyBlockZH");

	private static final int PANEL = 0xF0181818;
	private static final int BAR = 0xFF101010;
	private static final int LINE = 0xFF3A3A3A;
	private static final int CARD = 0x14FFFFFF;
	private static final int CARD_HOVER = 0x24FFFFFF;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFAAAAAA;
	private static final int DISABLED = 0xFF666666;
	private static final int ACCENT = 0xFFFFAA00;
	private static final int ON = 0xFF55AA55;
	private static final int OFF = 0xFF4A4A4A;

	private static final int TITLE_HEIGHT = 22;
	private static final int HEADER_HEIGHT = 18;
	private static final int SIDEBAR = 96;
	private static final int PADDING = 6;
	private static final int CONTROL_HEIGHT = 14;

	/** Consumed on the next tick, after ChatScreen has closed itself. */
	private static boolean commandScreenRequested;
	private static LocalPlayer commandScreenPlayer;

	private final Screen parent;
	private final SkyZHConfig config = SkyZHConfig.get();
	private final boolean captureInitiallyEnabled = this.config.captureUntranslated;
	private final boolean legacyFontInitially = this.config.legacyFont;
	private final List<Category> categories = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();
	private Category selected;
	private EditBox search;
	private Choice<?> open;
	private int openX;
	private int openY;
	private double scroll;
	private int contentHeight;
	private int left;
	private int top;
	private int right;
	private int bottom;

	public SkyZHConfigScreen(Screen parent) {
		super(Component.literal("SkyZH " + version() + " Beta"));
		this.parent = parent;
		this.categories.add(new Category("general", List.of(
			new Info(),
			new Toggle("enabled", () -> this.config.enabled, value -> this.config.enabled = value),
			new Choice<>("updateCheck", List.of(Update.values()), value -> "skyzh.updateCheck." + value.key,
				() -> !this.config.updateCheck ? Update.OFF : this.config.updateChannel == UpdateChannel.BETA ? Update.BETA : Update.RELEASE,
				value -> {
					this.config.updateCheck = value != Update.OFF;
					if (value != Update.OFF) this.config.updateChannel = value == Update.BETA ? UpdateChannel.BETA : UpdateChannel.RELEASE;
				}),
			new Choice<>("updateInstall", List.of(UpdateInstall.values()), value -> "skyzh.updateInstall." + value.key(),
				() -> this.config.updateInstall, value -> this.config.updateInstall = value) {
				@Override boolean visible() { return SkyZHConfigScreen.this.config.updateCheck; }
			},
			new Toggle("originalTips", () -> this.config.originalTips, value -> this.config.originalTips = value)
		)));
		this.categories.add(new Category("translation", List.of(
			new Toggle("translateSkyBlockName", () -> this.config.translateSkyBlockName,
				value -> this.config.translateSkyBlockName = value),
			new Toggle("translateItemNames", () -> this.config.translateItemNames, value -> {
				this.config.translateItemNames = value;
				SkyZHConfig.bumpGeneration();
			}),
			new Toggle("translateMobNames", () -> this.config.translateMobNames, value -> {
				this.config.translateMobNames = value;
				SkyZHConfig.bumpGeneration();
			})
		)));
		this.categories.add(new Category("advanced", List.of(
			new Choice<>("legacyFont", List.of(true, false), value -> "skyzh.legacyFont." + (value ? "on" : "off"),
				() -> this.config.legacyFont, value -> this.config.legacyFont = value),
			new Group("displayPriority", List.of(
				new Choice<>("displayPriority", List.of(DisplayPriority.values()), value -> "skyzh.priority." + value.key(),
					() -> this.config.displayPriority, value -> {
						this.config.displayPriority = value;
						SkyZHConfig.bumpGeneration();
					})
			)),
			new Group("capture", List.of(
				new Toggle("captureUntranslated", () -> this.config.captureUntranslated,
					value -> this.config.captureUntranslated = value),
				new Toggle("captureNotifications", () -> this.config.captureNotifications, value -> {
					this.config.captureNotifications = value;
					CaptureAnnouncer.clear();
				}),
				new Toggle("autoClearCapture", () -> this.config.autoClearCapture, value -> this.config.autoClearCapture = value)
			))
		)));
		this.selected = this.categories.getFirst();
	}

	private static String version() {
		String version = FabricLoader.getInstance().getModContainer(SkyZH.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
		int metadata = version.indexOf('+');
		return metadata < 0 ? version : version.substring(0, metadata);
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
		int panelWidth = Math.min(this.width - 20, 520);
		int panelHeight = Math.min(this.height - 44, 320);
		this.left = (this.width - panelWidth) / 2;
		this.top = Math.max(8, (this.height - 32 - panelHeight) / 2);
		this.right = this.left + panelWidth;
		this.bottom = this.top + panelHeight;
		this.open = null;

		String query = this.search == null ? "" : this.search.getValue();
		int searchWidth = Math.min(140, panelWidth - SIDEBAR - 40);
		this.search = new EditBox(this.font, this.right - PADDING - searchWidth, this.top + TITLE_HEIGHT + 4,
			searchWidth, 12, Component.translatable("skyzh.search"));
		this.search.setBordered(false);
		this.search.setMaxLength(48);
		this.search.setHint(Component.translatable("skyzh.search").withStyle(ChatFormatting.DARK_GRAY));
		this.search.setValue(query);
		this.search.setResponder(value -> {
			this.scroll = 0;
			this.open = null;
		});
		addRenderableWidget(this.search);

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
			.bounds((this.width - 150) / 2, this.bottom + 6, 150, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		layout();

		graphics.fill(this.left - 1, this.top - 1, this.right + 1, this.bottom + 1, LINE);
		graphics.fill(this.left, this.top, this.right, this.bottom, PANEL);
		graphics.fill(this.left, this.top, this.right, this.top + TITLE_HEIGHT, BAR);
		graphics.centeredText(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), this.width / 2,
			this.top + (TITLE_HEIGHT - 8) / 2, ACCENT);

		int headerTop = this.top + TITLE_HEIGHT;
		int contentTop = headerTop + HEADER_HEIGHT;
		graphics.fill(this.left, contentTop - 1, this.right, contentTop, LINE);
		graphics.fill(this.left + SIDEBAR, headerTop, this.left + SIDEBAR + 1, this.bottom, LINE);
		graphics.centeredText(this.font, Component.translatable("skyzh.category.title"), this.left + SIDEBAR / 2,
			headerTop + 5, MUTED);
		searchIcon(graphics, this.search.getX() - 12, headerTop + 4);

		int y = contentTop + PADDING;
		for (Category category : visibleCategories()) {
			boolean chosen = category == this.selected;
			boolean hovered = !chosen && this.open == null && inside(mouseX, mouseY, this.left, y - 2, this.left + SIDEBAR, y + 11);
			if (chosen) graphics.fill(this.left, y - 3, this.left + 2, y + 11, ACCENT);
			graphics.text(this.font, Component.translatable("skyzh.category." + category.key), this.left + 10, y,
				chosen ? ACCENT : hovered ? TEXT : MUTED, true);
			y += 16;
		}

		int viewLeft = this.left + SIDEBAR + 1 + PADDING;
		int viewRight = this.right - PADDING;
		graphics.enableScissor(viewLeft - PADDING, contentTop, this.right, this.bottom);
		for (Row row : this.rows) {
			int rowTop = row.y - (int) this.scroll;
			if (rowTop + row.height < contentTop || rowTop > this.bottom) continue;
			int x = viewLeft + row.depth * 10;
			boolean hovered = this.open == null && inside(mouseX, mouseY, x, rowTop, viewRight, rowTop + row.height)
				&& mouseY >= contentTop && mouseY < this.bottom;
			row.option.draw(graphics, x, rowTop, viewRight - x, row.height, hovered, mouseX, mouseY);
		}
		graphics.disableScissor();

		int view = this.bottom - contentTop - PADDING;
		if (this.contentHeight > view) {
			int barHeight = Math.max(16, view * view / this.contentHeight);
			int barTop = contentTop + (int) ((view - barHeight) * this.scroll / (this.contentHeight - view));
			graphics.fill(this.right - 3, barTop, this.right - 1, barTop + barHeight, 0xFF6A6A6A);
		}

		// Widgets (search field, done button) go on top of the panel; an open dropdown above both.
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (this.open != null) this.open.drawList(graphics, this.openX, this.openY, mouseX, mouseY);
	}

	/** A magnifying glass, drawn in pixels so it does not depend on an emoji glyph. */
	private static void searchIcon(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x + 1, y, x + 5, y + 1, MUTED);
		graphics.fill(x + 1, y + 5, x + 5, y + 6, MUTED);
		graphics.fill(x, y + 1, x + 1, y + 5, MUTED);
		graphics.fill(x + 5, y + 1, x + 6, y + 5, MUTED);
		graphics.fill(x + 5, y + 5, x + 7, y + 7, MUTED);
		graphics.fill(x + 7, y + 7, x + 9, y + 9, MUTED);
	}

	private List<Category> visibleCategories() {
		List<Category> visible = new ArrayList<>();
		for (Category category : this.categories) {
			if (category.options.stream().anyMatch(option -> option.matches(query()))) visible.add(category);
		}
		return visible;
	}

	private String query() {
		return this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
	}

	/** Rows for the selected category, filtered by the search field; recomputed every frame. */
	private void layout() {
		List<Category> visible = visibleCategories();
		if (!visible.contains(this.selected) && !visible.isEmpty()) {
			this.selected = visible.getFirst();
			this.scroll = 0;
		}
		this.rows.clear();
		int width = this.right - PADDING - (this.left + SIDEBAR + 1 + PADDING);
		int y = this.top + TITLE_HEIGHT + HEADER_HEIGHT + PADDING;
		int start = y;
		if (visible.contains(this.selected)) {
			for (Option option : this.selected.options) y = add(option, 0, y, width);
		}
		this.contentHeight = y - start;
		int view = this.bottom - (this.top + TITLE_HEIGHT + HEADER_HEIGHT) - PADDING;
		this.scroll = Math.max(0, Math.min(this.scroll, this.contentHeight - view));
	}

	private int add(Option option, int depth, int y, int width) {
		String query = query();
		if (!option.visible() || !option.matches(query)) return y;
		int height = option.height(width - depth * 10);
		this.rows.add(new Row(option, depth, y, height));
		y += height + 4;
		if (option instanceof Group group && (group.expanded || !query.isEmpty())) {
			for (Option child : group.children) y = add(child, depth + 1, y, width);
		}
		return y;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x(), mouseY = event.y();
		if (this.open != null) {
			this.open.clickList(mouseX, mouseY, this.openX, this.openY);
			this.open = null;
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != 0) return false;

		int contentTop = this.top + TITLE_HEIGHT + HEADER_HEIGHT;
		int y = contentTop + PADDING;
		for (Category category : visibleCategories()) {
			if (inside(mouseX, mouseY, this.left, y - 3, this.left + SIDEBAR, y + 12)) {
				if (this.selected != category) this.scroll = 0;
				this.selected = category;
				return true;
			}
			y += 16;
		}

		if (mouseY < contentTop || mouseY >= this.bottom) return false;
		int viewLeft = this.left + SIDEBAR + 1 + PADDING;
		for (Row row : this.rows) {
			int rowTop = row.y - (int) this.scroll;
			int x = viewLeft + row.depth * 10;
			if (inside(mouseX, mouseY, x, rowTop, this.right - PADDING, rowTop + row.height)) {
				row.option.click(mouseX, mouseY, x, rowTop, this.right - PADDING - x, row.height);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.open == null && inside(mouseX, mouseY, this.left + SIDEBAR, this.top + TITLE_HEIGHT + HEADER_HEIGHT, this.right, this.bottom)) {
			this.scroll -= scrollY * 16;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x < right && y >= top && y < bottom;
	}

	@Override
	public void onClose() {
		boolean flushCapture = this.captureInitiallyEnabled && !this.config.captureUntranslated;
		this.config.save();
		if (flushCapture) {
			TextCapture.flush();
		}
		ClientGui.setScreen(this.minecraft, this.parent);
		if (this.config.legacyFont != this.legacyFontInitially) ClientPacks.reload();
	}

	static boolean commandScreenRequested() {
		return commandScreenRequested;
	}

	static void clearCommandScreenRequest() {
		commandScreenRequested = false;
		commandScreenPlayer = null;
	}

	private enum Update {
		OFF("off"), RELEASE("release"), BETA("beta");

		private final String key;

		Update(String key) { this.key = key; }
	}

	private record Category(String key, List<Option> options) {}

	private record Row(Option option, int depth, int y, int height) {}

	/** One card: a name, a wrapped description, and a control on the right. */
	private abstract class Option {
		final String key;

		Option(String key) {
			this.key = key;
		}

		Component name() {
			return Component.translatable("skyzh.option." + this.key);
		}

		Component description() {
			return Component.translatable("skyzh.option." + this.key + ".tooltip");
		}

		boolean visible() {
			return true;
		}

		/** Everything except the master switch waits for the master switch. */
		boolean active() {
			return config.enabled || this.key.equals("enabled");
		}

		boolean matches(String query) {
			return query.isEmpty() || name().getString().toLowerCase(Locale.ROOT).contains(query)
				|| description().getString().toLowerCase(Locale.ROOT).contains(query);
		}

		abstract int controlWidth();

		List<FormattedCharSequence> lines(int width) {
			return font.split(description(), Math.max(40, width - controlWidth() - PADDING * 3));
		}

		int height(int width) {
			return PADDING * 2 + 9 + 3 + lines(width).size() * 10;
		}

		void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hovered, int mouseX, int mouseY) {
			graphics.fill(x, y, x + width, y + height, hovered && active() ? CARD_HOVER : CARD);
			graphics.text(font, name(), x + PADDING, y + PADDING, active() ? TEXT : DISABLED, true);
			int line = y + PADDING + 12;
			for (FormattedCharSequence text : lines(width)) {
				graphics.text(font, text, x + PADDING, line, active() ? MUTED : DISABLED, false);
				line += 10;
			}
			drawControl(graphics, x + width - PADDING - controlWidth(), y + (height - CONTROL_HEIGHT) / 2, hovered);
		}

		abstract void drawControl(GuiGraphicsExtractor graphics, int x, int y, boolean hovered);

		void click(double mouseX, double mouseY, int x, int y, int width, int height) {
			if (active()) activate(x + width - PADDING - controlWidth(), y + (height - CONTROL_HEIGHT) / 2);
		}

		abstract void activate(int controlX, int controlY);
	}

	private final class Toggle extends Option {
		private final BooleanSupplier value;
		private final Consumer<Boolean> sink;

		Toggle(String key, BooleanSupplier value, Consumer<Boolean> sink) {
			super(key);
			this.value = value;
			this.sink = sink;
		}

		@Override int controlWidth() { return 26; }

		@Override
		void drawControl(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
			boolean on = this.value.getAsBoolean();
			int top = y + 2;
			graphics.fill(x, top, x + 26, top + 10, !active() ? 0xFF333333 : on ? ON : OFF);
			int knob = on ? x + 15 : x + 1;
			graphics.fill(knob, top + 1, knob + 10, top + 9, active() ? 0xFFE0E0E0 : 0xFF777777);
		}

		@Override
		void activate(int controlX, int controlY) {
			this.sink.accept(!this.value.getAsBoolean());
		}
	}

	private class Choice<T> extends Option {
		private final List<T> values;
		private final java.util.function.Function<T, String> label;
		private final Supplier<T> value;
		private final Consumer<T> sink;

		Choice(String key, List<T> values, java.util.function.Function<T, String> label, Supplier<T> value, Consumer<T> sink) {
			super(key);
			this.values = values;
			this.label = label;
			this.value = value;
			this.sink = sink;
		}

		private Component label(T value) {
			return Component.translatable(this.label.apply(value));
		}

		@Override
		int controlWidth() {
			int widest = 0;
			for (T value : this.values) widest = Math.max(widest, font.width(label(value)));
			return widest + 18;
		}

		@Override
		void drawControl(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
			int width = controlWidth();
			graphics.fill(x, y, x + width, y + CONTROL_HEIGHT, LINE);
			graphics.fill(x + 1, y + 1, x + width - 1, y + CONTROL_HEIGHT - 1, 0xFF262626);
			graphics.text(font, label(this.value.get()), x + 5, y + 3, active() ? TEXT : DISABLED, false);
			graphics.text(font, Component.literal("▾"), x + width - 10, y + 3, active() ? MUTED : DISABLED, false);
		}

		@Override
		void activate(int controlX, int controlY) {
			open = this;
			openX = controlX;
			openY = controlY + CONTROL_HEIGHT;
			if (openY + this.values.size() * 12 + 2 > height) openY = controlY - this.values.size() * 12 - 2;
		}

		void drawList(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
			int width = controlWidth();
			graphics.nextStratum();
			graphics.fill(x, y, x + width, y + this.values.size() * 12 + 2, LINE);
			graphics.fill(x + 1, y + 1, x + width - 1, y + this.values.size() * 12 + 1, 0xFF1C1C1C);
			for (int i = 0; i < this.values.size(); i++) {
				T option = this.values.get(i);
				int top = y + 1 + i * 12;
				if (inside(mouseX, mouseY, x, top, x + width, top + 12)) graphics.fill(x + 1, top, x + width - 1, top + 12, 0xFF333333);
				graphics.text(font, label(option), x + 5, top + 2, option.equals(this.value.get()) ? ACCENT : TEXT, false);
			}
		}

		void clickList(double mouseX, double mouseY, int x, int y) {
			int index = (int) Math.floor((mouseY - y - 1) / 12);
			if (mouseX >= x && mouseX < x + controlWidth() && index >= 0 && index < this.values.size()) {
				this.sink.accept(this.values.get(index));
			}
		}
	}

	/** A collapsible block of related options, like MoulConfig's accordions. */
	private final class Group extends Option {
		private final List<Option> children;
		private boolean expanded;

		Group(String key, List<Option> children) {
			super(key);
			this.children = children;
		}

		@Override
		Component name() {
			return Component.translatable("skyzh.group." + this.key);
		}

		@Override
		boolean active() {
			return config.enabled;
		}

		@Override
		boolean matches(String query) {
			return query.isEmpty() || name().getString().toLowerCase(Locale.ROOT).contains(query)
				|| this.children.stream().anyMatch(child -> child.matches(query));
		}

		@Override int controlWidth() { return 0; }

		@Override int height(int width) { return 20; }

		@Override
		void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hovered, int mouseX, int mouseY) {
			graphics.fill(x, y, x + width, y + height, hovered ? CARD_HOVER : CARD);
			graphics.fill(x, y, x + 2, y + height, ACCENT);
			boolean shown = this.expanded || !query().isEmpty();
			graphics.text(font, Component.literal(shown ? "▼ " : "▶ ").append(name()), x + PADDING + 2, y + 6, TEXT, true);
		}

		@Override void drawControl(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {}

		@Override
		void click(double mouseX, double mouseY, int x, int y, int width, int height) {
			this.expanded = !this.expanded;
		}

		@Override void activate(int controlX, int controlY) {}
	}

	/** The welcome and issue-report text that used to head the first-run screen. */
	private final class Info extends Option {
		Info() {
			super("welcome");
		}

		@Override Component name() { return Component.translatable("skyzh.welcome.title"); }

		@Override
		Component description() {
			return Component.translatable("skyzh.welcome.issue.before")
				.append(Component.literal("[Github]").withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(GITHUB_URI))
				))
				.append(Component.translatable("skyzh.welcome.issue.after"))
				.append("\n")
				.append(Component.translatable("skyzh.welcome.choose"));
		}

		@Override boolean active() { return true; }

		@Override boolean matches(String query) { return query.isEmpty(); }

		@Override int controlWidth() { return 0; }

		@Override void drawControl(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {}

		/** Only the bracketed link reacts, as it did on the old screen. */
		@Override
		void click(double mouseX, double mouseY, int x, int y, int width, int height) {
			int line = (int) Math.floor((mouseY - y - PADDING - 12) / 10);
			List<FormattedCharSequence> lines = lines(width);
			if (line < 0 || line >= lines.size()) return;
			Style style = styleAt(lines.get(line), (int) mouseX - x - PADDING);
			if (style != null && style.getClickEvent() != null) defaultHandleClickEvent(style.getClickEvent(), minecraft, SkyZHConfigScreen.this);
		}

		private Style styleAt(FormattedCharSequence line, int offset) {
			Style[] found = {null};
			int[] advance = {0};
			line.accept((index, style, codePoint) -> {
				int width = font.width(FormattedCharSequence.forward(Character.toString(codePoint), style));
				if (offset >= advance[0] && offset < advance[0] + width) {
					found[0] = style;
					return false;
				}
				advance[0] += width;
				return true;
			});
			return found[0];
		}

		@Override void activate(int controlX, int controlY) {}
	}
}
