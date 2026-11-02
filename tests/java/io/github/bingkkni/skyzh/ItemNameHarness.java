package io.github.bingkkni.skyzh;

import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;

/** Exercises the item-name display policy without changing English matching or corpus coverage. */
public final class ItemNameHarness {
	private static int checks;
	static TranslationIndex caseIndex;
	static TranslationIndex baselineIndex;
	private static void check(boolean success, String message) {
		checks++;
		if (!success) throw new AssertionError(message);
	}
	/** Replayed against both authored and shipped indexes by MinifiedCorpusCheck too. */
	static void runCases(Path resources) throws Exception {
		var config = SkyZHConfig.get();
		boolean previous = config.translateItemNames;
		List<String> failures = new ArrayList<>();
		try {
			for (String name : List.of("chat", "gui", "world", "integration", "review")) {
				Path path = resources.resolve("item-name-" + name + "-cases.json");
				for (var element : JsonParser.parseString(Files.readString(path)).getAsJsonArray()) {
					var row = element.getAsJsonObject();
					for (boolean enabled : List.of(true, false)) {
						config.translateItemNames = enabled;
						SkyZHConfig.bumpGeneration();
						Component rendered = drawCase(row);
						if (enabled && baselineIndex != null) {
							TranslationIndex current = caseIndex;
							TranslationHarness.installIndex(baselineIndex);
							caseIndex = baselineIndex;
							Component baseline;
							try { baseline = drawCase(row); }
							finally { caseIndex = current; TranslationHarness.installIndex(current); }
							check(TranslationHarness.legacy(baseline).equals(TranslationHarness.legacy(rendered)),
								"开启输出/颜色保持注解前一致: " + row.get("id"));
						}
						if (row.has("baseline")) {
							if (!enabled) for (var item : row.getAsJsonArray("disabled_contains"))
								check(rendered.getString().contains(item.getAsString()), "关闭保留完整名称 " + item + ": " + rendered.getString());
							if (!enabled && row.has("disabled_counts")) for (var item : row.getAsJsonObject("disabled_counts").entrySet())
								check(rendered.getString().split(java.util.regex.Pattern.quote(item.getKey()), -1).length - 1 == item.getValue().getAsInt(),
									"跨行完整名称仅输出指定次数 " + item.getKey());
							continue;
						}
						String expected = row.get(enabled ? "enabled" : "disabled").getAsString();
						String actual = expected.contains("§") ? TranslationHarness.legacy(rendered) : rendered.getString();
						checks++;
						if (!expected.equals(actual)) failures.add(name + "/" + row.get("id").getAsString() + "/" + enabled
							+ " expected [" + expected + "] actual [" + actual + "]");
					}
				}
			}
		} finally { config.translateItemNames = previous; SkyZHConfig.bumpGeneration(); }
		if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
	}

	private static Component drawCase(com.google.gson.JsonObject row) {
		Surface surface = Surface.valueOf(row.get("surface").getAsString());
		if (row.has("lines")) {
			List<TranslationEntry.Matched> matches = new ArrayList<>();
			for (var line : row.getAsJsonArray("lines")) {
				StyledText source = StyledText.of(Component.literal(line.getAsString()));
				Translator.Located located = Translator.locate(source, Surface.ITEM);
				check(located.matched(), "continuation sample must match: " + line);
				matches.add(new TranslationEntry.Matched(located.entry(), located.core(), located.match()));
			}
			return TranslationEntry.renderJoined(matches, caseIndex.terms());
		}
		Component source = Component.literal(row.get("input").getAsString());
		if (row.has("entry")) {
			Translator.Located located = Translator.locate(source, surface);
			check(located.matched() && located.entry().id().equals(row.get("entry").getAsString()),
				"真实命中指定记录: " + row.get("id"));
		}
		if (row.has("baseline")) check(Translator.locate(StyledText.of(source), surface).matched(),
			"整合样本必须实际命中记录: " + row.get("id"));
		if (surface == Surface.ITEM) return TooltipTranslator.translateItemName(source).padded();
		if (surface == Surface.SIGN || surface == Surface.BOOK) return DocumentTranslation.available(source, surface);
		return Probe.draw(source, surface);
	}

	static TranslationIndex baseline(Map<String, com.google.gson.JsonObject> files) {
		Map<String, com.google.gson.JsonObject> clean = new java.util.HashMap<>();
		files.forEach((path, value) -> { var copy = value.deepCopy(); removeItemMetadata(copy); clean.put(path, copy); });
		return TranslationLoader.compile(clean);
	}

	private static void removeItemMetadata(com.google.gson.JsonElement element) {
		if (element.isJsonObject()) {
			var object = element.getAsJsonObject();
			object.remove("item_refs"); object.remove("item_name"); object.remove("item");
			for (var value : object.entrySet()) removeItemMetadata(value.getValue());
		} else if (element.isJsonArray()) for (var value : element.getAsJsonArray()) removeItemMetadata(value);
	}

	private static void checkGameplayAndCapture() {
		var config = SkyZHConfig.get();
		boolean previousItems = config.translateItemNames, previousGameplay = config.translateSkyBlockName;
		try {
			config.translateItemNames = false;
			for (boolean gameplay : List.of(true, false)) {
				config.translateSkyBlockName = gameplay;
				Component dynamic = Translator.translateLine(Component.literal("SkyBlock item: SkyBlock Citizen Timecharm"), Surface.CHAT);
				check(dynamic.getString().equals((gameplay ? "空岛物品" : "SkyBlock 物品") + ": SkyBlock Citizen Timecharm"),
					"玩法名选项只影响物品名之外的内容: " + dynamic.getString());
				Component fixed = Translator.translateLine(Component.literal("Welcome to SkyBlock with SkyBlock Citizen Timecharm."), Surface.CHAT);
				check(fixed.getString().equals((gameplay ? "在空岛生存中使用 " : "在 SkyBlock 中使用 ")
					+ "SkyBlock Citizen Timecharm。"), "固定物品范围免于玩法名后处理: " + fixed.getString());
			}
			config.translateSkyBlockName = true;
			var unknownItem = TranslationEntry.compile(new TranslationEntry.Definition("unknown_item", "fixture",
				List.of(new TranslationEntry.Segment("SkyBlock item: %s", "SkyBlock 物品: %s")),
				Map.of(1, new TranslationEntry.Argument("item_name", "SkyBlock")), TranslationEntry.Options.DEFAULT));
			StyledText unknownSource = StyledText.of(Component.literal("SkyBlock item: SkyBlock"));
			check(unknownItem.renderDisplay(unknownSource, unknownItem.match(unknownSource.plain()), Translator.index().terms())
				.getString().equals("空岛物品: SkyBlock"), "同词出现时只保护确实属于物品的范围");
			Component source = Component.literal(" ".repeat(140) + "This is the SkyBlock Citizen Timecharm!");
			var offPlan = io.github.bingkkni.skyzh.capture.TextCapture.inspectChat(source, 320, c -> c.getString().length());
			var off = io.github.bingkkni.skyzh.capture.TranslationDiagnostics.chat(offPlan);
			String offDisplay = ChatTranslation.plan(source, 320, c -> c.getString().length()).text().getString();
			config.translateItemNames = true;
			var onPlan = io.github.bingkkni.skyzh.capture.TextCapture.inspectChat(source, 320, c -> c.getString().length());
			var on = io.github.bingkkni.skyzh.capture.TranslationDiagnostics.chat(onPlan);
			check(!on.isEmpty() && on.size() == off.size(), "真实聊天采集规划产生布局诊断");
			for (int i = 0; i < on.size(); i++) check(on.get(i).verdict().evidence().equals(off.get(i).verdict().evidence()),
				"两态聊天采集的文本、宽度与布局证据一致");
			check(!offDisplay.equals(ChatTranslation.plan(source, 320, c -> c.getString().length()).text().getString()),
				"采集隔离不影响实际聊天显示开关");
			config.translateItemNames = false;
			var lore = LoreTranslation.plan(List.of(Component.literal("§7Keep the §cSkyBlock Citizen"),
				Component.literal("§cTimecharm §7in your inventory.")));
			check(lore.size() == 1 && lore.getFirst().complete()
				&& lore.getFirst().rendered().getString().equals("将 SkyBlock Citizen Timecharm 放在背包中。"),
				"真实跨行 Lore 规划也保护英文物品名");
			StyledText styled = StyledText.of(lore.getFirst().rendered());
			check(styled.styleAt(styled.plain().indexOf("SkyBlock")).getColor().getValue() == 0xFF5555,
				"跨行 Lore 物品名保留实时红色");
		} finally {
			config.translateItemNames = previousItems;
			config.translateSkyBlockName = previousGameplay;
			SkyZHConfig.bumpGeneration();
		}
	}

	public static void main(String[] args) throws Exception {
		var files = TranslationLoader.readDirectory(Path.of(args[0]));
		var index = TranslationLoader.compile(files);
		baselineIndex = baseline(files);
		TranslationHarness.installIndex(index);
		caseIndex = index;
		var config = SkyZHConfig.get();
		runCases(Path.of(args[0]).getParent().resolve("tests/resources"));
		try {
			var fixture = JsonParser.parseString("""
				{"records":[{"id":"item_toggle_fixture","text":"Please bring a Magma Core here.",
				"zh":"请把岩浆核心带到这里。","item_refs":[{"text":"Magma Core","zh":"岩浆核心"}]},
				{"id":"repeat_items","text":"Use Magma Core and Magma Core.","zh":"使用岩浆核心，再使用岩浆核心。",
				"item_refs":[{"text":"Magma Core","zh":"岩浆核心"}]},
				{"id":"same_zh_items","text":"Use Wheat and Hay Bale.","zh":"使用小麦，再使用小麦。",
				"item_refs":[{"text":"Wheat","zh":"小麦","target_occurrence":0},{"text":"Hay Bale","zh":"小麦","target_occurrence":1}]},
				{"id":"plural_items","text":"Need %s Magma Cores.","zh":"需要 %s 个岩浆核心。",
				"placeholders":[{"token":"%s","type":"number","example":"2"}],
				"item_refs":[{"text":"Magma Cores","zh":"岩浆核心"}]},
				{"id":"multi_run_item","text":"Bring %s Logs here.","zh":"带来%s原木。",
				"placeholders":[{"token":"%s","type":"category_name","example":"Birch"}],
				"segments":[{"text":"Bring ","zh":"带来"},{"text":"%s ","zh":"%s"},{"text":"Logs","zh":"原木"},{"text":" here.","zh":"。"}],
				"item_refs":[{"text":"%s Logs","zh":"%s原木"}]},
				{"id":"mixed_gameplay_item","text":"SkyBlock item: %s","zh":"SkyBlock 物品: %s",
				"placeholders":[{"token":"%s","type":"catalog_item_name","example":"SkyBlock Citizen Timecharm"}]},
				{"id":"mixed_fixed_item","text":"Welcome to SkyBlock with SkyBlock Citizen Timecharm.",
				"zh":"在 SkyBlock 中使用 SkyBlock 公民时光符。",
				"item_refs":[{"text":"SkyBlock Citizen Timecharm","zh":"SkyBlock 公民时光符"}]}]}
				""").getAsJsonObject();
			files.put("Hub_General/ChatMessage/Item_Toggle_Fixture.json", fixture);
			files.put("Hub_General/GUI_Lore/Item_Toggle_Fixture.json", JsonParser.parseString("""
				{"records":[{"id":"review_lore_item","text":"Keep the SkyBlock Citizen Timecharm in your inventory.",
				"zh":"将SkyBlock 公民时光符放在背包中。",
				"item_refs":[{"text":"SkyBlock Citizen Timecharm","zh":"SkyBlock 公民时光符"}]}]}
				""").getAsJsonObject());
			index = TranslationLoader.compile(files);
			TranslationHarness.installIndex(index);
			Component source = Component.literal("Please bring a ").withStyle(ChatFormatting.WHITE)
				.append(Component.literal("Magma Core").withStyle(ChatFormatting.BLUE))
				.append(Component.literal(" here.").withStyle(ChatFormatting.WHITE));
			config.translateItemNames = true;
			check(Translator.translateLine(source, Surface.CHAT).getString().equals("请把岩浆核心带到这里。"), "开启保留已有译文");
			config.translateItemNames = false;
			Component off = Translator.translateLine(source, Surface.CHAT);
			check(off.getString().equals("请把 Magma Core 带到这里。"), off.getString());
			StyledText styled = StyledText.of(off);
			check(styled.styleAt(off.getString().indexOf("Magma")).getColor().getValue() == 0x5555FF, "恢复实时物品颜色");
						Component repeated = Component.literal("Use ").append(Component.literal("Magma Core").setStyle(
				Style.EMPTY.withColor(ChatFormatting.BLUE).withInsertion("first")
					.withClickEvent(new ClickEvent.RunCommand("/first"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("first hover")))))
				.append(" and ").append(Component.literal("Magma Core").setStyle(
				Style.EMPTY.withColor(ChatFormatting.RED).withInsertion("second")
					.withClickEvent(new ClickEvent.RunCommand("/second"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("second hover"))))).append(".");
			StyledText repeats = StyledText.of(Translator.translateLine(repeated, Surface.CHAT));
			check(repeats.plain().equals("使用 Magma Core，再使用 Magma Core。"), "同名完整恢复");
			check("first".equals(repeats.styleAt(repeats.plain().indexOf("Magma")).getInsertion()), "首次同名交互");
			check("second".equals(repeats.styleAt(repeats.plain().lastIndexOf("Magma")).getInsertion()), "再次同名交互");
			check(repeats.styleAt(repeats.plain().indexOf("Magma")).getColor().getValue() == 0x5555FF
				&& repeats.styleAt(repeats.plain().lastIndexOf("Magma")).getColor().getValue() == 0xFF5555, "同名分别保留实时颜色");
			StyledText repeatedSource = StyledText.of(repeated);
			for (int at : List.of(repeats.plain().indexOf("Magma"), repeats.plain().lastIndexOf("Magma"))) {
				int sourceAt = at == repeats.plain().indexOf("Magma") ? repeatedSource.plain().indexOf("Magma") : repeatedSource.plain().lastIndexOf("Magma");
				Style expectedStyle = repeatedSource.styleAt(sourceAt), actualStyle = repeats.styleAt(at);
				check(expectedStyle.getClickEvent().equals(actualStyle.getClickEvent()), "物品点击事件逐次保持");
				check(expectedStyle.getHoverEvent().equals(actualStyle.getHoverEvent()), "物品悬浮事件逐次保持");
			}
			check(Translator.translateLine(Component.literal("Use Wheat and Hay Bale."), Surface.CHAT).getString()
				.equals("使用 Wheat，再使用 Hay Bale。"), "同中文不同英文按指定目标恢复");
			for (String[] pair : List.of(new String[]{"1", "Core"}, new String[]{"2", "Cores"})) {
				check(Translator.translateLine(Component.literal("Need " + pair[0] + " Magma " + pair[1] + "."), Surface.CHAT)
					.getString().equals("需要 " + pair[0] + " 个 Magma " + pair[1] + "。"), "实时单复数 " + pair[1]);
			}
			for (String wood : List.of("Birch", "Spruce")) {
				Component input = Component.literal("Bring ").append(Component.literal(wood + " ").withStyle(ChatFormatting.BLUE))
					.append(Component.literal("Logs").withStyle(ChatFormatting.RED)).append(" here.");
				StyledText output = StyledText.of(Translator.translateLine(input, Surface.CHAT));
				check(output.plain().equals("带来 " + wood + " Logs。"), "跨颜色整名 " + wood);
				check(output.styleAt(output.plain().indexOf(wood)).getColor().getValue() == 0x5555FF
					&& output.styleAt(output.plain().indexOf("Logs")).getColor().getValue() == 0xFF5555, "跨颜色两段保留 " + wood);
			}
			check(TooltipTranslator.translateItemName(Component.literal("Ancient Mineral Helmet ✪✪")).padded().getString()
				.equals("Ancient Mineral Helmet ✪✪"), "完整名称、重铸及星级保留英文");
			check(TooltipTranslator.translateItemName(Component.literal("Close")).padded().getString().equals("关闭"), "菜单按钮继续翻译");
			for (String type : List.of("catalog_item_name", "loot_item_name", "guide_item_name", "loadout_item_name")) {
				var entry = TranslationEntry.compile(new TranslationEntry.Definition("dynamic", "fixture",
					List.of(new TranslationEntry.Segment("Item: %s", "物品: %s")),
					Map.of(1, new TranslationEntry.Argument(type, "Magma Core")), TranslationEntry.Options.DEFAULT));
				StyledText text = StyledText.of(Component.literal("Item: Magma Core"));
				check(entry.render(text, entry.match(text.plain()), index.terms()).getString().equals("物品: Magma Core"), type);
			}
			checkGameplayAndCapture();
			check(ItemNamePolicy.available(() -> Translator.translateLine(source, Surface.CHAT).getString())
				.equals("请把岩浆核心带到这里。"), "覆盖检查不受显示开关影响");
			var cache = new ChatDisplayCache();
			check(cache.needsRefresh(true, true, index), "首次刷新");
			check(!cache.needsRefresh(true, true, index), "同策略不刷新");
			config.translateItemNames = true;
			check(cache.needsRefresh(true, true, index), "名称开关刷新旧聊天");
			check(Translator.translateLine(source, Surface.CHAT).getString().equals("请把岩浆核心带到这里。"), "恢复开启后无旧缓存");
			for (String metadata : List.of(
				"\"source_occurrence\":-1", "\"target_occurrence\":9", "\"target_segment\":9",
				"\"source_records\":[\"missing\"]", "\"emit\":false")) {
				var invalid = JsonParser.parseString("{\"records\":[{\"id\":\"invalid\",\"text\":\"Use Magma Core.\","
					+ "\"zh\":\"使用岩浆核心。\",\"item_refs\":[{\"text\":\"Magma Core\",\"zh\":\"岩浆核心\"," + metadata + "}]}]}").getAsJsonObject();
				boolean rejected = false;
				try { TranslationLoader.compile(Map.of("Hub_General/ChatMessage/Invalid.json", invalid)); }
				catch (IllegalArgumentException expected) { rejected = true; }
				check(rejected, "拒绝无效物品范围元数据 " + metadata);
			}
			var old = SkyZHConfig.fromJson(new com.google.gson.JsonObject());
			check(old.translateItemNames, "旧配置默认开启");
			config.translateItemNames = false;
			check(!SkyZHConfig.fromJson(config.toJson()).translateItemNames, "设置保存与读取");
			check(!OriginalTips.containsTranslatedItem(source), "关闭名称翻译不提示切换名称原文");
			// Compiling while the player keeps English must still publish complete Chinese item data.
			var compiledOff = TranslationLoader.compile(files);
			check(compiledOff.terms().translate("catalog_item_name", "Magma Core").equals("岩浆核心"), "关闭时重载不丢中文名称");
			System.out.println("物品名称开关通过 " + checks + " 项检查。");
		} finally {
			config.translateItemNames = true;
			SkyZHConfig.bumpGeneration();
		}
	}
}
