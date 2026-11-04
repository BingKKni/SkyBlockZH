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
		check("非空岛门控关闭", !HypixelServer.canTranslate());
		System.out.println("文档翻译检查通过: " + passed);
	}
	private static int measure(Component text) {
		return StyledText.of(text).plain().codePoints().map(c -> c == ' ' ? 4 : c > 127 ? 9 : 6).sum();
	}
	private static void check(String name, boolean valid) {
		if (!valid) throw new AssertionError(name);
		passed++;
	}
}
