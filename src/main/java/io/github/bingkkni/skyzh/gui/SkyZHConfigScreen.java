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
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TextLayout;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.DescriptionRendereringBehaviour;
import io.github.notenoughupdates.moulconfig.Social;
import io.github.notenoughupdates.moulconfig.TitleRenderingBehaviour;
import io.github.notenoughupdates.moulconfig.annotations.SearchTag;
import io.github.notenoughupdates.moulconfig.common.MyResourceLocation;
import io.github.notenoughupdates.moulconfig.common.RenderContext;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;
import io.github.notenoughupdates.moulconfig.gui.GuiComponent;
import io.github.notenoughupdates.moulconfig.gui.GuiContext;
import io.github.notenoughupdates.moulconfig.gui.GuiImmediateContext;
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent;
import io.github.notenoughupdates.moulconfig.gui.GuiOptionEditor;
import io.github.notenoughupdates.moulconfig.gui.KeyboardEvent;
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor;
import io.github.notenoughupdates.moulconfig.gui.MouseEvent;
import io.github.notenoughupdates.moulconfig.gui.component.CenterComponent;
import io.github.notenoughupdates.moulconfig.gui.editors.ComponentEditor;
import io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorAccordion;
import io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorBoolean;
import io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorButton;
import io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorDropdown;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigPlatform;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent;
import io.github.notenoughupdates.moulconfig.processor.ProcessedCategory;
import io.github.notenoughupdates.moulconfig.processor.ProcessedOption;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * The settings screen shared by Mod Menu, commands and first-run guidance.
 *
 * <p>Drawn by MoulConfig, the editor SkyHanni builds its settings with and Skyblocker uses by default,
 * so it looks and handles like the screens SkyBlock players already know: categories on the left,
 * type-to-search, collapsible groups and one card per option. The options are assembled here rather
 * than from annotated fields, because {@link SkyZHConfig} keeps its hand-editable JSON and several
 * options change more than their own field. The build relocates MoulConfig into this mod, so whatever
 * version those mods bundle never meets this one.
 */
public final class SkyZHConfigScreen {
	private static final URI GITHUB_URI = URI.create("https://github.com/BingKKni/SkyBlockZH");
	private static final URI ISSUES_URI = URI.create("https://github.com/BingKKni/SkyBlockZH/issues");

	/** Consumed on the next tick, after ChatScreen has closed itself. */
	private static boolean commandScreenRequested;
	private static LocalPlayer commandScreenPlayer;

	private SkyZHConfigScreen() {
	}

	/** Builds the screen; closing it returns to {@code parent}. */
	public static Screen create(Screen parent) {
		SkyZHConfig config = SkyZHConfig.get();
		Layout layout = new Layout();
		List<Category> categories = List.of(
			new Category("general", layout,
				welcome(),
				toggle("enabled", () -> config.enabled, value -> config.enabled = value).always(),
				choice("updateCheck", List.of(Update.values()), value -> "skyzh.updateCheck." + value.key,
					() -> !config.updateCheck ? Update.OFF : config.updateChannel == UpdateChannel.BETA ? Update.BETA : Update.RELEASE,
					value -> {
						config.updateCheck = value != Update.OFF;
						if (value != Update.OFF) config.updateChannel = value == Update.BETA ? UpdateChannel.BETA : UpdateChannel.RELEASE;
					}),
				choice("updateInstall", List.of(UpdateInstall.values()), value -> "skyzh.updateInstall." + value.key(),
					() -> config.updateInstall, value -> config.updateInstall = value).shownWhen(() -> config.updateCheck),
				toggle("originalTips", () -> config.originalTips, value -> config.originalTips = value)),
			new Category("translation", layout,
				toggle("translateSkyBlockName", () -> config.translateSkyBlockName, value -> config.translateSkyBlockName = value),
				toggle("translateItemNames", () -> config.translateItemNames, value -> {
					config.translateItemNames = value;
					SkyZHConfig.bumpGeneration();
				}),
				toggle("translateMobNames", () -> config.translateMobNames, value -> {
					config.translateMobNames = value;
					SkyZHConfig.bumpGeneration();
				})),
			new Category("advanced", layout,
				choice("legacyFont", List.of(true, false), value -> "skyzh.legacyFont." + (value ? "on" : "off"),
					() -> config.legacyFont, value -> config.legacyFont = value),
				new Group("displayPriority",
					choice("displayPriority", List.of(DisplayPriority.values()), value -> "skyzh.priority." + value.key(),
						() -> config.displayPriority, value -> {
							config.displayPriority = value;
							SkyZHConfig.bumpGeneration();
						})),
				new Group("capture",
					toggle("captureUntranslated", () -> config.captureUntranslated, value -> config.captureUntranslated = value),
					toggle("captureNotifications", () -> config.captureNotifications, value -> {
						config.captureNotifications = value;
						CaptureAnnouncer.clear();
					}),
					toggle("autoClearCapture", () -> config.autoClearCapture, value -> config.autoClearCapture = value))));
		return new SettingsScreen(new MoulConfigEditor<>(ProcessedCategory.collect(categories), layout), parent);
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
				ClientGui.setScreen(minecraft, create(null));
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
		ClientGui.setScreen(minecraft, create(null));
	}

	/** Whether {@code screen} is this one, where MoulConfig sends every typed character to its search field. */
	public static boolean takesTyping(Screen screen) {
		return screen instanceof SettingsScreen;
	}

	static boolean commandScreenRequested() {
		return commandScreenRequested;
	}

	static void clearCommandScreenRequest() {
		commandScreenRequested = false;
		commandScreenPlayer = null;
	}

	private static Entry toggle(String key, BooleanSupplier value, Consumer<Boolean> sink) {
		return new Entry(key, boolean.class, value::getAsBoolean, set -> sink.accept((Boolean) set),
			entry -> new GuiOptionEditorBoolean(entry, -1, entry.getConfig()) {
				@Override
				protected GuiComponent wrapComponent(GuiComponent control) { return card(this, entry, control); }

				@Override
				public int getHeight() { return getDelegate().getHeight(); }
			});
	}

	/** A dropdown over {@code values}; MoulConfig reads and writes the index of the chosen label. */
	private static <T> Entry choice(String key, List<T> values, Function<T, String> label, Supplier<T> value, Consumer<T> sink) {
		String[] labels = values.stream().map(each -> Component.translatable(label.apply(each)).getString()).toArray(String[]::new);
		return new Entry(key, int.class, () -> values.indexOf(value.get()), index -> sink.accept(values.get((Integer) index)),
			entry -> new GuiOptionEditorDropdown(entry, labels, true) {
				@Override
				protected GuiComponent wrapComponent(GuiComponent control) { return card(this, entry, control); }

				@Override
				public int getHeight() { return getDelegate().getHeight(); }
			});
	}

	/** The greeting that heads General, with the way to report a problem. */
	private static Entry welcome() {
		Runnable report = () -> ConfirmLinkScreen.confirmLinkNow(ClientGui.screen(Minecraft.getInstance()), ISSUES_URI);
		Entry entry = new Entry("welcome", Component.translatable("skyzh.welcome.title"), Component.translatable("skyzh.welcome.description"),
			Runnable.class, () -> report, ignored -> {},
			self -> new GuiOptionEditorButton(self, -1, MoulConfigPlatform.wrap(Component.literal("GitHub")), self.getConfig()) {
				@Override
				protected GuiComponent wrapComponent(GuiComponent control) { return card(this, self, control); }

				@Override
				public int getHeight() { return getDelegate().getHeight(); }
			});
		entry.heading = true;
		return entry.always();
	}

	/** Keep MoulConfig's cards and controls, but use our existing CJK-aware wrapping for descriptions. */
	private static GuiComponent card(ComponentEditor owner, Entry entry, GuiComponent control) {
		return owner.new EditorComponentWrapper(new CenterComponent(control)) {
			private int descriptionWidth = 156;

			private List<Component> lines(int width) {
				StyledText text = StyledText.of(entry.description);
				List<Component> lines = new ArrayList<>();
				int start = 0;
				for (String paragraph : text.plain().split("\n", -1)) {
					lines.addAll(TextLayout.wrap(Minecraft.getInstance().font, text.slice(start, start + paragraph.length()), width));
					start += paragraph.length() + 1;
				}
				return lines;
			}

			@Override
			protected int getDescriptionHeight() {
				return Math.max(45, lines(this.descriptionWidth).size() * 10 + 14);
			}

			@Override
			protected void renderDescription(GuiImmediateContext context) {
				this.descriptionWidth = context.getWidth() * 2 / 3 - 10;
				RenderContext render = context.getRenderContext();
				var font = render.getMinecraft().getDefaultFontRenderer();
				render.pushMatrix();
				render.translate(5 + context.getWidth() / 3, entry.heading ? font.getHeight() + 13 : 5);
				for (Component line : lines(this.descriptionWidth)) {
					render.drawString(font, MoulConfigPlatform.wrap(line), 0, 0, 0xc0c0c0, false);
					render.translate(0, font.getHeight() + 1);
				}
				render.popMatrix();
			}
		};
	}

	private enum Update {
		OFF("off"), RELEASE("release"), BETA("beta");

		private final String key;

		Update(String key) { this.key = key; }
	}

	/** MoulConfig's own close leaves no screen at all; this one returns to whatever opened it, saving on the way. */
	private static final class SettingsScreen extends MoulConfigScreenComponent {
		private final SkyZHConfig config = SkyZHConfig.get();
		private final boolean captureInitiallyEnabled = this.config.captureUntranslated;
		private final boolean legacyFontInitially = this.config.legacyFont;

		SettingsScreen(MoulConfigEditor<?> editor, Screen parent) {
			super(Component.literal("SkyZH"), new GuiContext(new GuiElementComponent(editor)), parent);
		}

		@Override
		public void onClose() {
			boolean flushCapture = this.captureInitiallyEnabled && !this.config.captureUntranslated;
			this.config.save();
			if (flushCapture) {
				TextCapture.flush();
			}
			ClientGui.setScreen(this.minecraft, getPreviousScreen());
			if (this.config.legacyFont != this.legacyFontInitially) ClientPacks.reload();
		}
	}

	/** What MoulConfig asks the config object about the screen as a whole. */
	private static final class Layout extends Config {
		private final List<Social> socials = List.of(new GitHub());

		@Override
		public StructuredText getTitle() {
			return MoulConfigPlatform.wrap(Component.literal("SkyZH " + version() + " Beta").withStyle(ChatFormatting.GOLD));
		}

		@Override
		public List<Social> getSocials() {
			return this.socials;
		}

		/** Chinese scaled down to fit a fixed card is unreadable, so a long description grows its card instead. */
		@Override
		public DescriptionRendereringBehaviour getDescriptionBehaviour(ProcessedOption option) {
			return DescriptionRendereringBehaviour.EXPAND_PANEL;
		}

		/** A heading is too long for the left third every other card keeps for its name. */
		@Override
		public TitleRenderingBehaviour getTitleRenderingBehaviour(ProcessedOption option) {
			return ((Entry) option).heading ? TitleRenderingBehaviour.WIDE_CENTERED_UNDERLINED : TitleRenderingBehaviour.LEFT;
		}
	}

	/** The GitHub mark in the title bar, where SkyHanni keeps its links. */
	private static final class GitHub extends Social {
		@Override
		public void onClick() {
			ConfirmLinkScreen.confirmLinkNow(ClientGui.screen(Minecraft.getInstance()), GITHUB_URI);
		}

		@Override
		public List<StructuredText> getTooltip() {
			return List.of(MoulConfigPlatform.wrap(Component.literal("GitHub")));
		}

		@Override
		public MyResourceLocation getIcon() {
			return new MyResourceLocation(SkyZH.MOD_ID, "social/github.png");
		}
	}

	/** A tab on the left: its options in display order, each group followed by the options it folds away. */
	private static final class Category implements ProcessedCategory {
		private final String key;
		private final List<ProcessedOption> options = new ArrayList<>();
		private final Map<Integer, ProcessedOption> groups = new HashMap<>();

		Category(String key, Layout layout, Entry... entries) {
			this.key = key;
			for (Entry entry : entries) add(entry, -1, layout);
		}

		private void add(Entry entry, int group, Layout layout) {
			entry.category = this;
			entry.layout = layout;
			entry.accordionId = group;
			this.options.add(entry);
			if (entry instanceof Group block) {
				block.id = this.groups.size();
				this.groups.put(block.id, block);
				for (Entry child : block.children) add(child, block.id, layout);
			}
		}

		@Override
		public StructuredText getDisplayName() {
			return MoulConfigPlatform.wrap(Component.translatable("skyzh.category." + this.key));
		}

		@Override
		public StructuredText getDescription() {
			return MoulConfigPlatform.wrap(Component.translatable("skyzh.category." + this.key + ".tooltip"));
		}

		@Override
		public String getIdentifier() {
			return this.key;
		}

		@Override
		public String getParentCategoryId() {
			return null;
		}

		@Override
		public List<ProcessedOption> getOptions() {
			return this.options;
		}

		@Override
		public Map<Integer, ProcessedOption> getAccordionAnchors() {
			return this.groups;
		}

		@Override
		public String getDebugDeclarationLocation() {
			return this.key;
		}
	}

	/** One card: a name, a description, the value MoulConfig reads and writes, and the editor that shows it. */
	private static class Entry implements ProcessedOption {
		private final String key;
		private final Component name;
		private final Component description;
		private final Type type;
		private final Supplier<?> value;
		private final Consumer<Object> sink;
		private final Function<Entry, GuiOptionEditor> factory;
		/** A heading spans its card and stays out of search results. */
		boolean heading;
		/** Everything except the master switch waits for the master switch. */
		boolean gated = true;
		BooleanSupplier shown = () -> true;
		int accordionId = -1;
		Category category;
		Layout layout;
		GuiOptionEditor editor;

		Entry(String key, Type type, Supplier<?> value, Consumer<Object> sink, Function<Entry, GuiOptionEditor> factory) {
			this(key, Component.translatable("skyzh.option." + key), Component.translatable("skyzh.option." + key + ".tooltip"),
				type, value, sink, factory);
		}

		Entry(String key, Component name, Component description, Type type, Supplier<?> value, Consumer<Object> sink,
			Function<Entry, GuiOptionEditor> factory) {
			this.key = key;
			this.name = name;
			this.description = description;
			this.type = type;
			this.value = value;
			this.sink = sink;
			this.factory = factory;
		}

		Entry always() {
			this.gated = false;
			return this;
		}

		Entry shownWhen(BooleanSupplier shown) {
			this.shown = shown;
			return this;
		}

		@Override
		public GuiOptionEditor getEditor() {
			if (this.editor == null) this.editor = new Gate(this, this.factory.apply(this));
			return this.editor;
		}

		@Override
		public SearchTag[] getSearchTags() {
			return new SearchTag[0];
		}

		@Override
		public int getAccordionId() {
			return this.accordionId;
		}

		@Override
		public ProcessedCategory getCategory() {
			return this.category;
		}

		@Override
		public StructuredText getName() {
			return MoulConfigPlatform.wrap(this.name);
		}

		@Override
		public StructuredText getDescription() {
			return MoulConfigPlatform.wrap(this.description);
		}

		@Override
		public String getPath() {
			return this.key;
		}

		@Override
		public Config getConfig() {
			return this.layout;
		}

		@Override
		public Object get() {
			return this.value.get();
		}

		@Override
		public Type getType() {
			return this.type;
		}

		@Override
		public boolean set(Object value) {
			this.sink.accept(value);
			return true;
		}

		@Override
		public void explicitNotifyChange() {
		}

		@Override
		public String getDebugDeclarationLocation() {
			return this.key;
		}
	}

	/** A collapsible block, MoulConfig's accordion. */
	private static final class Group extends Entry {
		final List<Entry> children;
		int id;

		Group(String key, Entry... children) {
			super(key, Component.translatable("skyzh.group." + key), Component.empty(), Void.class, () -> null, ignored -> {}, null);
			this.children = List.of(children);
		}

		/** Left unwrapped: MoulConfig recognises an accordion by its editor's class. */
		@Override
		public GuiOptionEditor getEditor() {
			if (this.editor == null) this.editor = new GuiOptionEditorAccordion(this, this.id);
			return this.editor;
		}
	}

	/**
	 * The two rules MoulConfig has no notion of: an option that does not apply takes no room, and one
	 * waiting for the master switch is dimmed and ignores input.
	 */
	private static final class Gate extends GuiOptionEditor {
		private final Entry entry;
		private final GuiOptionEditor editor;

		Gate(Entry entry, GuiOptionEditor editor) {
			super(entry);
			this.entry = entry;
			this.editor = editor;
		}

		private boolean shown() {
			return this.entry.shown.getAsBoolean();
		}

		private boolean usable() {
			return shown() && (!this.entry.gated || SkyZHConfig.get().enabled);
		}

		/** MoulConfig leaves five pixels after every card; a hidden one gives them back. */
		@Override
		public int getHeight() {
			return shown() ? this.editor.getHeight() : -5;
		}

		@Override
		public void render(RenderContext context, int x, int y, int width) {
			if (!shown()) return;
			this.editor.render(context, x, y, width);
			// A card's background stops two pixels short of its height.
			if (!usable()) context.drawColoredRect(x, y, x + width, y + getHeight() - 2, 0x80000000);
		}

		@Override
		public void renderOverlay(RenderContext context, int x, int y, int width) {
			if (shown()) this.editor.renderOverlay(context, x, y, width);
		}

		@Override
		public boolean mouseInput(int x, int y, int width, int mouseX, int mouseY, MouseEvent event) {
			return usable() && this.editor.mouseInput(x, y, width, mouseX, mouseY, event);
		}

		@Override
		public boolean mouseInputOverlay(int x, int y, int width, int mouseX, int mouseY, MouseEvent event) {
			return usable() && this.editor.mouseInputOverlay(x, y, width, mouseX, mouseY, event);
		}

		@Override
		public boolean keyboardInput(KeyboardEvent event) {
			return usable() && this.editor.keyboardInput(event);
		}

		@Override
		public boolean fulfillsSearch(String word) {
			return shown() && !this.entry.heading && this.editor.fulfillsSearch(word);
		}

		@Override
		public void setGuiContext(GuiContext context) {
			this.editor.setGuiContext(context);
		}
	}
}
