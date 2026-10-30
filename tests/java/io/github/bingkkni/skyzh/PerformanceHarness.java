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
				String sample = sample(entry, references);
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
		checkCacheReload(index);
		checkDisplayShapes(index, references);
		checkShapes();
		checkTooltipRecent();
		checkGlyphs();
		checkCapture();
		System.out.println("Performance equivalence checks=" + comparisons + ", failures=0");
	}

	private static String sample(TranslationEntry entry, Map<String, JsonObject> references) {
		JsonObject source = references.get(entry.sourceFile() + "#" + entry.id());
		if (source.has("ref")) source = references.get(source.get("ref").getAsString());
		return TranslationHarness.fillPlaceholders(entry.template(), source);
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
		var shared = Component.literal("shared hover");
		Component inheriting = Component.empty().append("a").append(Component.literal("b").withStyle(style -> style.withColor(0x111111)))
			.withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(shared)));
		StyledText inherited = StyledText.of(inheriting);
		shared.append(" changed");
		check(inherited.styleAt(0).getColor() == null && inherited.styleAt(1).getColor().getValue() == 0x111111
			&& hoverText(inherited.styleAt(0)).equals("shared hover") && hoverText(inherited.styleAt(1)).equals("shared hover")
			&& !inherited.equals(StyledText.of(inheriting)), "every run snapshots the hover event it inherited");
	}

	private static String hoverText(Style style) {
		return ((net.minecraft.network.chat.HoverEvent.ShowText) style.getHoverEvent()).value().getString();
	}

	/** Every display result must equal the uncached translation, and a rebuilt equal frame must reuse it. */
	private static void checkDisplayShapes(TranslationIndex index, Map<String, JsonObject> references) {
		check(SkyZHConfig.get().enabled && !HoldOriginal.active(), "display equivalence requires translation enabled");
		for (Surface surface : List.of(Surface.SCOREBOARD, Surface.TABLIST, Surface.ACTION_BAR, Surface.BOSS_BAR,
			Surface.MISC, Surface.HOLOGRAM, Surface.GUI_TITLE)) {
			for (TranslationEntry entry : index.entries(surface)) {
				String sample = sample(entry, references);
				int half = sample.length() / 2;
				displayed(surface, () -> Component.literal(sample));
				// Split across separately styled children, the way a sidebar row's prefix and suffix arrive.
				// An insertion keeps the halves distinct runs without a colour change warning about the record.
				displayed(surface, () -> Component.empty().append(sample.substring(0, half))
					.append(Component.literal(sample.substring(half)).withStyle(style -> style.withInsertion("harness"))));
			}
		}
	}

	private static void displayed(Surface surface, java.util.function.Supplier<Component> frame) {
		Component reference = io.github.bingkkni.skyzh.text.Translator.translateAvailable(frame.get(), surface).padded();
		Component source = frame.get();
		Component first = io.github.bingkkni.skyzh.text.Translator.translateLine(source, surface);
		Component again = frame.get();
		Component second = io.github.bingkkni.skyzh.text.Translator.translateLine(again, surface);
		boolean unchanged = StyledText.of(reference).equals(StyledText.of(source));
		check(StyledText.of(first).equals(StyledText.of(reference)), "display equals uncached translation: " + source.getString());
		check(unchanged ? first == source && second == again : second == first,
			"a rebuilt equal frame reuses its display result: " + source.getString());
	}

	/** Recorded shapes may only answer for a tree that would flatten to the same key. */
	private static void checkShapes() throws Exception {
		int[] calls = {0};
		java.util.function.Function<StyledText, Component> render = text -> {
			calls[0]++;
			return Component.literal("形状 ").append(text.slice(0, text.length()));
		};
		var hover = Component.literal("hover");
		var name = Component.literal("Zombie").withStyle(style -> style.withColor(0x55FF55));
		// Vanilla's name tag: a new top node and hover event each frame, over the same children.
		java.util.function.Supplier<Component> frame = () -> Component.empty().append(Component.literal("[Lv1] ")).append(name)
			.withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(hover)).withInsertion("id"));
		Component first = DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.MOB, render);
		for (int i = 0; i < 3; i++) {
			check(DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.MOB, render) == first
				&& calls[0] == 1, "rebuilt frames reuse the recorded result");
		}
		hover.append(" edited");
		Component edited = DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.MOB, render);
		check(calls[0] == 2 && hoverText(StyledText.of(edited).styleAt(3)).equals("hover edited"),
			"a hover edited in place is not served from the old shape");
		name.withStyle(style -> style.withColor(0xFF5555));
		Component recoloured = DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.MOB, render);
		check(calls[0] == 3 && StyledText.of(recoloured).styleAt(9).getColor().getValue() == 0xFF5555,
			"a child restyled in place is not served from the old shape");
		name.append("!");
		Component extended = DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.MOB, render);
		check(calls[0] == 4 && extended.getString().endsWith("Zombie!"), "a child given a sibling is not served from the old shape");
		DisplayTextCache.get(frame.get(), Surface.HOLOGRAM, DisplayTextCache.Mode.TIMER, render);
		DisplayTextCache.get(frame.get(), Surface.SCOREBOARD, DisplayTextCache.Mode.MOB, render);
		check(calls[0] == 6, "surface and mode stay part of a recorded shape");
		Component translatable = Component.translatable("skyzh.harness.unknown.key");
		Component keyed = DisplayTextCache.get(translatable, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(DisplayTextCache.get(Component.translatable("skyzh.harness.unknown.key"), Surface.SCOREBOARD,
			DisplayTextCache.Mode.LINE, render) == keyed && calls[0] == 7, "non-literal trees still use the flattened key");
		var hash = Class.forName("io.github.bingkkni.skyzh.text.TextShape").getDeclaredMethod("hash", Component.class);
		hash.setAccessible(true);
		var deep = Component.literal("deep");
		for (int i = 0; i < 70; i++) deep.append(Component.literal(" " + i));
		check((long) hash.invoke(null, deep) == -1 && (long) hash.invoke(null, translatable) == -1
			&& (long) hash.invoke(null, Component.empty().append("x")) == (long) hash.invoke(null, Component.empty().append("x")),
			"shape hash eligibility and stability");
		Component deepFirst = DisplayTextCache.get(deep, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		check(DisplayTextCache.get(deep.copy(), Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render) == deepFirst
			&& calls[0] == 8, "oversized trees still use the flattened key");
		// Overflow clears the recorded shapes; answers must stay those of the flattened key.
		for (int i = 0; i < 1_500; i++) {
			Component row = Component.literal("Row " + i);
			Component shown = DisplayTextCache.get(row, Surface.SCOREBOARD, DisplayTextCache.Mode.ROW, render);
			check(shown.getString().equals("形状 Row " + i)
				&& DisplayTextCache.get(Component.literal("Row " + i), Surface.SCOREBOARD, DisplayTextCache.Mode.ROW, render) == shown,
				"shape table overflow keeps answers correct");
		}
	}

	/** A remembered tooltip must never answer for lines that changed in place. */
	@SuppressWarnings("unchecked")
	private static void checkTooltipRecent() throws Exception {
		var present = io.github.bingkkni.skyzh.text.TooltipTranslator.class.getDeclaredMethod("present",
			net.minecraft.client.gui.Font.class, List.class, boolean.class, String.class, boolean.class);
		present.setAccessible(true);
		java.util.function.IntFunction<List<Component>> tooltip = item -> List.of(
			Component.literal("§6Harness Item " + item), Component.literal("§7Line one"),
			Component.empty().append(Component.literal("§7Line two")).withStyle(style -> style.withItalic(true)));
		List<Component> first = (List<Component>) present.invoke(null, null, tooltip.apply(1), false, "X", true);
		check(present.invoke(null, null, tooltip.apply(1), false, "X", true) == first, "rebuilt tooltip lines reuse the list");
		List<Component> other = (List<Component>) present.invoke(null, null, tooltip.apply(2), false, "X", true);
		check(other != first && present.invoke(null, null, tooltip.apply(1), false, "X", true) == first
			&& present.invoke(null, null, tooltip.apply(2), false, "X", true) == other, "alternating tooltips both stay recognised");
		check(present.invoke(null, null, tooltip.apply(1), false, "Y", true) != first, "hint key stays part of the match");
		List<Component> edited = tooltip.apply(1);
		((net.minecraft.network.chat.MutableComponent) edited.get(1)).append(" edited");
		List<Component> reread = (List<Component>) present.invoke(null, null, edited, false, "X", true);
		check(reread != first && StyledText.of(reread.get(1)).plain().equals("Line one edited"),
			"a line edited in place is re-presented");
		List<Component> keyed = List.of(Component.literal("§6Harness Item 3"), Component.translatable("skyzh.harness.lore"));
		List<Component> translated = (List<Component>) present.invoke(null, null, keyed, false, "X", true);
		check(present.invoke(null, null, List.of(Component.literal("§6Harness Item 3"),
			Component.translatable("skyzh.harness.lore")), false, "X", true) == translated
			&& translated.get(1).getString().equals("skyzh.harness.lore"), "non-literal tooltip lines use the flattened key");
	}

	/** The array table and one-pass restore against the previous map lookups and per-character scans. */
	private static void checkGlyphs() {
		Map<Character, Character> symbols = new HashMap<>();
		for (char c = (char) 0xE000; c < 0xE100; c++) {
			char folded = io.github.bingkkni.skyzh.text.Glyphs.canonical(String.valueOf(c)).charAt(0);
			if (folded != c) symbols.put(c, folded);
		}
		check(symbols.size() == 31 && symbols.get((char) 0xE010) == '❤' && symbols.get((char) 0xE067) == '⏣',
			"glyph table keeps every mapping");
		StringBuilder expectedClass = new StringBuilder();
		for (char symbol : new java.util.TreeSet<>(symbols.values())) expectedClass.append(symbol);
		check(io.github.bingkkni.skyzh.text.Glyphs.symbolClass().equals(expectedClass.toString()), "symbol class unchanged");
		// Mapped glyphs, two sharing ☘, an unmapped private-use character, their symbols and ordinary text.
		char[] alphabet = {(char) 0xE010, (char) 0xE051, (char) 0xE053, (char) 0xE067, (char) 0xE005,
			'❤', '☘', '⏣', '✦', 'a', 'b', ' ', '中', '文', '%'};
		java.util.Random random = new java.util.Random(42);
		for (int round = 0; round < 20_000; round++) {
			String chinese = randomText(random, alphabet), source = randomText(random, alphabet);
			check(io.github.bingkkni.skyzh.text.Glyphs.restore(chinese, source).equals(restoreBefore(symbols, chinese, source))
				&& io.github.bingkkni.skyzh.text.Glyphs.canonical(source).equals(canonicalBefore(symbols, source))
				&& io.github.bingkkni.skyzh.text.Glyphs.hasGlyphs(source) == source.chars().anyMatch(c -> symbols.containsKey((char) c)),
				"glyph folding equals the previous implementation: " + chinese + " / " + source);
		}
	}

	private static String randomText(java.util.Random random, char[] alphabet) {
		char[] text = new char[random.nextInt(12)];
		for (int i = 0; i < text.length; i++) text[i] = alphabet[random.nextInt(alphabet.length)];
		return new String(text);
	}

	private static String canonicalBefore(Map<Character, Character> symbols, String text) {
		char[] folded = text.toCharArray();
		for (int i = 0; i < folded.length; i++) folded[i] = symbols.getOrDefault(folded[i], folded[i]);
		return new String(folded);
	}

	private static String restoreBefore(Map<Character, Character> symbols, String chinese, String source) {
		char[] restored = canonicalBefore(symbols, chinese).toCharArray();
		for (int i = 0; i < restored.length; i++) {
			for (char candidate : source.toCharArray()) {
				Character mapped = symbols.get(candidate);
				if (mapped != null && mapped == restored[i]) {
					restored[i] = candidate;
					break;
				}
			}
		}
		return new String(restored);
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

	/** Publish a new corpus during flattening, between the cache's two lookup sections. */
	private static void checkCacheReload(TranslationIndex index) throws Exception {
		TranslationIndex replacement = new TranslationIndex();
		int[] calls = {0};
		java.util.function.Function<StyledText, Component> render = text -> {
			calls[0]++;
			return Component.literal(io.github.bingkkni.skyzh.text.Translator.index() == index ? "加载前" : "加载后");
		};
		DisplayTextCache.get(Component.literal("Cache reload"), Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
		// Different literal seams miss the shape table, but flatten to the already cached key.
		Component split = Component.empty().append("Cache ").append("reload");
		Component source = (Component) java.lang.reflect.Proxy.newProxyInstance(Component.class.getClassLoader(),
			new Class<?>[] {Component.class}, (proxy, method, arguments) -> {
				Object result = method.invoke(split, arguments);
				if (method.getName().equals("visit")) TranslationHarness.installIndex(replacement);
				return result;
			});
		try {
			Component result = DisplayTextCache.get(source, Surface.SCOREBOARD, DisplayTextCache.Mode.LINE, render);
			check(calls[0] == 2 && result.getString().equals("加载后"), "corpus changes during flattening reject old cached results");
		} finally {
			TranslationHarness.installIndex(index);
		}
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
