package io.github.bingkkni.skyzh;

import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Exercises the item-name display policy without changing English matching or corpus coverage. */
public final class ItemNameHarness {
	private static int checks;
	private static void check(boolean success, String message) {
		checks++;
		if (!success) throw new AssertionError(message);
	}
	public static void main(String[] args) throws Exception {
		var files = TranslationLoader.readDirectory(Path.of(args[0]));
		var index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
		var config = SkyZHConfig.get();
		try {
			var fixture = JsonParser.parseString("""
				{"records":[{"id":"item_toggle_fixture","text":"Please bring a Magma Core here.",
				"zh":"请把岩浆核心带到这里。","item_refs":[{"text":"Magma Core","zh":"岩浆核心"}]}]}
				""").getAsJsonObject();
			files.put("Hub_General/ChatMessage/Item_Toggle_Fixture.json", fixture);
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
			check(ItemNamePolicy.available(() -> Translator.translateLine(source, Surface.CHAT).getString())
				.equals("请把岩浆核心带到这里。"), "覆盖检查不受显示开关影响");
			var cache = new ChatDisplayCache();
			check(cache.needsRefresh(true, true, index), "首次刷新");
			check(!cache.needsRefresh(true, true, index), "同策略不刷新");
			config.translateItemNames = true;
			check(cache.needsRefresh(true, true, index), "名称开关刷新旧聊天");
			check(Translator.translateLine(source, Surface.CHAT).getString().equals("请把岩浆核心带到这里。"), "恢复开启后无旧缓存");
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
