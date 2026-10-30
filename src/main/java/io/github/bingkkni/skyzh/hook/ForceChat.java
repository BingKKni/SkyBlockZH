package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.platform.ClientGui;
import io.github.bingkkni.skyzh.text.ChatDisplayCache;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;

/** A bounded display history, independent of chat events and the modifiable vanilla display queue. */
public final class ForceChat {
	private static final int LIMIT = 100;
	private static final int CHARACTER_LIMIT = 131072;
	private static final List<GuiMessage> MESSAGES = new ArrayList<>();
	private static final ChatDisplayCache CACHE = new ChatDisplayCache();
	private static Object level;
	private static Object connection;
	private static int characters;
	private static int packetDepth;
	private static int scroll;
	private static boolean unread;
	private static List<GuiMessage.Line> previousLines = List.of();
	private static boolean layoutDirty = true;
	private static Font layoutFont;
	private static int layoutWidth;
	private static Object layoutCorpus;
	private static boolean layoutTranslated;
	private static boolean layoutName;

	private ForceChat() {}

	/** Original mode changes the language of this view, not its source or ownership. */
	public static boolean ownsDisplay() {
		return SkyZHConfig.get().displayPriority == DisplayPriority.FORCE
			&& SkyZHConfig.get().enabled && HypixelServer.isSkyBlock();
	}

	public static void tick() { collecting(); }

	private static boolean collecting() {
		Minecraft mc = Minecraft.getInstance();
		Object currentConnection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
		boolean collect = SkyZHConfig.get().enabled && HypixelServer.isSkyBlock();
		if (level != mc.level || connection != currentConnection || !collect) {
			clear();
			level = mc.level;
			connection = currentConnection;
		}
		return collect;
	}

	/** Called on the main thread around packet dispatch, before listener/chat events can replace it. */
	public static void server(Component text, Runnable dispatch) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || !collecting()) { dispatch.run(); return; }
		if (!rememberServer(text, ClientGui.guiTicks(mc))) { dispatch.run(); return; }
		packetDepth++;
		try { dispatch.run(); }
		finally { packetDepth--; }
	}

	private static boolean rememberServer(Component text, int tick) {
		return append(new GuiMessage(tick, text, null, GuiMessageSource.SYSTEM_SERVER, null));
	}

	/** Preserve client notices and signed player messages, without translating their text. */
	public static void message(GuiMessage message) {
		if (!collecting()) return;
		if (message.source() == GuiMessageSource.SYSTEM_SERVER && packetDepth > 0) return;
		// An unassociated server-labelled message is not evidence of server provenance.
		if (message.source() == GuiMessageSource.SYSTEM_SERVER) {
			message = new GuiMessage(message.addedTime(), message.content(), message.signature(),
				GuiMessageSource.SYSTEM_CLIENT, message.tag());
		}
		append(message);
	}

	private static boolean append(GuiMessage message) {
		StyledText snapshot = StyledText.of(message.content());
		int length = snapshot.length();
		if (length > 8192) return false;
		message = new GuiMessage(message.addedTime(), snapshot.slice(0, length), message.signature(),
			message.source(), message.tag());
		MESSAGES.addFirst(message);
		layoutDirty = true;
		characters += length;
		trim();
		if (scroll > 0) unread = true;
		return true;
	}

	private static void trim() {
		while (MESSAGES.size() > LIMIT || characters > CHARACTER_LIMIT)
			characters -= MESSAGES.removeLast().content().getString().length();
	}

	/** Only display copies are translated. Line parents retain the source, click styles and signatures. */
	public static List<GuiMessage.Line> lines(Font font, int width) {
		boolean translate = HypixelServer.canTranslate();
		boolean name = SkyZHConfig.get().translateSkyBlockName;
		Object corpus = Translator.index();
		if (!layoutDirty && layoutFont == font && layoutWidth == width && layoutCorpus == corpus
			&& layoutTranslated == translate && layoutName == name) return new ArrayList<>(previousLines);
		List<GuiMessage.Line> result = new ArrayList<>();
		for (GuiMessage message : MESSAGES) {
			boolean server = message.source() == GuiMessageSource.SYSTEM_SERVER;
			List<FormattedCharSequence> lines = CACHE.get(message, font, width, translate && server,
				SkyZHConfig.get().translateSkyBlockName, Translator.index(), () -> {
					Component text = translate && server
						? Translator.translateChatBlock(message.content(), font, width) : message.content();
					return new GuiMessage(message.addedTime(), text, message.signature(), message.source(), message.tag())
						.splitLines(font, width).stream().map(line -> (FormattedCharSequence) new PriorityText.ProtectedText(line)).toList();
				});
			for (int i = lines.size() - 1; i >= 0 && result.size() < LIMIT; i--)
				result.add(new GuiMessage.Line(message, lines.get(i), i == lines.size() - 1));
			if (result.size() == LIMIT) break;
		}
		if (scroll > 0) scroll = anchoredScroll(previousLines, result, scroll);
		previousLines = List.copyOf(result);
		layoutFont = font; layoutWidth = width; layoutCorpus = corpus;
		layoutTranslated = translate; layoutName = name; layoutDirty = false;
		return result;
	}

	/** Keep the same message/line under the reader when new messages arrive or wrapping changes. */
	private static int anchoredScroll(List<GuiMessage.Line> before, List<GuiMessage.Line> after, int position) {
		if (position >= before.size()) return Math.min(position, Math.max(0, after.size() - 1));
		GuiMessage parent = before.get(position).parent();
		int first = position;
		while (first > 0 && before.get(first - 1).parent() == parent) first--;
		for (int i = 0; i < after.size(); i++) {
			if (after.get(i).parent() != parent) continue;
			int last = i;
			while (last + 1 < after.size() && after.get(last + 1).parent() == parent) last++;
			return Math.min(last, i + position - first);
		}
		return Math.min(position, Math.max(0, after.size() - 1));
	}

	public static int scroll() { return scroll; }
	public static boolean unread() { return unread; }
	public static void scrolled(int position, boolean newMessage) { scroll = position; unread = newMessage; }
	public static void resetScroll() { scroll = 0; unread = false; }
	public static void invalidateLayout() { CACHE.clear(); layoutDirty = true; }
	public static void deleted(MessageSignature signature, GuiMessage marker) {
		if (signature == null) return;
		for (int i = 0; i < MESSAGES.size(); i++) {
			if (!signature.equals(MESSAGES.get(i).signature())) continue;
			// Observe vanilla's actual replacement, including its delay. A local deletion notice is not server text.
			MESSAGES.set(i, new GuiMessage(marker.addedTime(), marker.content(), marker.signature(),
				GuiMessageSource.SYSTEM_CLIENT, marker.tag()));
		}
		characters = MESSAGES.stream().mapToInt(message -> message.content().getString().length()).sum();
		trim();
		invalidateLayout();
	}
	public static void clear() {
		MESSAGES.clear(); characters = 0; previousLines = List.of();
		resetScroll(); invalidateLayout();
	}
}
