package io.github.bingkkni.skyzh.compat;

import io.github.bingkkni.skyzh.hook.PriorityText;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Translator;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Display-only chat adapters. Packet contents, history and third-party classes remain untouched. */
public final class ChatCompatibility {
	private static final Pattern COUNTER = Pattern.compile(" \\((?:[2-9]|[1-9][0-9]{1,9})\\)");
	private static final ThreadLocal<Component> SERVER = new ThreadLocal<>();
	private static final ThreadLocal<View> VIEW = new ThreadLocal<>();
	private record View(Component source, Supplier<Component> translation) {}

	/** Implemented on vanilla GuiMessage; the sidecar is shared by messages from one packet. */
	public interface Origin {
		Component skyzh$serverOriginal();
	}

	private static final class Mods {
		static final boolean BETTER_CHAT = FabricLoader.getInstance().isModLoaded("better_hypixel_chat");
		static final boolean COUNTERS = FabricLoader.getInstance().isModLoaded("compacting")
			|| FabricLoader.getInstance().isModLoaded("enhanced_chat")
			|| FabricLoader.getInstance().isModLoaded("stfu");
	}

	private static final class BetterChat {
		static final Method ACTIVE;
		static final Style FONT;
		static {
			Method active = null;
			Style font = null;
			if (Mods.BETTER_CHAT) {
				try {
					active = Class.forName("io.github.viciscat.bhc.BetterHypixelChatMod").getMethod("isOnHypixel");
					font = (Style) Class.forName("io.github.viciscat.bhc.ChatConstants").getField("VANILLA_FONT_STYLE").get(null);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { active = null; }
			}
			ACTIVE = active;
			FONT = font;
		}
	}

	private ChatCompatibility() {}
	public static boolean usesVisualChat() {
		if (BetterChat.ACTIVE == null) return false;
		try { return Boolean.TRUE.equals(BetterChat.ACTIVE.invoke(null)); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
	}

	/** BHC detects server alignment using its vanilla font at 320px, then lays out at actual width. */
	public static Component translateVisual(Component text, Font font) {
		return Translator.translateChatBlock(text, 320, component -> PriorityText.measureWidth(
			() -> font.width(Component.empty().setStyle(BetterChat.FONT).append(component))));
	}
	public static boolean hasCounters() { return Mods.COUNTERS; }
	public static Component serverOriginal() { return SERVER.get(); }

	/** Called only for verified Hypixel packets on the client thread. No event is cancelled. */
	public static void server(Component text, Runnable dispatch) {
		Component previous = SERVER.get();
		StyledText styled = StyledText.of(text);
		Component snapshot = styled.length() <= 8192 ? styled.slice(0, styled.length()) : null;
		if (snapshot == null) SERVER.remove(); else SERVER.set(snapshot);
		try { dispatch.run(); }
		finally { if (previous == null) SERVER.remove(); else SERVER.set(previous); }
	}

	/** Preserve the actual GuiMessage and line parents while a custom splitter reads visual text. */
	public static void display(Component source, Supplier<Component> translation, Runnable split) {
		View previous = VIEW.get();
		VIEW.set(new View(source, translation));
		try { split.run(); }
		finally { if (previous == null) VIEW.remove(); else VIEW.set(previous); }
	}

	/** Only this exact component's visual read is adapted, and only within the display queue call. */
	public static Component visual(Component source) {
		View view = VIEW.get();
		if (view == null || view.source() != source) return null;
		VIEW.remove();
		try { return view.translation().get(); }
		finally { VIEW.set(view); }
	}

	/**
	 * Recognize an appended counter only with a byte-for-style match to the packet's entire body.
	 * Server-authored parentheses, unknown decorations and altered prose are never stripped.
	 */
	public static Component translate(Component displayed, Component server, boolean counters,
		UnaryOperator<Component> translate) {
		if (!counters || server == null) return translate.apply(displayed);
		StyledText body = StyledText.of(server), visible = StyledText.of(displayed);
		int boundary = body.length();
		if (boundary == 0 || boundary >= visible.length()
			|| !body.equals(StyledText.of(visible.slice(0, boundary)))) return translate.apply(displayed);
		String suffix = visible.plain().substring(boundary);
		if (!COUNTER.matcher(suffix).matches()) return translate.apply(displayed);
		Style gray = Style.EMPTY.withColor(ChatFormatting.GRAY);
		for (int i = boundary; i < visible.length(); i++) {
			if (!gray.equals(visible.styleAt(i))) return translate.apply(displayed);
		}
		Component translated = translate.apply(visible.slice(0, boundary));
		return Component.empty().append(translated).append(visible.slice(boundary, visible.length()));
	}
}
