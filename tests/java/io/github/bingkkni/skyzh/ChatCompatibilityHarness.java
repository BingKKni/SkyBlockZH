package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.compat.ChatCompatibility;
import io.github.bingkkni.skyzh.text.ChatDisplayCache;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/** Regression checks for display-only adaptation, packet provenance and stateful splitter caching. */
public final class ChatCompatibilityHarness {
	private static int checks;

	public static void main(String[] args) throws Exception {
		TranslationHarness.installIndex(TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0]))));
		counters();
		scopes();
		cache();
		locationReplies();
		System.out.println("Chat compatibility: " + checks + " checks passed");
	}

	/** Executed through Knot with real third-party mixins, before Minecraft's main method. */
	public static void woven(String corpus) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("skyhanni")) {
			Class<?> mod = Class.forName("at.hannibal2.skyhanni.SkyHanniMod");
			var data = mod.getField("visualWordsData");
			data.set(null, data.getType().getConstructor().newInstance());
		}
		TranslationHarness.installIndex(TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(corpus))));
		Component source = Component.literal("original"), translated = Component.literal("译文");
		ChatCompatibility.display(source, () -> translated, () -> {
			StringBuilder visible = new StringBuilder();
			source.getVisualOrderText().accept((i, style, cp) -> { visible.appendCodePoint(cp); return true; });
			require(visible.toString().equals("译文"), "woven visual read translates");
			require(source.getString().equals("original"), "woven history read remains original");
		});
		StringBuilder original = new StringBuilder();
		source.getVisualOrderText().accept((i, style, cp) -> { original.appendCodePoint(cp); return true; });
		require(original.toString().equals("original"), "woven scope did not poison Minecraft visual cache");
		ChatCompatibility.server(source, () -> {
			GuiMessage message = new GuiMessage(1, source, null, GuiMessageSource.SYSTEM_SERVER, null);
			require((Object) message instanceof ChatCompatibility.Origin, "vanilla history has sidecar interface");
			Component packet = ((ChatCompatibility.Origin) (Object) message).skyzh$serverOriginal();
			require(packet != null && packet.getString().equals("original"), "woven constructor captures provenance");
			GuiMessage relayed = new GuiMessage(1, source, null, GuiMessageSource.SYSTEM_CLIENT, null);
			require(ChatDisplayCache.isServerMessage(relayed), "exact packet re-emission remains translatable");
			GuiMessage local = new GuiMessage(1, Component.literal("local notice"), null, GuiMessageSource.SYSTEM_CLIENT, null);
			require(((ChatCompatibility.Origin) (Object) local).skyzh$serverOriginal() == null, "unrelated client message receives no provenance");
		});
		require(!ChatDisplayCache.isServerMessage(new GuiMessage(1, source, null, GuiMessageSource.SYSTEM_CLIENT, null)),
			"delayed identical local text is not packet provenance");
		// Use the actual BHC alignment provider from the installed JAR, with deterministic glyph widths.
		// It recognizes a 320px server anchor even when the user's chat is only 120px wide.
		var font = new net.minecraft.client.gui.Font(null) {
			@Override public int width(net.minecraft.network.chat.FormattedText text) {
				return text.getString().codePoints().map(cp -> cp == ' ' ? 4 : 6).sum();
			}
			@Override public int width(net.minecraft.util.FormattedCharSequence text) {
				int[] width = {0};
				text.accept((i, style, cp) -> { width[0] += cp == ' ' ? 4 : 6; return true; });
				return width[0];
			}
			@Override public net.minecraft.client.StringSplitter getSplitter() {
				return new net.minecraft.client.StringSplitter((cp, style) -> cp == ' ' ? 4 : 6);
			}
		};
		Component banner = Component.literal(" ".repeat(28) + "SKYBLOCK LEVEL UP");
		Component chinese = ChatCompatibility.translateVisual(banner, font);
		require(!chinese.getString().equals(banner.getString()), "banner fixture translates");
		Class<?> providerType = Class.forName("io.github.viciscat.bhc.CenteredLine$Provider");
		Object provider = providerType.getConstructor().newInstance();
		var provide = providerType.getMethod("getLineRenderers", net.minecraft.client.gui.Font.class,
			Component.class, String.class, String.class, int.class);
		try (AutoCloseable client = chatOptions()) {
			queue(font);
			for (Component text : new Component[] {banner, chinese}) {
				var result = (java.util.Optional<?>) provide.invoke(provider, font, text, text.getString(), text.getString().trim(), 120);
				require(result.isPresent(), "BHC retains its centered renderer in narrow chat");
			}
		}
		System.out.println("Real Mod composition: " + checks + " checks passed");
	}

	private static Object allocate(Class<?> type) throws Exception {
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		var singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
		return unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
	}

	private static void field(Class<?> owner, Object object, String name, Object value) throws Exception {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
	}

	private static void queue(net.minecraft.client.gui.Font font) throws Exception {
		var mc = net.minecraft.client.Minecraft.getInstance();
		field(net.minecraft.client.Minecraft.class, mc, "font", font);
		var player = (net.minecraft.client.player.LocalPlayer) allocate(net.minecraft.client.player.LocalPlayer.class);
		var listener = (net.minecraft.client.multiplayer.ClientPacketListener) allocate(net.minecraft.client.multiplayer.ClientPacketListener.class);
		var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
		var channel = new io.netty.channel.embedded.EmbeddedChannel();
		field(net.minecraft.network.Connection.class, connection, "channel", channel);
		field(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class, listener, "connection", connection);
		field(net.minecraft.client.player.LocalPlayer.class, player, "connection", listener);
		mc.player = player;
		field(HypixelServer.class, null, "sessionConnection", connection);
		field(HypixelServer.class, null, "receivedHello", true);
		field(HypixelServer.class, null, "skyBlock", true);
		field(Class.forName("io.github.viciscat.bhc.BetterHypixelChatMod"), null, "onHypixel", true);
		for (String option : new String[] {"chatScale", "chatWidth", "chatHeightFocused", "chatHeightUnfocused"})
			field(net.minecraft.client.Options.class, mc.options, option,
				new net.minecraft.client.OptionInstance<Double>("", net.minecraft.client.OptionInstance.noTooltip(),
					(c, v) -> Component.empty(), net.minecraft.client.OptionInstance.UnitDouble.INSTANCE, 1.0, v -> {}));
		field(net.minecraft.client.Minecraft.class, mc, "gui", allocate(Class.forName("net.minecraft.client.gui.Gui")));
		field(net.minecraft.client.Minecraft.class, mc, "gameThread", Thread.currentThread());
		Object gui = net.minecraft.client.Minecraft.class.getField("gui").get(mc);
		field(gui.getClass(), gui, "hud", allocate(Class.forName("net.minecraft.client.gui.Hud")));
		field(net.minecraft.client.Minecraft.class, mc, "gameDirectory", net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile());
		var chat = new net.minecraft.client.gui.components.ChatComponent(mc);
		var add = chat.getClass().getDeclaredMethod("addMessageToDisplayQueue", GuiMessage.class); add.setAccessible(true);
		var rows = chat.getClass().getDeclaredField("trimmedMessages"); rows.setAccessible(true);
		Component source = Component.literal("§e[NPC] §r§bSalesman§r§f: §rThank you for supporting the server and allowing us to maintain SkyBlock!");
		GuiMessage message = new GuiMessage(0, source, null, GuiMessageSource.SYSTEM_SERVER, null);
		require(HypixelServer.canTranslate() && ChatCompatibility.usesVisualChat(), "real queue gates enabled");
		add.invoke(chat, message);
		StringBuilder output = new StringBuilder();
		for (var line : (java.util.List<GuiMessage.Line>) rows.get(chat))
			line.content().accept((i, style, cp) -> { output.appendCodePoint(cp); return true; });
		require(output.codePoints().anyMatch(cp -> cp >= 0x4e00 && cp <= 0x9fff), "real BHC queue translates Salesman: " + output);
		require(message.content() == source, "queue retains original parent");
		filteredReplies();
		sidebarScope();
		channel.close();
	}

	private static void filteredReplies() throws Exception {
		Class<?> type = io.github.bingkkni.skyzh.hook.ForceChat.class;
		var field = type.getDeclaredField("MESSAGES"); field.setAccessible(true);
		var messages = (java.util.List<GuiMessage>) field.get(null);
		Component reply = Component.literal("{\"server\":\"mini1\",\"gametype\":\"SKYBLOCK\",\"mode\":\"hub\",\"map\":\"Hub\"}");
		io.github.bingkkni.skyzh.hook.ForceChat.clear();
		io.github.bingkkni.skyzh.hook.ForceChat.server(reply, () -> {});
		require(messages.isEmpty(), "filtered locraw does not leak into high/force history");
		io.github.bingkkni.skyzh.hook.ForceChat.server(reply, () ->
			io.github.bingkkni.skyzh.hook.ForceChat.message(new GuiMessage(1, reply, null, GuiMessageSource.SYSTEM_SERVER, null)));
		require(messages.size() == 1 && messages.getFirst().content().getString().equals(reply.getString()), "manually visible locraw retained once");
		io.github.bingkkni.skyzh.hook.ForceChat.clear();
	}

	private static void sidebarScope() throws Exception {
		if (!io.github.bingkkni.skyzh.hook.SidebarCompatibility.defaultAdapterInstalled()) return;
		Class<?> type = io.github.bingkkni.skyzh.hook.PriorityText.class;
		var hud = type.getDeclaredField("HUD"); hud.setAccessible(true);
		var sources = (java.util.Map<?, ?>) hud.get(null);
		var put = type.getDeclaredMethod("put", java.util.Map.class, Component.class, io.github.bingkkni.skyzh.text.Surface.class);
		put.setAccessible(true);
		put.invoke(null, sources, Component.literal("SKYBLOCK"), io.github.bingkkni.skyzh.text.Surface.SCOREBOARD);
		var original = Component.literal("SKYBLOCK").withStyle(ChatFormatting.GOLD).getVisualOrderText();
		require(io.github.bingkkni.skyzh.hook.PriorityText.resolve(original) == null, "default sidebar does not affect other HUDs");
		io.github.bingkkni.skyzh.hook.SidebarCompatibility.display(() -> {
			var translated = io.github.bingkkni.skyzh.hook.PriorityText.resolve(original);
			require(translated != null, "default custom sidebar translates verified row");
			translated.accept((i, style, cp) -> { require(style.getColor().getValue() == 0xffaa00, "custom sidebar colour retained"); return true; });
			require(io.github.bingkkni.skyzh.hook.PriorityText.resolve(Component.literal("Custom Mod row").getVisualOrderText()) == null, "custom prose is excluded");
		});
		try { io.github.bingkkni.skyzh.hook.SidebarCompatibility.display(() -> { throw new IllegalStateException("fixture"); }); }
		catch (IllegalStateException expected) { }
		require(!io.github.bingkkni.skyzh.hook.SidebarCompatibility.inDisplay(), "sidebar scope released on failure");
		sources.clear();
	}

	/** Minimal options for vanilla wrapping; never constructs a window, game session or connection. */
	private static AutoCloseable chatOptions() throws Exception {
		Class<?> type = Class.forName("sun.misc.Unsafe");
		var singleton = type.getDeclaredField("theUnsafe");
		singleton.setAccessible(true);
		Object unsafe = singleton.get(null);
		var allocate = type.getMethod("allocateInstance", Class.class);
		var mc = (net.minecraft.client.Minecraft) allocate.invoke(unsafe, net.minecraft.client.Minecraft.class);
		var options = (net.minecraft.client.Options) allocate.invoke(unsafe, net.minecraft.client.Options.class);
		var colors = net.minecraft.client.Options.class.getDeclaredField("chatColors");
		colors.setAccessible(true);
		colors.set(options, net.minecraft.client.OptionInstance.createBoolean("options.chat.color", true));
		var optionsField = net.minecraft.client.Minecraft.class.getDeclaredField("options");
		optionsField.setAccessible(true);
		optionsField.set(mc, options);
		var instance = net.minecraft.client.Minecraft.class.getDeclaredField("instance");
		instance.setAccessible(true);
		Object previous = instance.get(null);
		instance.set(null, mc);
		return () -> instance.set(null, previous);
	}

	private static void counters() {
		Style action = Style.EMPTY.withColor(ChatFormatting.WHITE)
			.withClickEvent(new ClickEvent.RunCommand("/test-dialogue"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("original hover")));
		Component source = Component.empty().append("[NPC] Dean: ")
			.append(Component.literal("Oh, you are willing to help?").setStyle(action));
		UnaryOperator<Component> translate = text -> Translator.translateChatBlock(text, 320, c -> StyledText.of(c).length() * 6);
		String translated = translate.apply(source).getString();
		require(!translated.equals(source.getString()), "real corpus fixture translates");
		StyledText before = StyledText.of(source);
		for (int count : new int[] {2, 9, 10, 18, 103, Integer.MAX_VALUE}) {
			Component suffix = Component.literal(" (" + count + ")").withStyle(ChatFormatting.GRAY);
			Component visible = source.copy().append(suffix);
			Component result = ChatCompatibility.translate(visible, source, true, translate);
			require(result.getString().equals(translated + suffix.getString()), "counter retained " + count);
			StyledText output = StyledText.of(result);
			int boundary = output.length() - suffix.getString().length();
			require(StyledText.of(translate.apply(source)).equals(StyledText.of(output.slice(0, boundary))), "body interactions preserved");
			require(StyledText.of(suffix).equals(StyledText.of(output.slice(boundary, output.length()))), "counter style preserved");
			require(before.equals(StyledText.of(source)), "packet never mutated");
			require(visible.getString().equals(source.getString() + suffix.getString()), "history never mutated");
		}
		for (String counter : new String[] {" (2)", " (×2)", " (2) (3)", " (×2) (3) (4)"}) {
			for (Style style : new Style[] {Style.EMPTY.withColor(ChatFormatting.GRAY), Style.EMPTY.applyLegacyFormat(ChatFormatting.GRAY)}) {
				Component suffix = Component.literal(counter).setStyle(style);
				Component result = ChatCompatibility.translate(source.copy().append(suffix), source, true, translate);
				require(result.getString().equals(translated + counter), "combined counter translated: " + counter);
				require(StyledText.of(result).styleAt(result.getString().length() - 1).equals(style), "counter style retained");
			}
		}
		Component decorated = source.copy().append(Component.literal(" (2)").withStyle(ChatFormatting.GRAY));
		for (Component original : new Component[] {null, decorated, Component.literal("different server text")}) {
			require(ChatCompatibility.translate(decorated, original, true, c -> c) == decorated,
				"missing provenance or server-authored counter is untouched");
		}
		require(ChatCompatibility.translate(decorated, source, false, c -> c) == decorated, "unknown mod not adapted");
		for (Component suffix : new Component[] {Component.literal(" (2)"),
			Component.literal(" (2)").withStyle(ChatFormatting.RED),
			Component.literal(" (2)").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withClickEvent(new ClickEvent.RunCommand("/other"))),
			Component.literal(" (1)").withStyle(ChatFormatting.GRAY),
			Component.literal(" (02)").withStyle(ChatFormatting.GRAY),
			Component.literal(" (2) extra").withStyle(ChatFormatting.GRAY)}) {
			Component text = source.copy().append(suffix);
			require(ChatCompatibility.translate(text, source, true, c -> c) == text, "unknown suffix untouched");
		}
	}

	private static void scopes() {
		Component outer = Component.literal("outer"), inner = Component.literal("inner");
		Component translated = Component.literal("译文");
		ChatCompatibility.server(outer, () -> {
			require(ChatCompatibility.serverOriginal().getString().equals("outer"), "packet scope");
			try { ChatCompatibility.server(inner, () -> { throw new IllegalStateException("fixture"); }); }
			catch (IllegalStateException expected) { }
			require(ChatCompatibility.serverOriginal().getString().equals("outer"), "nested packet restored on failure");
		});
		require(ChatCompatibility.serverOriginal() == null, "packet scope ends");
		ChatCompatibility.display(outer, () -> {
			require(ChatCompatibility.visual(outer) == null, "translation reentry suppressed");
			return translated;
		}, () -> {
			require(ChatCompatibility.visual(outer) == translated, "custom splitter gets display translation");
			require(outer.getString().equals("outer"), "filters still read original");
			require(ChatCompatibility.visual(Component.literal("outer")) == null, "equal text is not identity evidence");
			try {
				ChatCompatibility.display(inner, () -> Component.literal("inner translation"), () -> {
					require(ChatCompatibility.visual(outer) == null, "nested view isolated");
					throw new IllegalStateException("fixture");
				});
			} catch (IllegalStateException expected) { }
			require(ChatCompatibility.visual(outer) == translated, "outer view restored after failure");
		});
		require(ChatCompatibility.visual(outer) == null, "visual scope ends");
		try { ChatCompatibility.display(outer, () -> { throw new IllegalStateException("fixture"); }, () -> ChatCompatibility.visual(outer)); }
		catch (IllegalStateException expected) { }
		require(ChatCompatibility.visual(outer) == null, "failed translation releases view");
	}

	private static void cache() {
		ChatDisplayCache cache = new ChatDisplayCache();
		var content = Component.literal("source");
		GuiMessage message = new GuiMessage(1, content, null, GuiMessageSource.SYSTEM_SERVER, null);
		Object font = new Object(), corpus = new Object();
		AtomicInteger calls = new AtomicInteger();
		var hover = Component.literal("hover");
		Component supplied = Component.empty().append(Component.literal("译文")
			.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(hover))));
		Component first = cache.text(message, font, 320, true, corpus, () -> {
			calls.incrementAndGet(); return supplied;
		});
		hover.append(" changed by supplier");
		Component second = cache.text(message, font, 320, true, corpus, () -> { throw new AssertionError("cache miss"); });
		require(first != second && StyledText.of(first).equals(StyledText.of(second)) && calls.get() == 1,
			"cached translation is copied for each splitter");
		((net.minecraft.network.chat.MutableComponent) first).append(" appended by splitter");
		((net.minecraft.network.chat.MutableComponent) first.getSiblings().getFirst()).append(" nested edit");
		var firstHover = (HoverEvent.ShowText) first.getSiblings().getFirst().getStyle().getHoverEvent();
		((net.minecraft.network.chat.MutableComponent) firstHover.value()).append(" changed by splitter");
		Component clean = cache.text(message, font, 320, true, corpus, () -> { throw new AssertionError("cache miss"); });
		require(clean.getString().equals("译文"), "splitter edits cannot poison cached translation");
		var cleanHover = (HoverEvent.ShowText) clean.getSiblings().getFirst().getStyle().getHoverEvent();
		require(cleanHover.value().getString().equals("hover"), "supplier and splitter hover edits are isolated");
		content.append(" changed");
		Component changed = cache.text(message, font, 320, true, corpus, () -> Component.literal("changed"));
		require(changed != first, "mutated source invalidates cache");
		Component resized = cache.text(message, font, 120, true, corpus, () -> Component.literal("narrow"));
		require(resized != changed, "width invalidates translation layout");
		cache.clear();
		require(StyledText.of(cache.text(message, font, 120, true, corpus, () -> first)).equals(StyledText.of(first)),
			"resource reload invalidates");
		require(!ChatDisplayCache.isServerMessage(new GuiMessage(1, content, null, GuiMessageSource.SYSTEM_CLIENT, null)), "client notices excluded");
		require(!ChatDisplayCache.isServerMessage(new GuiMessage(1, content, null, GuiMessageSource.PLAYER, null)), "player messages excluded");
	}

	private static void locationReplies() {
		for (String json : new String[] {
			"{\"server\":\"mini1\",\"gametype\":\"SKYBLOCK\",\"mode\":\"hub\",\"map\":\"Hub\"}",
			"{\"server\":\"lobby1\",\"gametype\":\"MAIN\",\"lobbyname\":\"lobby4\"}"})
			require(io.github.bingkkni.skyzh.hook.ForceChat.isLocationReply(Component.literal(json)), "location protocol recognized");
		for (String text : new String[] {"hello", "{}", "{\"server\":\"example\"}",
			"{\"server\":1,\"gametype\":\"SKYBLOCK\"}", "{\"server\":\"a\",\"gametype\":\"b\",\"chat\":\"hello\"}",
			"{\"server\":\"a\",\"gametype\":}", "[Player] {\"server\":\"a\",\"gametype\":\"b\"}"})
			require(!io.github.bingkkni.skyzh.hook.ForceChat.isLocationReply(Component.literal(text)), "ordinary JSON/chat preserved");
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new AssertionError(label);
		checks++;
	}
}
