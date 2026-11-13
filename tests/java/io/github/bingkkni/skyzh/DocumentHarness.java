package io.github.bingkkni.skyzh;

import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.*;
import io.github.bingkkni.skyzh.text.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** Frozen server observations plus independent layout and interaction expectations. */
public final class DocumentHarness {
	private static int passed;
	public static void main(String[] args) throws Exception {
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(index);
		for (var el : JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray()) {
			var fixture = el.getAsJsonObject();
			Surface surface = Surface.fromDirectory(fixture.get("surface").getAsString());
			Component source = Component.literal(fixture.get("raw").getAsString());
			StyledText before = StyledText.of(source);
			Component output = DocumentTranslation.available(source, surface);
			if (fixture.get("preserved").getAsBoolean()) {
				check("Sanger 人名保留", StyledText.of(output).equals(before));
				continue;
			}
			var located = Translator.locate(source, surface);
			check("实际整面/页样本覆盖 " + fixture.get("gameplay") + ": " + before.plain(), located.matched());
			check("样本无中英混杂", !located.entry().mixed(located.core(), located.match(), index.terms()).any());
			check("样本颜色边界完整", !located.entry().losesColour(located.core(), located.match()));
			check("保留显式行数", DocumentTranslation.lines(source).size() == DocumentTranslation.lines(output).size());
			check("不写原文", before.equals(StyledText.of(source)));
			if (fixture.has("expected")) {
				check("整面译文及标记符合独立期望", StyledText.of(output).plain().equals(fixture.get("expected").getAsString()));
			}
			CaptureSurface capture = surface == Surface.SIGN ? CaptureSurface.SIGN : CaptureSurface.BOOK;
			check("采集不重复报告已完成文档", Classifier.all(capture, before).isEmpty());
		}
		Component auction = DocumentTranslation.available(Component.literal(
			"§6§l12.5M coins\n§22 bids\n§301h 40m 53s\n§5§l[CLICK]"), Surface.SIGN);
		check("独立拍卖期望", StyledText.of(auction).plain().equals("12.5M 硬币\n2 次出价\n01小时40分53秒\n[点击]"));
		check("出价单数也匹配", StyledText.of(DocumentTranslation.available(Component.literal(
			"1 coins\n1 bid\n4d\n[CLICK]"), Surface.SIGN)).plain().equals("1 硬币\n1 次出价\n4 天\n[点击]"));
		check("渲染面隔离", !Translator.locate(Component.literal("LEAP OF FAITH\n\n\n"), Surface.MISC).matched());

		// An actual book page with a unique click target and exact RGB hover styling.
		var bookFile = TranslationHarness.readCorpus(Path.of(args[0])).get("Rift/Book/Bookshelf.json");
		var record = bookFile.getAsJsonArray("lines").get(0).getAsJsonObject();
		String prefix = record.getAsJsonArray("segments").get(0).getAsJsonObject().get("text").getAsString();
		String suffix = record.getAsJsonArray("segments").get(2).getAsJsonObject().get("text").getAsString();
		ClickEvent click = new ClickEvent.RunCommand("/riftbookshelftravel");
		Component page = Component.literal(prefix).append(Component.literal("LIVE IN THE MOMENT").setStyle(
			Style.EMPTY.withColor(0x005500).withBold(true).withClickEvent(click)
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("It's never too late!").withColor(0xffff00)))))
			.append(suffix);
		Component pageOutput = DocumentTranslation.book(page, 114, DocumentHarness::measure);
		StyledText displayed = StyledText.of(pageOutput);
		int button = displayed.plain().indexOf("活在当下");
		check("正文独立期望", displayed.plain().startsWith("穿越时间解不开人生的谜题。"));
		check("按钮继承点击命令", click.equals(displayed.styleAt(button).getClickEvent()));
		check("按钮继承精确颜色和粗体", displayed.styleAt(button).isBold()
			&& displayed.styleAt(button).getColor().getValue() == 0x005500);
		HoverEvent.ShowText hover = (HoverEvent.ShowText) displayed.styleAt(button).getHoverEvent();
		check("悬浮说明翻译", StyledText.of(hover.value()).plain().equals("现在开始,永远不晚!"));
		check("悬浮颜色保真", StyledText.of(hover.value()).styleAt(0).getColor().getValue() == 0xffff00);
		check("原始按钮仍是英文", StyledText.of(page).plain().contains("LIVE IN THE MOMENT"));

		// The new-player store book has an unbracketed URL button, not a command button.
		var storeRecord = TranslationHarness.readCorpus(Path.of(args[0]))
			.get("Hub_General/Book/Capture_Nov02.json").getAsJsonArray("lines").get(0).getAsJsonObject();
		var storeSegments = storeRecord.getAsJsonArray("segments");
		ClickEvent storeClick = new ClickEvent.OpenUrl(java.net.URI.create("https://store.hypixel.net/"));
		Component storePage = Component.literal(storeSegments.get(0).getAsJsonObject().get("text").getAsString())
			.append(Component.literal("    ").withStyle(ChatFormatting.BOLD))
			.append(Component.literal("VISIT STORE").setStyle(Style.EMPTY.withColor(0x00aaaa).withBold(true)
				.withClickEvent(storeClick)))
			.append(storeSegments.get(3).getAsJsonObject().get("text").getAsString());
		StyledText storeOutput = StyledText.of(DocumentTranslation.book(storePage, 114, DocumentHarness::measure));
		int storeButton = storeOutput.plain().indexOf("前往商店");
		check("商店书页按钮已翻译", storeButton >= 0);
		check("商店按钮保留服务器网址", storeClick.equals(storeOutput.styleAt(storeButton).getClickEvent()));
		check("商店按钮保留深青色与粗体", storeOutput.styleAt(storeButton).isBold()
			&& storeOutput.styleAt(storeButton).getColor().getValue() == 0x00aaaa);
		check("商店正文不继承网址", storeOutput.styleAt(0).getClickEvent() == null);
		check("商店按钮前后不泄漏点击事件", storeOutput.styleAt(storeButton - 1).getClickEvent() == null
			&& storeOutput.styleAt(storeButton + "前往商店".length()).getClickEvent() == null);
		check("商店书页保留全部显式行", DocumentTranslation.lines(storePage).size()
			== DocumentTranslation.lines(DocumentTranslation.available(storePage, Surface.BOOK)).size());
		check("商店原始按钮不改写", StyledText.of(storePage).plain().contains("VISIT STORE"));

		// Independent tiny corpus: geometry must use book width, not 320px chat width.
		var fixtureFiles = new HashMap<String, com.google.gson.JsonObject>();
		fixtureFiles.put("Hub_General/Book/Layout.json", JsonParser.parseString("""
			{"lines":[{"id":"center","text":"TITLE","zh":"标题"},
			{"id":"indent","text":"List","zh":"列表"}]}
			""").getAsJsonObject());
		TranslationHarness.installIndex(TranslationLoader.compile(fixtureFiles));
		check("居中随译文字宽重算", StyledText.of(DocumentTranslation.book(Component.literal("    TITLE"), 62,
			DocumentHarness::measure)).plain().equals("      标题"));
		check("短缩进保持左侧", StyledText.of(DocumentTranslation.book(Component.literal("  List"), 114,
			DocumentHarness::measure)).plain().equals("  列表"));
		check("空行和未知文本保留", StyledText.of(DocumentTranslation.book(Component.literal("\nTITLE\n\nUnknown sentence\n"),
			114, DocumentHarness::measure)).plain().equals("\n标题\n\nUnknown sentence\n"));
		checkSignCache();
		check("非空岛门控关闭", !HypixelServer.canTranslate());
		System.out.println("文档翻译检查通过: " + passed);
	}
	private static void checkSignCache() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		TranslationIndex index = TranslationLoader.compile(Map.of("Test/Sign/Cache.json", JsonParser.parseString("""
			{"lines":[{"id":"title","text":"TITLE","zh":"标题"},{"id":"filtered","text":"FILTERED","zh":"过滤后"},
			{"id":"bad","text":"BROKEN","zh":"一\\n二\\n三\\n四\\n五"}]}
			""").getAsJsonObject()));
		TranslationHarness.installIndex(index);
		var signJson = JsonParser.parseString("""
			{"messages":["TITLE","","",""],"filtered_messages":["FILTERED","","",""],"color":"red","has_glowing_text":true}
			""");
		var sign = io.github.bingkkni.skyzh.platform.ServerSignData.codec()
			.parse(com.mojang.serialization.JsonOps.INSTANCE, signJson).getOrThrow();
		var cache = new SignDisplayCache();
		check("离屏告示牌实例不强持有旧语料或语言代际", Arrays.stream(SignDisplayCache.class.getDeclaredFields())
			.noneMatch(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())
				&& (field.getType() == TranslationIndex.class || field.getType() == net.minecraft.locale.Language.class)));
		int[] prepared = {0};
		java.util.function.Function<Component, net.minecraft.util.FormattedCharSequence> prepare = text -> {
			prepared[0]++;
			return text.getVisualOrderText();
		};
		Object font = new Object();
		var first = cache.get(sign, false, 90, font, prepare);
		check("告示牌译文准备四行", first != null && prepared[0] == 4 && sequence(first[0]).equals("标题"));
		for (int i = 0; i < 200; i++) check("告示牌连续帧不重译或重新准备字形",
			cache.get(sign, false, 90, font, prepare) == first && prepared[0] == 4);
		check("不写入告示牌原文", io.github.bingkkni.skyzh.platform.ServerSignData.lines(sign).getFirst().getString().equals("TITLE"));
		var vanilla = sign.getRenderMessages(false, Component::getVisualOrderText);
		check("不写入原版渲染缓存", sequence(vanilla[0]).equals("TITLE"));
		var filtered = cache.get(sign, true, 90, font, prepare);
		check("过滤文本独立失效", sequence(filtered[0]).equals("过滤后") && prepared[0] == 8);
		var hanging = cache.get(sign, true, 60, font, prepare);
		check("悬挂牌宽度不复用普通牌排版", hanging != filtered && prepared[0] == 12);
		check("字体对象变化重新排版", cache.get(sign, true, 60, new Object(), prepare) != hanging && prepared[0] == 16);
		SkyZHConfig.bumpGeneration();
		var generation = cache.get(sign, true, 60, font, prepare);
		check("配置代际变化重新翻译", generation != hanging && prepared[0] == 20);
		var language = net.minecraft.locale.Language.getInstance();
		try {
			net.minecraft.locale.Language.inject(new net.minecraft.locale.Language() {
				@Override public String getOrDefault(String key, String fallback) { return language.getOrDefault(key, fallback); }
				@Override public boolean has(String key) { return language.has(key); }
				@Override public boolean isDefaultRightToLeft() { return language.isDefaultRightToLeft(); }
				@Override public net.minecraft.util.FormattedCharSequence getVisualOrder(FormattedText text) {
					return language.getVisualOrder(text);
				}
			});
			check("资源/语言重载重新准备告示牌", cache.get(sign, true, 60, font, prepare) != generation && prepared[0] == 24);
		} finally { net.minecraft.locale.Language.inject(language); }
		for (String source : List.of("", "UNKNOWN", "BROKEN")) {
			var json = JsonParser.parseString("{\"messages\":[\"" + source + "\",\"\",\"\",\"\"]}");
			var unchanged = io.github.bingkkni.skyzh.platform.ServerSignData.codec()
				.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
			var negative = new SignDisplayCache();
			int before = prepared[0];
			for (int i = 0; i < 100; i++)
				check("空白/未知/非法行数的告示牌回退原版 " + source,
					negative.get(unchanged, false, i % 2 == 0 ? 90 : 60, font, prepare) == null);
			check("未翻译告示牌不准备重复字形", prepared[0] == before);
		}
		var replace = TranslationLoader.compile(Map.of("Test/Sign/Cache.json", JsonParser.parseString("""
			{"lines":[{"id":"new","text":"FILTERED","zh":"新语料"}]}
			""").getAsJsonObject()));
		TranslationHarness.installIndex(replace);
		check("语料索引更换使实例缓存失效", sequence(cache.get(sign, true, 60, font, prepare)[0]).equals("新语料"));
	}

	private static String sequence(net.minecraft.util.FormattedCharSequence text) {
		StringBuilder result = new StringBuilder();
		text.accept((index, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
		return result.toString();
	}

	private static int measure(Component text) {
		return StyledText.of(text).plain().codePoints().map(c -> c == ' ' ? 4 : c > 127 ? 9 : 6).sum();
	}
	private static void check(String name, boolean valid) {
		if (!valid) throw new AssertionError(name);
		passed++;
	}
}
