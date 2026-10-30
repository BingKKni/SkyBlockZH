package io.github.bingkkni.skyzh.hook;

import io.github.bingkkni.skyzh.DisplayPriority;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.HoldOriginal;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;

/** Late display fallback, limited to text observed in server packets or server-owned HUD state. */
public final class PriorityText {
	private static final int LIMIT = 2048;
	private static final Map<String, Source> PACKETS = new LinkedHashMap<>();
	private static final Map<String, Source> HUD = new LinkedHashMap<>();
	private static final Map<String, Boolean> OUTPUT = new LinkedHashMap<>();
	private static Object level;
	private static int generation = -1;
	private static boolean resolving;

	private record Source(Component text, Surface surface) {}

	/** A display-only sequence. Never stored back in a packet, item, scoreboard or chat history. */
	public record ProtectedText(FormattedCharSequence text) implements FormattedCharSequence {
		@Override public boolean accept(FormattedCharSink sink) { return this.text.accept(sink); }
	}

	private PriorityText() {}

	public static boolean active() {
		return SkyZHConfig.get().displayPriority != DisplayPriority.DEFAULT
			&& effective(HypixelServer.isSkyBlock()) != DisplayPriority.DEFAULT;
	}

	public static boolean force() {
		return SkyZHConfig.get().displayPriority == DisplayPriority.FORCE
			&& effective(HypixelServer.isSkyBlock()) == DisplayPriority.FORCE;
	}

	private static DisplayPriority effective(boolean verifiedSkyBlock) {
		SkyZHConfig config = SkyZHConfig.get();
		return verifiedSkyBlock && config.enabled && !HoldOriginal.active() ? config.displayPriority : DisplayPriority.DEFAULT;
	}

	private static boolean collecting(boolean verifiedSkyBlock) {
		SkyZHConfig config = SkyZHConfig.get();
		return verifiedSkyBlock && config.enabled && config.displayPriority != DisplayPriority.DEFAULT;
	}

	public static void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		boolean collect = SkyZHConfig.get().displayPriority != DisplayPriority.DEFAULT && collecting(HypixelServer.isSkyBlock());
		if (level != minecraft.level || !collect) {
			PACKETS.clear();
			HUD.clear();
			OUTPUT.clear();
			level = minecraft.level;
		}
		if (generation != SkyZHConfig.generation()) {
			generation = SkyZHConfig.generation();
			OUTPUT.clear();
		}
		if (!collect || minecraft.level == null) return;
		HUD.clear();
		var scoreboard = minecraft.level.getScoreboard();
		var objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective != null) {
			put(HUD, objective.getDisplayName(), Surface.SCOREBOARD);
			for (var entry : scoreboard.listPlayerScores(objective)) {
				if (!entry.isHidden()) put(HUD,
					PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()), Surface.SCOREBOARD);
			}
		}
		if (minecraft.getConnection() != null) {
			for (var info : minecraft.getConnection().getListedOnlinePlayers()) {
				if (info.getTabListDisplayName() != null) put(HUD, info.getTabListDisplayName(), Surface.TABLIST);
			}
		}
	}

	/** Packet HEAD hooks can also run on the network thread, before vanilla schedules the packet. */
	public static void observe(Component source, Surface surface) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread()
			|| SkyZHConfig.get().displayPriority == DisplayPriority.DEFAULT || !collecting(HypixelServer.isSkyBlock())) return;
		if (level != minecraft.level) {
			PACKETS.clear();
			HUD.clear();
			OUTPUT.clear();
			level = minecraft.level;
		}
		put(PACKETS, source, surface);
	}

	private static void put(Map<String, Source> map, Component source, Surface surface) {
		StyledText styled = StyledText.of(source);
		if (styled.length() > 8192) return;
		int start = 0;
		for (int end = 0; end <= styled.length(); end++) {
			if (end < styled.length() && styled.plain().charAt(end) != '\n') continue;
			if (end > start) {
				Component line = styled.slice(start, end);
				String key = line.getString().strip();
				if (!key.isEmpty()) {
					Source previous = map.get(key);
					map.put(key, new Source(line, previous != null && previous.surface() != surface ? null : surface));
					if (map.size() > LIMIT) map.remove(map.keySet().iterator().next());
				}
			}
			start = end + 1;
		}
	}

	/** Null means this is not a known, translatable server line. No all-surfaces corpus guessing. */
	public static FormattedCharSequence resolve(FormattedCharSequence input) {
		if (resolving || !active()) return null;
		return resolveActive(input, force());
	}

	private static FormattedCharSequence resolveActive(FormattedCharSequence input, boolean forced) {
		if (resolving) return null;
		if (input instanceof ProtectedText) return input;
		resolving = true;
		try {
			return resolveKnown(input, forced);
		} finally {
			resolving = false;
		}
	}

	private static FormattedCharSequence resolveKnown(FormattedCharSequence input, boolean forced) {
		// Most GUI labels are not server text. Do not allocate components/styles for those misses.
		StringBuilder plain = new StringBuilder();
		input.accept((index, style, codePoint) -> {
			if (plain.length() > 8192) return false;
			plain.appendCodePoint(codePoint);
			return true;
		});
		if (plain.length() > 8192) return null;
		String key = plain.toString().strip();
		if (forced && OUTPUT.containsKey(key)) return new ProtectedText(input);
		Source source = HUD.get(key);
		Source packet = PACKETS.get(key);
		if (source != null && packet != null && source.surface() != packet.surface()) return null;
		if (source == null) source = packet;
		if (source == null || source.surface() == null) return null;
		Component original = forced ? source.text() : component(input);
		if (original == null) return null;
		Component translated = switch (source.surface()) {
			case TABLIST -> Translator.translateRow(original, Surface.TABLIST);
			case ACTION_BAR -> Translator.translateWidgets(original, Surface.ACTION_BAR);
			default -> Translator.translateLine(original, source.surface());
		};
		if (StyledText.of(original).equals(StyledText.of(translated))) return null;
		return new ProtectedText(translated.getVisualOrderText());
	}

	/** Preserve style runs and Unicode code points when a custom HUD submits ordered text. */
	public static Component component(FormattedCharSequence input) {
		MutableComponent result = Component.empty();
		StringBuilder run = new StringBuilder();
		net.minecraft.network.chat.Style[] previous = {null};
		int[] count = {0};
		input.accept((index, style, codePoint) -> {
			if (++count[0] > 8192) return false;
			if (!style.equals(previous[0]) && !run.isEmpty()) {
				result.append(Component.literal(run.toString()).setStyle(previous[0]));
				run.setLength(0);
			}
			previous[0] = style;
			run.appendCodePoint(codePoint);
			return true;
		});
		// Language's fallback sequence and client bidi sequences disagree on the completion return value.
		if (count[0] > 8192) return null;
		if (!run.isEmpty()) result.append(Component.literal(run.toString()).setStyle(previous[0]));
		return result;
	}

	/** Engine layout must measure original English as English, even when a custom HUD uses late translation. */
	public static int originalWidth(net.minecraft.client.gui.Font font, Component text) {
		return measureWidth(() -> font.width(text));
	}

	/** Keep the guard through Font.width: other mods may wrap and erase ProtectedText's type. */
	public static int measureWidth(java.util.function.IntSupplier measurement) {
		boolean previous = resolving;
		resolving = true;
		try { return measurement.getAsInt(); }
		finally { resolving = previous; }
	}

	/** {@code plain} is the text's stripped plain spelling, which the display cache keeps beside it. */
	public static Component remember(Component text, String plain) {
		if (force()) {
			OUTPUT.put(plain, true);
			if (OUTPUT.size() > LIMIT) OUTPUT.remove(OUTPUT.keySet().iterator().next());
		}
		return text;
	}

	public static FormattedCharSequence protect(Component text) {
		return force() ? new ProtectedText(text.getVisualOrderText()) : text.getVisualOrderText();
	}
}
