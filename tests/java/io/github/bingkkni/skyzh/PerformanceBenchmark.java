package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.sun.management.ThreadMXBean;
import io.github.bingkkni.skyzh.text.LoreTranslation;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Offline CPU/allocation measurements; not a client FPS measurement or a timing assertion. */
public final class PerformanceBenchmark {
	private static volatile Object sink;

	public static void main(String[] args) throws Exception {
		Map<String, JsonObject> files = TranslationHarness.readCorpus(Path.of(args[0]));
		TranslationIndex index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
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
