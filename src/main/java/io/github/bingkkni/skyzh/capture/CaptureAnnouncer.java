package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.Feedback;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Tells the player, in chat, what was just captured.
 *
 * <p>Without this the feature is invisible while it runs: files appear on disk and nothing on screen
 * says whether the menu that was just opened produced anything. A line in chat is also the only place
 * the <em>classification</em> can be checked as it happens — seeing "Mining / GUI_Item / Commissions"
 * a second after opening the commission board is how you find out the gameplay was guessed wrong,
 * which is a thing no amount of reading the output afterwards will tell you.
 *
 * <p><b>Why it batches.</b> A SkyBlock menu arrives as one packet holding fifty-four items with a
 * dozen lore lines each, so a message per captured line would put forty of them on screen at once and
 * push everything else out of a chat box that shows ten. Captures are therefore accumulated per
 * output file and sent one second after the last one stops arriving: one message per menu, and still
 * one message per chat line, because a chat line is a batch of one that goes quiet immediately.
 *
 * <p>The message is added as a <em>client</em> system message, which is what it is. It never goes
 * through a packet handler, so the capture cannot see its own announcements — the one way this
 * feature could have fed on itself is closed by where the hooks are rather than by a special case.
 */
public final class CaptureAnnouncer {
	/** How long a group has to stop growing before it is announced. */
	private static final long QUIET_MS = 1000L;

	/** A cap, so a session that captures continuously still gets told rather than waiting forever. */
	private static final long LATEST_MS = 5000L;

	/** One line's worth of captures: everything filed under one name, whichever pile it went into. */
	private record Group(String gameplay, String area, CaptureSurface surface, String name) {}

	/**
	 * The place the message says the capture happened in, when that is not the folder's own name.
	 *
	 * <p>{@code Hub_General} is a folder, not a place: the Hub goes there and so does a private island,
	 * a museum and the guest view of somebody else's island. A message that only names the folder reads
	 * as "you did this in the Hub" wherever it actually happened — which is wrong often enough to be
	 * worth the extra words, and misleading in exactly the way that is hard to catch later.
	 */
	private static String where(String gameplay, String area) {
		return area.isEmpty() || gameplay.equalsIgnoreCase(area.replace(' ', '_')) ? "" : area;
	}

	private static final class Pending {
		private final Map<Classifier.Bucket, Integer> counts = new EnumMap<>(Classifier.Bucket.class);
		private final Map<Classifier.Bucket, Path> files = new EnumMap<>(Classifier.Bucket.class);
		private final long first;
		private long last;

		private Pending(long now) {
			this.first = now;
			this.last = now;
		}

		private int total() {
			int total = 0;

			for (int count : this.counts.values()) {
				total += count;
			}

			return total;
		}
	}

	private static final Map<Group, Pending> PENDING = new LinkedHashMap<>();
	private static long generation;

	private CaptureAnnouncer() {
	}

	/**
	 * Notes one newly written record. Repeats and merges into an existing record are not counted:
	 * "captured 3" has to mean three records appeared, or the number means nothing.
	 */
	public static void record(CaptureWriter.Meta meta, Path file, String area) {
		record(meta, file, area, System.currentTimeMillis());
	}

	/** The same with the clock supplied, so the batching can be exercised without waiting for it. */
	public static synchronized void record(CaptureWriter.Meta meta, Path file, String area, long now) {
		if (!SkyZHConfig.get().captureNotifications) {
			clear();
			return;
		}

		Pending pending = PENDING.computeIfAbsent(
			new Group(meta.gameplay(), where(meta.gameplay(), area), meta.surface(), meta.name()),
			group -> new Pending(now)
		);

		pending.counts.merge(meta.bucket(), 1, Integer::sum);
		pending.files.putIfAbsent(meta.bucket(), file);
		pending.last = now;
	}

	/**
	 * Drops the batches that have not been sent yet.
	 *
	 * <p>Called by {@code /skyzh clear} before the files go. A pending announcement carries a clickable
	 * link to a file that is about to be deleted, and telling somebody about a capture that no longer
	 * exists is worse than saying nothing.
	 */
	public static synchronized void clear() {
		generation++;
		PENDING.clear();
	}

	/** One detached batch and the clear generation under which it was detached. */
	private record Ready(long generation, List<Component> messages) {
	}

	/**
	 * Sends whatever has gone quiet. Called from the capture worker on every poll.
	 *
	 * <p>{@code beforeSend} runs only when there is something to send, and exists for one reason: the
	 * message carries a link to a file that is written on a slower timer, so a player clicking it the
	 * moment it appears would be opening a file that does not exist yet. Flushing first is cheap
	 * precisely because there is something new to write.
	 */
	public static void tick(Runnable beforeSend) {
		Ready ready = takeDue(System.currentTimeMillis());

		if (ready.messages().isEmpty() || !current(ready.generation())) {
			return;
		}

		beforeSend.run();

		if (current(ready.generation())) {
			send(ready.messages(), ready.generation());
		}
	}

	/**
	 * Takes out the batches that are finished, leaving the ones still filling up.
	 *
	 * <p>Separate from sending them so the harness can check what a batch turns into without a client
	 * to draw it — the interesting part is which captures ended up in one message and what that
	 * message says, neither of which needs a chat box.
	 */
	public static List<Component> due(long now) {
		return takeDue(now).messages();
	}

	private static synchronized Ready takeDue(long now) {
		if (!SkyZHConfig.get().captureNotifications) {
			clear();
			return new Ready(generation, List.of());
		}

		Iterator<Map.Entry<Group, Pending>> groups = PENDING.entrySet().iterator();
		List<Component> messages = new ArrayList<>();

		while (groups.hasNext()) {
			Map.Entry<Group, Pending> group = groups.next();
			Pending pending = group.getValue();

			if (now - pending.last >= QUIET_MS || now - pending.first >= LATEST_MS) {
				messages.add(message(group.getKey(), pending));
				groups.remove();
			}
		}

		return new Ready(generation, messages);
	}

	private static synchronized boolean current(long expected) {
		return generation == expected && SkyZHConfig.get().captureNotifications;
	}

	/**
	 * Hands the finished lines to the client thread.
	 *
	 * <p>Everything above runs on the capture worker, and a chat message may only be added on the
	 * thread that draws it. {@code addClientSystemMessage} is deliberately the one used rather than
	 * the server variant: this line did not come from Hypixel and should not be filed as though it
	 * had, which matters for anything reading the chat log afterwards.
	 */
	private static void send(List<Component> messages, long expectedGeneration) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null) {
			return;
		}

		minecraft.execute(() -> {
			if (!current(expectedGeneration) || !HypixelServer.isConnected()) {
				return;
			}

			for (Component message : messages) {
				ClientGui.chat(minecraft, message);
			}
		});
	}

	/**
	 * {@code [SkyZH] 采集 43 条 · Mining / GUI_Item / Commissions  在 Dwarven Mines} and, under it, the
	 * split by pile and a link that opens the file.
	 *
	 * <p>The path is spelled out in the link's tooltip rather than in the line. Which file a capture
	 * landed in is the thing worth checking and the thing too long to read in chat, and a hover is
	 * where a path belongs.
	 */
	private static Component message(Group group, Pending pending) {
		MutableComponent message = Component.empty()
			.append(Component.literal(Feedback.PREFIX).withStyle(ChatFormatting.AQUA))
			.append(Component.literal("采集 "))
			.append(Component.literal(String.valueOf(pending.total())).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" 条 · "))
			.append(Component.literal(group.gameplay() + " / " + group.surface().directory() + " / ")
				.withStyle(ChatFormatting.GRAY))
			.append(Component.literal(group.name()).withStyle(ChatFormatting.YELLOW));

		if (!group.area().isEmpty()) {
			message.append(Component.literal("  在 " + group.area()).withStyle(ChatFormatting.DARK_GRAY));
		}

		message.append(Component.literal("\n         "));

		boolean first = true;

		for (Classifier.Bucket bucket : Classifier.Bucket.values()) {
			Integer count = pending.counts.get(bucket);

			if (count == null) {
				continue;
			}

			if (!first) {
				message.append(Component.literal("、").withStyle(ChatFormatting.GRAY));
			}

			message.append(Component.literal(label(bucket) + " " + count).withStyle(ChatFormatting.GRAY));
			first = false;
		}

		// One link per pile, named only when a batch produced more than one — which happens when a menu
		// holds lines nobody has translated alongside lines a record answered for and left half English.
		boolean several = pending.files.size() > 1;

		for (Map.Entry<Classifier.Bucket, Path> file : pending.files.entrySet()) {
			message.append(Component.literal("  "));
			message.append(link(several ? "[打开" + label(file.getKey()) + "]" : "[打开文件]", file.getValue()));
		}

		return message;
	}

	private static String label(Classifier.Bucket bucket) {
		return switch (bucket) {
			case MIXED -> "中英混杂";
			case COLOUR -> "颜色失真";
			case UNTRANSLATED -> "未翻译";
		};
	}

	private static Component link(String text, Path file) {
		Style style = Style.EMPTY
			.withColor(ChatFormatting.BLUE)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenFile(file))
			.withHoverEvent(new HoverEvent.ShowText(
				Component.literal("点击用系统默认程序打开\n").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(file.toString()).withStyle(ChatFormatting.WHITE))
			));

		return Component.literal(text).setStyle(style);
	}
}
