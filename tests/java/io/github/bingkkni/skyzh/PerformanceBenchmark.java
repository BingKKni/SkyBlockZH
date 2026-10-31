package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.sun.management.ThreadMXBean;
import io.github.bingkkni.skyzh.text.LoreTranslation;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Offline CPU/allocation measurements; not a client FPS measurement or a timing assertion. */
public final class PerformanceBenchmark {
	private static volatile Object sink;

	public static void main(String[] args) throws Exception {
		Map<String, JsonObject> files = TranslationHarness.readCorpus(Path.of(args[0]));
		TranslationIndex index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
		benchmarkSidebarMisses();
		benchmarkSidebarSubmissions();
		measure("dynamic ITEM", 2_000, i -> sink = index.lookup(Surface.ITEM, "Damage: +" + (10_000 + i)));
		measure("unknown ITEM", 2_000, i -> sink = index.lookup(Surface.ITEM, "Unrecorded property " + i));
		measure("unknown LORE", 2_000, i -> sink = index.lookup(Surface.LORE, "An unrecorded sentence " + i));
		List<Component> lore = new ArrayList<>();
		for (int i = 0; i < 20; i++) lore.add(Component.literal("§7Unrecorded property " + i));
		measure("warm lore plan (20 lines)", 500, i -> sink = LoreTranslation.plan(lore));
		measure("changing lore plan (20 lines)", 100, i -> {
			lore.set(0, Component.literal("§7Damage: §c+" + (10_000 + i)));
			sink = LoreTranslation.plan(lore);
		});
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(Component.literal("§9Mithril Drill SX-R226"));
		tooltip.addAll(lore);
		Method present = TooltipTranslator.class.getDeclaredMethod("present", Font.class, List.class,
			boolean.class, String.class, boolean.class);
		present.setAccessible(true);
		measure("original tooltip cache (21 lines)", 2_000, i -> {
			try {
				sink = present.invoke(null, null, tooltip, false, null, true);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		// Vanilla rebuilds these each frame: a new top node (and for name tags a new hover event)
		// over children that stay the same objects.
		List<Component> prefixes = new ArrayList<>();
		for (int i = 0; i < 13; i++) prefixes.add(Component.literal("§7Sidebar row " + i));
		Component owner = Component.literal("§r");
		measure("sidebar frame (13 rebuilt rows)", 20_000, i -> {
			for (Component prefix : prefixes) {
				sink = Translator.translateLine(Component.empty().append(prefix).append(owner), Surface.SCOREBOARD);
			}
		});
		List<Component> names = new ArrayList<>();
		for (int i = 0; i < 60; i++) names.add(Component.literal("§8[§7Lv" + i + "§8] §cZombie §a" + i + "§c❤"));
		measure("name tags frame (60 rebuilt, hover each)", 5_000, i -> {
			for (int n = 0; n < names.size(); n++) {
				Component name = names.get(n);
				String id = "entity-" + n;
				sink = Translator.translateLine(Component.empty().append(name).withStyle(style -> style
					.withHoverEvent(new HoverEvent.ShowText(name)).withInsertion(id)), Surface.HOLOGRAM);
			}
		});
	}

	/** Same server snapshot and draw sequences, with/without the conservative prefilter. */
	private static void benchmarkSidebarMisses() throws Exception {
		Class<?> priority = io.github.bingkkni.skyzh.hook.PriorityText.class;
		var field = priority.getDeclaredField("HUD");
		field.setAccessible(true);
		Map<?, ?> sources = (Map<?, ?>) field.get(null);
		Method put = priority.getDeclaredMethod("put", Map.class, Component.class, Surface.class);
		put.setAccessible(true);
		for (String line : List.of("SKYBLOCK", "Purse: 1,234", "Bits: 123", "⏣ Village"))
			put.invoke(null, sources, Component.literal(line), Surface.SCOREBOARD);
		Method resolve = priority.getDeclaredMethod("resolveKnown", net.minecraft.util.FormattedCharSequence.class,
			boolean.class, boolean.class, boolean.class);
		resolve.setAccessible(true);
		var handle = java.lang.invoke.MethodHandles.lookup().unreflect(resolve);
		try {
			for (String text : List.of("Unrelated menu option", "Unrelated tooltip description ".repeat(32))) {
				var sequence = Component.literal(text).getVisualOrderText();
				for (boolean filtered : new boolean[] {false, true}) {
					measure("HUD miss " + text.length() + " chars " + (filtered ? "filtered" : "full scan"), 100_000, i -> {
						try {
							sink = (net.minecraft.util.FormattedCharSequence) handle.invokeExact(sequence, false, filtered, true);
						} catch (Throwable failure) { throw new AssertionError(failure); }
					});
				}
			}
		} finally { sources.clear(); }
	}

	private static void benchmarkSidebarSubmissions() {
		io.github.bingkkni.skyzh.hook.ForceSidebar.withFrame(false, () ->
			measure("High GUI submission gate", 20_000, i -> sink = io.github.bingkkni.skyzh.hook.ForceSidebar.suppressCustomDraw()), () -> {});
		io.github.bingkkni.skyzh.hook.ForceSidebar.withFrame(true, () -> {
			measure("Force other HUD (20 nested calls)", 20_000, i -> sink = submissionDepth(20));
			measure("Force sidebar (20 nested calls)", 20_000, i -> sink =
				me.owdding.customscoreboard.core.CustomScoreboardRenderer.submit(() -> submissionDepth(20)));
		}, () -> {});
	}

	private static boolean submissionDepth(int depth) {
		return depth == 0 ? io.github.bingkkni.skyzh.hook.ForceSidebar.suppressCustomDraw() : submissionDepth(depth - 1);
	}

	private static void measure(String name, int count, IntConsumer operation) {
		for (int i = 0; i < Math.min(count, 500); i++) operation.accept(-i - 1);
		ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		long bytes = bean.getThreadAllocatedBytes(thread);
		long start = System.nanoTime();
		for (int i = 0; i < count; i++) operation.accept(i);
		long elapsed = System.nanoTime() - start;
		long allocated = bean.getThreadAllocatedBytes(thread) - bytes;
		System.out.printf("BENCH %-38s %10.2f us/op %10.2f KiB/op (%d ops)%n",
			name, elapsed / (count * 1_000.0), allocated / (count * 1024.0), count);
	}
}
