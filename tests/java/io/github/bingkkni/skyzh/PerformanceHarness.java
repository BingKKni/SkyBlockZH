package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.capture.CaptureStore;
import io.github.bingkkni.skyzh.capture.CaptureSurface;
import io.github.bingkkni.skyzh.capture.LoreSnapshot;
import io.github.bingkkni.skyzh.text.DisplayTextCache;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Differential and lifecycle regressions for performance changes; no timing-based build gates. */
public final class PerformanceHarness {
	private static int comparisons;

	public static void main(String[] args) throws Exception {
		Map<String, JsonObject> files = TranslationHarness.readCorpus(Path.of(args[0]));
		TranslationIndex index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
		Map<String, JsonObject> references = new HashMap<>();
		files.forEach((path, file) -> TranslationHarness.recordsOf(file).forEach(record ->
			references.put(path + "#" + record.get("id").getAsString(), record)));
		for (Surface surface : Surface.values()) {
			List<TranslationEntry> patterns = patterns(index).get(surface);
			for (TranslationEntry entry : index.entries(surface)) {
				JsonObject source = references.get(entry.sourceFile() + "#" + entry.id());
				if (source.has("ref")) source = references.get(source.get("ref").getAsString());
				String sample = TranslationHarness.fillPlaceholders(entry.template(), source);
				compare(index, patterns, surface, sample);
				compare(index, patterns, surface, sample.replaceAll("([A-Za-z]{2,})[sS]\\b", "$1"));
				if (entry.template().contains("%")) {
					compare(index, patterns, surface, sample + " unknown tail");
					compare(index, patterns, surface, "Unrecorded " + sample);
					for (String excluded : entry.excludedTexts()) compare(index, patterns, surface, excluded);
				}
			}
		}
		checkSharedCompilation();
		checkStyles();
		checkCache(index);
		checkCapture();
		System.out.println("Performance equivalence checks=" + comparisons + ", failures=0");
	}

	@SuppressWarnings("unchecked")
	private static Map<Surface, List<TranslationEntry>> patterns(TranslationIndex index) throws Exception {
		var field = TranslationIndex.class.getDeclaredField("patterns");
		field.setAccessible(true);
		return (Map<Surface, List<TranslationEntry>>) field.get(index);
	}

	/** The previous exhaustive scan, with exact precedence and registration-order ties. */
	private static void compare(TranslationIndex index, List<TranslationEntry> entries, Surface surface, String plain) {
		TranslationEntry expected = index.lookupExact(surface, plain);
		if (expected == null) {
			for (TranslationEntry entry : entries) {
				if ((expected == null || entry.specificity() > expected.specificity()) && entry.match(plain) != null) {
					expected = entry;
				}
			}
		}
		check(index.lookup(surface, plain) == expected, "winner differs: " + surface + " " + plain);
		var actual = index.match(surface, plain);
		var reference = expected == null ? null : expected.match(plain);
		check((reference == null) == (actual == null), "matcher presence differs: " + plain);
		if (reference != null) {
			check(actual.entry() == expected, "matcher entry differs");
			for (int group = 0; group <= reference.groupCount(); group++) {
				check(actual.matcher().start(group) == reference.start(group)
					&& actual.matcher().end(group) == reference.end(group), "capture range differs: " + plain);
			}
		}
	}

	private static void checkSharedCompilation() throws Exception {
		JsonObject shared = com.google.gson.JsonParser.parseString("""
			{"lines":[{"id":"energy","text":"Energy: %s","zh":"能量: %s",
			"placeholders":[{"token":"%s","type":"number"}]}]}
			""").getAsJsonObject();
		JsonObject item = com.google.gson.JsonParser.parseString("""
			{"lines":[{"id":"item_energy","ref":"_shared/Fragments/Energy.json#energy"}]}
			""").getAsJsonObject();
		JsonObject chat = com.google.gson.JsonParser.parseString("""
			{"lines":[{"id":"chat_energy","ref":"_shared/Fragments/Energy.json#energy"}]}
			""").getAsJsonObject();
		TranslationIndex fixture = TranslationLoader.compile(Map.of(
			"_shared/Fragments/Energy.json", shared, "A/GUI_Item/Energy.json", item,
			"B/ChatMessage/Energy.json", chat));
		TranslationEntry left = fixture.lookup(Surface.ITEM, "Energy: 12");
		TranslationEntry right = fixture.lookup(Surface.CHAT, "Energy: 12");
		var compiled = TranslationEntry.class.getDeclaredField("pattern");
		compiled.setAccessible(true);
		check(left != right && compiled.get(left) == compiled.get(right), "equivalent references share compiled regex");
		check(left.id().equals("item_energy") && right.id().equals("chat_energy")
			&& left.sourceFile().equals("A/GUI_Item/Energy.json")
			&& right.sourceFile().equals("B/ChatMessage/Energy.json"), "references retain independent identity and surface");
	}

	private static void checkStyles() {
		Component source = Component.literal("§aAlpha§qBeta§r中😀")
			.withStyle(Style.EMPTY.withBold(true).withInsertion("test"))
			.append(Component.literal("§cGamma§").withStyle(Style.EMPTY.withItalic(true)));
		StyledText styled = StyledText.of(source);
		check(styled.plain().equals("AlphaBeta中😀Gamma"), "legacy marker parsing");
		StyledText rebuilt = StyledText.of(styled.slice(0, styled.length()));
		check(styled.equals(rebuilt) && styled.hashCode() == rebuilt.hashCode(), "run snapshot roundtrip");
		for (int from = 0; from <= styled.length(); from++) {
			for (int to = from; to <= styled.length(); to++) {
				StyledText view = styled.sub(from, to);
				StyledText copy = StyledText.of(styled.slice(from, to));
				check(view.equals(copy) && view.hashCode() == copy.hashCode(), "range view equality/hash");
				for (int i = 0; i < view.length(); i++) {
					check(view.styleAt(i).equals(styled.styleAt(from + i)), "view style offsets");
				}
			}
		}
		Component split = Component.literal("ab").append(Component.literal("cd"));
		var hover = Component.literal("hover before");
		Component interactive = Component.literal("button").withStyle(style -> style.withHoverEvent(
			new net.minecraft.network.chat.HoverEvent.ShowText(hover)));
		StyledText frozen = StyledText.of(interactive);
		int hash = frozen.hashCode();
		hover.append(" after");
		check(frozen.hashCode() == hash && !frozen.equals(StyledText.of(interactive)), "mutable hover text is snapshotted");
		var cyclic = Component.literal("cycle").append(Component.literal(" tail").withStyle(style -> style.withColor(0x123456)));
		cyclic.withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(cyclic)));
		StyledText bounded = StyledText.of(cyclic);
		check(bounded.equals(StyledText.of(cyclic)) && bounded.hashCode() == StyledText.of(cyclic).hashCode(),
			"cyclic hover graphs terminate and produce stable snapshots");
		Component hoverText = ((net.minecraft.network.chat.HoverEvent.ShowText) bounded.styleAt(0).getHoverEvent()).value();
		check(hoverText.getString().equals("cycle tail"), "cyclic hover retains its visible siblings");
		Component recursiveText = ((net.minecraft.network.chat.HoverEvent.ShowText) hoverText.getStyle().getHoverEvent()).value();
		check(recursiveText.getString().equals("cycle tail")
			&& StyledText.of(recursiveText).styleAt(6).getColor().getValue() == 0x123456,
			"truncated hover edge retains all visible text and sibling colour");
		check(StyledText.of(split).equals(StyledText.of(Component.literal("abcd"))), "component seams are not style seams");
	}

	private static void checkCache(TranslationIndex index) {
		int[] calls = {0};
		var source = Component.literal("Cache input").withStyle(style -> style.withColor(0x112233));
		java.util.function.Function<StyledText, Component> render = text -> {
			calls[0]++;
			return Component.literal("缓存译文").setStyle(text.styleAt(0));
		};
		Component first = DisplayTextCache.get(source, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(DisplayTextCache.get(source.copy(), Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render) == first
			&& calls[0] == 1, "equal immutable styles reuse output");
		source.withStyle(style -> style.withColor(0x445566));
		Component recoloured = DisplayTextCache.get(source, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(calls[0] == 2 && StyledText.of(recoloured).styleAt(0).getColor().getValue() == 0x445566,
			"colour-only changes cannot freeze shimmer");
		check(StyledText.of(first).styleAt(0).getColor().getValue() == 0x112233, "source mutation cannot change stored output");
		Component a = Component.literal("collision").setStyle(Style.EMPTY.withInsertion("Aa"));
		Component b = Component.literal("collision").setStyle(Style.EMPTY.withInsertion("BB"));
		check(StyledText.of(a).hashCode() == StyledText.of(b).hashCode(), "deliberate hash collision fixture");
		DisplayTextCache.get(a, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		DisplayTextCache.get(b, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(calls[0] == 4, "full style equality resolves hash collisions");
		SkyZHConfig.bumpGeneration();
		DisplayTextCache.get(b, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(calls[0] == 5, "resource/config generation invalidation");
		LoreSnapshot snapshot = LoreSnapshot.of("Menu", 1, 2, a, List.of(source));
		LoreSnapshot reopened = LoreSnapshot.of("Menu", 3, 4, a.copy(), List.of(source.copy()));
		check(snapshot.lines().getFirst() == reopened.lines().getFirst() && reopened.slot() == 4
			&& reopened.containerId() == 3 && snapshot.signature().equals(reopened.signature()), "packet snapshot reuse retains new position");
	}

	private static void checkCapture() throws Exception {
		Path root = Files.createTempDirectory("skyzh-performance-capture");
		try {
			CaptureStore.root(root);
			CaptureStore.clear(root);
			for (int i = 0; i < 17_000; i++) {
				CaptureStore.accept(sighting(i, "Menu", new CaptureStore.Line(
					StyledText.of(Component.literal("Unrecorded charge: " + i)))));
			}
			for (String fieldName : List.of("BY_KEY", "SHARED")) {
				var field = CaptureStore.class.getDeclaredField(fieldName);
				field.setAccessible(true);
				check(((Map<?, ?>) field.get(null)).size() <= 16_384, fieldName + " is bounded");
			}
			// Both aliases are evicted. The complete fallback must restore the count without a new file.
			CaptureStore.accept(sighting(200, "AnotherMenu", new CaptureStore.Repeat(new CaptureStore.Line(
				StyledText.of(Component.literal("Unrecorded charge: 200"))))));
			CaptureStore.flush();
			Path output = root.resolve("untranslated/Mining/GUI_Item/Menu.json");
			check(Files.exists(output) && !Files.exists(root.resolve("untranslated/Mining/GUI_Item/AnotherMenu.json")),
				"eviction preserves cross-menu deduplication");
			JsonObject file = com.google.gson.JsonParser.parseString(Files.readString(output)).getAsJsonObject();
			check(TranslationHarness.recordsOf(file).size() == 1, "dynamic values still merge into one record");
			JsonObject saved = TranslationHarness.recordsOf(file).getFirst().getAsJsonObject("_capture");
			check(saved.get("count").getAsInt() == 17_001, "evicted aliases retain every observation count");
			check(saved.getAsJsonObject("observed").getAsJsonArray("%1$s").size() == 40,
				"bounded alias recovery retains the original observed examples");
			// A held store lock must not hold up the close/disconnect call.
			CountDownLatch called = new CountDownLatch(1);
			synchronized (CaptureStore.class) {
				Thread.ofPlatform().daemon().start(() -> { CaptureStore.finishAsync(false); called.countDown(); });
				check(called.await(5, TimeUnit.SECONDS), "async finish must not acquire the worker/store lock");
			}
		} finally {
			CaptureStore.clear(root);
			Files.deleteIfExists(root);
		}
	}

	private static CaptureStore.Sighting sighting(int i, String menu, CaptureStore.Observation observation) {
		return new CaptureStore.Sighting(CaptureSurface.GUI_ITEM, "charge-" + i, observation,
			"Mining", "Dwarven Mines", menu, "Menu Lore", i);
	}

	private static void check(boolean condition, String message) {
		comparisons++;
		if (!condition) throw new AssertionError(message);
	}
}
