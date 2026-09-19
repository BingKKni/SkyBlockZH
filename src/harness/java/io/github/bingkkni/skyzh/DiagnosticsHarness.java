package io.github.bingkkni.skyzh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.capture.CaptureStore;
import io.github.bingkkni.skyzh.capture.CaptureSurface;
import io.github.bingkkni.skyzh.capture.ChatShape;
import io.github.bingkkni.skyzh.capture.Classifier;
import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.capture.TranslationDiagnostics;
import io.github.bingkkni.skyzh.capture.Unplaced;
import io.github.bingkkni.skyzh.text.Capture;
import io.github.bingkkni.skyzh.text.ChatLayout;
import io.github.bingkkni.skyzh.text.ChatTranslation;
import io.github.bingkkni.skyzh.text.LineShape;
import io.github.bingkkni.skyzh.text.LoreTranslation;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TermTable;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Regression cases from the two computers, plus deliberately broken records to exercise capture. */
public final class DiagnosticsHarness {
	private static int checks;
	private static final List<String> failures = new ArrayList<>();
	private static TranslationIndex corpus;

	public static void main(String[] args) throws Exception {
		corpus = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(corpus);
		capturePolicies();
		liveLore();
		chat();
		joinedChat();
		existingTerms();
		brokenRecords();
		persist();
		TranslationHarness.installIndex(corpus);
		for (JsonElement value : JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray()) {
			JsonObject fixture = value.getAsJsonObject();
			check("采集样本晚于上次编译", fixture.get("last_seen").getAsString().compareTo("2026-09-14 15:34:17") > 0);
			Surface surface = Surface.fromDirectory(fixture.get("surface").getAsString());
			var found = Translator.locate(CaptureReplay.decode(fixture.get("raw").getAsString()), surface);
			check("实测颜色 " + fixture.get("record"), found.matched()
				&& !found.entry().losesColour(found.core(), found.match()));
		}
		System.out.printf("Diagnostic checks=%d, failures=%d%n", checks, failures.size());
		failures.forEach(s -> System.err.println("FAIL " + s));
		if (!failures.isEmpty()) {
			throw new AssertionError("Diagnostic regression failed");
		}
	}

	private static void capturePolicies() {
		check("必填品质的正则直接拒绝空值", !Pattern.matches(Capture.TROPHY_QUALITY.regex(), ""));
		check("必填品质的语义校验拒绝空值", !Capture.TROPHY_QUALITY.accepts(""));

		for (String quality : List.of("BRONZE", "SILVER", "GOLD", "DIAMOND")) {
			check("必填品质仍接受有效值 " + quality, Pattern.matches(Capture.TROPHY_QUALITY.regex(), quality));
		}

		check("品质不接受未知值", !Pattern.matches(Capture.TROPHY_QUALITY.regex(), "MYTHIC"));
		check("可选名称保留空值兼容", Pattern.matches(Capture.NAME.regex(), "") && Capture.NAME.accepts(""));
		check("可选数字保留空值兼容", Pattern.matches(Capture.NUMBER.regex(), "") && Capture.NUMBER.accepts(""));

		TranslationEntry unspecified = TranslationEntry.compile(new TranslationEntry.Definition(
			"unspecified", "test", List.of(new TranslationEntry.Segment("Mine %s blocks", "挖掘 %s 个方块")),
			Map.of(), TranslationEntry.Options.DEFAULT
		));
		check("未声明占位符类型仍可按默认短语编译", unspecified != null && unspecified.match("Mine 3 blocks") != null);

		for (String tag : List.of("[NPC] ", "[BOSS] ", "[SECURITY] ", "[CROWD] ", "[STATUE] ", "[SKULL] ")) {
			String line = "   " + tag + "Keeper of the Crystal: Hello";
			int end = line.indexOf("Hello");
			check("采集和渲染共用说话人 " + tag, LineShape.speakerTagEnd(line) == end
				&& ChatShape.npcTagEnd(line) == end && ChatShape.npcName(line).equals("Keeper of the Crystal"));
		}

		String tooLong = "[NPC] " + "A".repeat(49) + ": Hello";
		check("过长说话人两条路径同时拒绝", LineShape.speaker(tooLong) == null && ChatShape.npcTagEnd(tooLong) == -1);
	}

	private static void liveLore() {
		for (int n : new int[]{1, 2, 8, 15}) {
			var campfire = components("§7Each campfire can only be", "§7interacted with once, and you can",
				"§7only place one campfire every §a" + n, "§aminutes§7.");
			var plan = LoreTranslation.plan(campfire);
			check("营火跨行数值 " + n, plan.size() == 1 && plain(plan).equals("每堆营火只能互动一次,而且每 " + n + " 分钟才能放置一堆。"));
			check("营火数值及单位保留绿色", encoded(plan).contains("§a" + n + " 分钟§7才能放置一堆。"));
			check("修复营火不再诊断缺数值", TranslationDiagnostics.lore(plan).isEmpty());
			for (String in : List.of("", " in")) {
				var next = LoreTranslation.plan(components("§7The next event will be announced" + in,
					"§7about §a" + n + " §7minutes!"));
				check("动态倒计时 " + n + in, next.size() == 1 && plain(next).equals("下一场活动将在大约 " + n + " 分钟后公布!"));
				check("倒计时数值保持绿色", encoded(next).contains("§a" + n + " §7分钟后公布!"));
			}
		}
		var cultivating = LoreTranslation.plan(components("§7Gain §3+1☯ Farming Wisdom §7and §6+2☘", "§6Farming Fortune§7."));
		check("农业时运只输出一次", plain(cultivating).equals("获得 +1☯ 农业智慧和 +2☘ 农业时运。"));
		check("农业时运句号保持灰色", encoded(cultivating).endsWith("§6+2☘ 农业时运§7。"));
		var powder = LoreTranslation.plan(components("§cYou don't have enough Glacite", "§cPowder!"));
		check("粉末与精华不串门", powder.size() == 1 && plain(powder).equals("你的极冰粉末不够!"));
		var essence = LoreTranslation.plan(components("§cYou don't have enough Wither", "§cEssence!"));
		check("精华原有两行仍可翻译", essence.size() == 1 && plain(essence).equals("你的凋零精华不足!"));
		check("未命中普通记录的 SkyBlock 独立行仍走原有名称开关", plain(LoreTranslation.plan(components("SkyBlock"))).equals("空岛生存"));
		var orphan = LoreTranslation.plan(components("§7Unknown new head", "§aminutes§7."));
		check("未知首行不能吞续行", orphan.size() == 2 && plain(orphan).equals("Unknown new head\nminutes."));
		check("空行隔开的文本不算续行", TranslationDiagnostics.lore(LoreTranslation.plan(components(
			"§7Each campfire can only be", "", "§7about 5 minutes!"))).isEmpty());
		boolean enabled = SkyZHConfig.get().enabled;
		SkyZHConfig.get().enabled = false;
		check("关闭翻译时采集仍使用相同计划", LoreTranslation.plan(components(
			"§cYou don't have enough Glacite", "§cPowder!")).getFirst().rendered().getString().equals("你的极冰粉末不够!"));
		SkyZHConfig.get().enabled = enabled;
	}

	private static void chat() {
		for (String source : List.of("                                   Rewards", "                                   REWARDS",
			"                              +10 SkyBlock XP", "                       1. Knock mobs into the lava.",
			"             2. Some mobs give more points than others.", "                       3. Avoid negative point mobs!")) {
			Component text = Component.literal(source);
			var result = ChatLayout.plan(text, Translator.translateAvailable(text, Surface.CHAT), 320, DiagnosticsHarness::width);
			check("实测横幅居中 " + source.trim(), Math.abs(center(result.text()) - 160) <= 3);
		}
		for (String source : List.of("    +10,000 Farming Experience", "    +5,000 Farming Experience",
			"    +12 Garden Experience", "    +10 Bits", "  REWARDS", "    +10 SkyBlock XP")) {
			Component text = Component.literal(source);
			var result = ChatLayout.plan(text, Translator.translateAvailable(text, Surface.CHAT), 320, DiagnosticsHarness::width);
			check("任务奖励保留左缩进 " + source.trim(), left(result.text()) == left(text));
		}
		Component source = Component.literal("                                   Rewards");
		check("零或极小聊天缩放不会分配巨量空格", ChatLayout.plan(source,
			Translator.translateAvailable(source, Surface.CHAT), Integer.MAX_VALUE, DiagnosticsHarness::width)
			.text().getString().length() < 100);
		var narrow = ChatLayout.plan(source, Translator.translateAvailable(source, Surface.CHAT), 120, DiagnosticsHarness::width);
		check("聊天宽度改变后仍居中", Math.abs(center(narrow.text()) - 60) <= 3);
		Component shortOption = Component.literal("    §6ⓐ §aZombie Villager");
		check("Oruo 四格填充尊重显式居中", Math.abs(center(ChatLayout.plan(shortOption,
			Translator.translateAvailable(shortOption, Surface.CHAT), 320, DiagnosticsHarness::width).text()) - 160) <= 3);
		Component speaker = Component.literal("[STATUE] Oruo the Omniscient:     §6ⓐ §aZombie Villager");
		var speakerPlan = ChatLayout.plan(speaker, Translator.translateAvailable(speaker, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("说话人前缀保留且不重复内部填充", speakerPlan.text().getString().stripLeading().startsWith("[STATUE] Oruo the Omniscient: ⓐ ")
			&& !speakerPlan.text().getString().contains(":     ") && Math.abs(center(speakerPlan.text()) - 160) <= 3);
		Component right = Component.literal("                                                                      Rewards");
		var aligned = ChatLayout.plan(right, Translator.translateAvailable(right, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("右对齐保留右边缘", Math.abs(width(aligned.text()) - 320) <= 4);
	}

	private static void joinedChat() throws Exception {
		// Two lines inside one component, each of which fits the box on its own; the fake font draws
		// "Alpha beta gamma" at 87px, "delta omega" at 59px and every CJK character at 8px.
		TranslationIndex joined = new TranslationIndex();
		add(joined, Surface.CHAT, "head", "Alpha beta gamma", "甲乙丙丁戊己庚辛壬癸", "", "tail");
		add(joined, Surface.CHAT, "tail", "delta omega", "子丑寅卯辰巳午未申酉", "", "");
		add(joined, Surface.CHAT, "wide", "Alpha beta gamma delta omega", "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉", "", "");
		TranslationHarness.installIndex(joined);

		try {
			Component source = Component.literal("Alpha beta gamma\ndelta omega");
			ChatTranslation.Plan plan = ChatTranslation.plan(source, 120, DiagnosticsHarness::width);
			List<TranslationDiagnostics.Finding> findings = TranslationDiagnostics.chat(plan);
			Component drawn = Translator.translateChatBlock(source, 120, DiagnosticsHarness::width);

			check("合并后的聊天仍是完整译文", plan.units().size() == 1
				&& drawn.getString().equals("甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉"));
			check("采集与渲染的整块输出及样式一致", StyledText.of(plan.text()).equals(StyledText.of(drawn)));
			check("两行各自放得下、合并后超宽会报告", findings.size() == 1
				&& findings.getFirst().verdict().evidence().get("code").getAsString().equals("layout_overflow"));

			if (!findings.isEmpty()) {
				JsonObject evidence = findings.getFirst().verdict().evidence();
				check("布局证据保留两行原文和两条记录", evidence.getAsJsonArray("original_lines").size() == 2
					&& evidence.getAsJsonArray("records").size() == 2);
				check("布局证据输出与玩家显示一致", evidence.getAsJsonArray("rendered_lines").size() == 1
					&& evidence.getAsJsonArray("rendered_lines").get(0).getAsString().equals(TranslationHarness.legacy(drawn)));
				check("超宽以完整译文测量", evidence.get("translated_text_width_px").getAsInt() == 160);
				check("超宽证据记录原文各行最宽处", evidence.get("source_extent_px").getAsInt() == 87);
			}

			for (Component line : plan.units().getFirst().source()) {
				check("原来逐行检查会漏报这个样本", TranslationDiagnostics.chat(
					ChatTranslation.plan(line, 120, DiagnosticsHarness::width)).isEmpty());
			}

			check("宽聊天框不误报合并后超宽", TranslationDiagnostics.chat(
				ChatTranslation.plan(source, 320, DiagnosticsHarness::width)).isEmpty());

			// One line whose English (150px) already overflows a 140px box: vanilla wraps it either way, so
			// a 160px translation is not something the corpus can fix. At 155px the English fitted and the
			// Chinese does not, which is the one case worth a report.
			Component wide = Component.literal("Alpha beta gamma delta omega");
			check("英文本身超宽时不报告译文超宽", TranslationDiagnostics.chat(
				ChatTranslation.plan(wide, 140, DiagnosticsHarness::width)).isEmpty());
			List<TranslationDiagnostics.Finding> caused = TranslationDiagnostics.chat(
				ChatTranslation.plan(wide, 155, DiagnosticsHarness::width));
			check("译文造成的超宽才报告", caused.size() == 1
				&& caused.getFirst().verdict().evidence().get("code").getAsString().equals("layout_overflow"));

			Component blankEdges = Component.empty().append("\n").append(source).append("\n");
			String withBlanks = ChatTranslation.plan(blankEdges, 320, DiagnosticsHarness::width).text().getString();
			check("共用聊天计划保留前后空行", withBlanks.startsWith("\n") && withBlanks.endsWith("\n")
				&& withBlanks.chars().filter(c -> c == '\n').count() == 2);

			boolean enabled = SkyZHConfig.get().enabled;

			try {
				SkyZHConfig.get().enabled = false;
				check("关闭显示时原始聊天直接返回", Translator.translateChatBlock(source, 120, DiagnosticsHarness::width) == source);
				check("关闭显示不阻止采集检查聊天合并", !TranslationDiagnostics.chat(
					ChatTranslation.plan(source, 120, DiagnosticsHarness::width)).isEmpty());
			} finally {
				SkyZHConfig.get().enabled = enabled;
			}
		} finally {
			TranslationHarness.installIndex(corpus);
		}
	}

	private static void existingTerms() {
		for (String[] sample : List.of(
			new String[]{"Progress to Melon Slice VIII: 52%", "西瓜片"},
			new String[]{"Progress to Blaze Rod VII: 51.7%", "烈焰棒"},
			new String[]{"Chum Sinker", "碎鱼饵"},
			new String[]{"RARE WAND", "法杖"},
			new String[]{"Yellow Belt Upgrade", "黄带"})) {
			String output = Translator.translateLine(Component.literal(sample[0]), Surface.ITEM).getString();
			check("报告中的既有译名生效 " + sample[0], output.contains(sample[1]));
		}
		check("Tab 宠物使用既有译名", Translator.translateLine(Component.literal("[Lvl 100] Black Cat"), Surface.TABLIST)
			.getString().equals("[100 级] 黑猫"));
		check("同一报告归并的 Stamina 目标保留分色", colourSafe("§aTest of Stamina §e§lOBJECTIVES", Surface.CHAT));
		check("同一报告归并的 Mastery 目标保留分色", colourSafe("§aTest of Mastery §e§lOBJECTIVES", Surface.CHAT));
		check("同一报告归并的火球说明保留编号色", colourSafe("§62. §7Earn points by avoiding fireballs over time.", Surface.LORE));
		check("同一报告归并的 Epic 分解说明保留稀有度色", colourSafe("§7Insert all salvageable §5Epic§7 items dropped from mobs in your inventory that have not been modified or upgraded.", Surface.LORE));
		check("同一报告归并的胡萝卜原料计数保留名称色", colourSafe("§71x §fCarrot", Surface.ITEM));
		check("不把同词异义的守卫/银行译名用于饰品/附魔", corpus.terms().translate("enchantment_name", "Bank") == null
			&& corpus.terms().translate("accessory_power", "Healthy") == null);
	}

	private static boolean colourSafe(String input, Surface surface) {
		var located = Translator.locate(Component.literal(input), surface);
		return located.matched() && !located.entry().losesColour(located.core(), located.match());
	}

	private static void brokenRecords() throws Exception {
		TranslationIndex broken = new TranslationIndex();
		add(broken, Surface.ITEM, "head", "This grants 50 extra", "获得额外 50 点伤害!", "");
		add(broken, Surface.ITEM, "missing", "Mine %s blocks", "挖掘方块", "");
		add(broken, Surface.ITEM, "unbound", "Each campfire can only be", "每 %s 分钟放置一次", "");
		add(broken, Surface.CHAT, "wrong-center", "+%s Bits", "+%s 点券", "center_chat_banner");
		add(broken, Surface.CHAT, "wrong-left", "Rewards", "奖励", "left_chat");
		add(broken, Surface.CHAT, "missing-layout", "Banner", "横幅", "");
		add(broken, Surface.CHAT, "conditional-center", "Choice", "选项", "center_chat_if_padded");
		add(broken, Surface.CHAT, "whole-speaker", "[STATUE] Oruo: Whole", "[STATUE] Oruo: 整句", "center_chat_banner");
		add(broken, Surface.LORE, "lost-complete", "Mine %s blocks", "挖掘方块", "");
		add(broken, Surface.ITEM, "counter", "Count %s then %s", "次数 %1$s", "");
		add(broken, Surface.ITEM, "skyblock", "Mine %s SkyBlock blocks", "SkyBlock 方块", "");
		add(broken, Surface.ITEM, "allday", "12:00 am-11:59 pm: Contest", "全天: 竞赛", "");
		add(broken, Surface.ITEM, "orb", "Place an orb for %sm buffing up to %s", "放置一颗光球,持续 %1$s 分钟,最多为 %2$s 名", "");
		add(broken, Surface.ITEM, "passive", "PASSIVE EVENT %s", "活动 %s", "", "", Map.of(1, new TranslationEntry.Argument("raw", "2X POWDER")));
		add(broken, Surface.ITEM, "title", "Ability: Some Title", "技能: 某标题", "");
		add(broken, Surface.ITEM, "name", "Topaz Crystal Hunter", "Topaz 水晶猎人", "");
		add(broken, Surface.ITEM, "slot", "+%s Commission Slot", "+%s 个委托槽位", "");
		add(broken, Surface.ITEM, "wrapped", "Grants +%s Mining Speed and +%s", "额外获得 +%1$s 挖掘速度和 +%2$s", "");
		broken.terms(TermTable.from(JsonParser.parseString(
			"{\"applies_to_types\":[\"raw\"],\"terms\":[{\"en\":\"2X POWDER\",\"zh\":\"双倍粉末\"}]}").getAsJsonObject()));
		TranslationHarness.installIndex(broken);
		loreCache(broken);
		var incomplete = TranslationDiagnostics.lore(LoreTranslation.plan(components("This grants 50 extra", "damage!")));
		check("整句已译但英语尾行残留会报告", incomplete.stream().anyMatch(f -> f.verdict().bucket() == Classifier.Bucket.INCOMPLETE));
		var actual = Classifier.all(CaptureSurface.GUI_ITEM, StyledText.of(Component.literal("§7Mine §a3 §cblocks")));
		check("同一行数值及颜色问题分别保留", actual.stream().anyMatch(v -> v.bucket() == Classifier.Bucket.VALUE)
			&& actual.stream().anyMatch(v -> v.bucket() == Classifier.Bucket.COLOUR));
		check("无源占位符会报告", TranslationDiagnostics.lore(LoreTranslation.plan(components("Each campfire can only be")))
			.stream().anyMatch(f -> f.verdict().bucket() == Classifier.Bucket.VALUE));
		check("相同数值出现两次不能只保留一次", !TranslationDiagnostics.lore(LoreTranslation.plan(components("Count 3 then 3"))).isEmpty());
		// The value check is about runtime values: what the translator could read in the record is theirs to keep or drop.
		check("记录自身英文里的数字不算丢失", TranslationDiagnostics.lore(LoreTranslation.plan(components("12:00 am-11:59 pm: Contest"))).isEmpty());
		check("5m 是五分钟不是五百万", TranslationDiagnostics.lore(LoreTranslation.plan(components("Place an orb for 5m buffing up to 10"))).isEmpty());
		check("词表整体翻译的值不算丢数字", TranslationDiagnostics.lore(LoreTranslation.plan(components("PASSIVE EVENT 2X POWDER"))).isEmpty());
		// A translated heading followed by an English body is an untranslated body, not a torn sentence.
		check("标题行后的英文正文不算续行", !incomplete(components("Ability: Some Title", "Gain +3 Defense for each enemy")));
		check("首字母全大写的名字行后不算续行", !incomplete(components("Topaz Crystal Hunter", "Find a Topaz Crystal in the")));
		check("列表符号开头的行不算续行", !incomplete(components("+1 Commission Slot", "■ Reduce cooldowns by 25%.")));
		check("乱码装饰字符不算小写开头", !incomplete(components("This grants 50 extra", "§ka§r Damage Bonus §ka")));
		check("真正折行的大写尾行仍会报告", incomplete(components("Grants +50 Mining Speed and +40", "Mining Fortune while in the Dwarven")));
		var wrong = Component.literal("    +10 Bits");
		var fixed = ChatLayout.plan(wrong, Translator.translateAvailable(wrong, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("显式居中与短缩进冲突只报告", fixed.issue() != null && Math.abs(center(fixed.text()) - 160) <= 3
			&& !fixed.issue().corrected());
		wrong = Component.literal("                                   Rewards");
		fixed = ChatLayout.plan(wrong, Translator.translateAvailable(wrong, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("显式左对齐与原文几何冲突只报告", fixed.issue() != null && left(fixed.text()) == 140);
		wrong = Component.literal("                                   Banner");
		fixed = ChatLayout.plan(wrong, Translator.translateAvailable(wrong, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("未声明的横幅会诊断并修复", fixed.issue() != null && Math.abs(center(fixed.text()) - 160) <= 3);
		wrong = Component.literal("[STATUE] Oruo:     Choice");
		fixed = ChatLayout.plan(wrong, Translator.translateAvailable(wrong, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("条件居中识别说话人后的填充", fixed.text().getString().stripLeading().equals("[STATUE] Oruo: 选项")
			&& Math.abs(center(fixed.text()) - 160) <= 3);
		wrong = Component.literal("[STATUE] Oruo: Whole");
		fixed = ChatLayout.plan(wrong, Translator.translateAvailable(wrong, Surface.CHAT), 320, DiagnosticsHarness::width);
		check("整行记录已包含说话人时不重复前缀", fixed.text().getString().stripLeading().equals("[STATUE] Oruo: 整句"));
		lorePipeline();
		repeatedFaults();
		TranslationHarness.installIndex(corpus);
	}

	private static void loreCache(TranslationIndex broken) throws Exception {
		var source = Component.literal("§7Mine §a3 §cblocks");
		var cached = TextCapture.inspectLore(List.of(source));
		check("相同 Lore 包复用整组检查", cached == TextCapture.inspectLore(components("§7Mine §a3 §cblocks")));
		var recoloured = TextCapture.inspectLore(components("§7Mine §b3 §cblocks"));
		check("颜色变化不会命中旧 Lore 缓存", recoloured != cached
			&& TranslationHarness.legacy(recoloured.getFirst().source().getFirst()).contains("§b3"));
		var bold = TextCapture.inspectLore(components("§7Mine §a3 §c§lblocks"));
		check("格式变化不会命中旧 Lore 缓存", bold != cached);
		var font = new FontDescription.Resource(Identifier.parse("skyzh:regression"));
		var customFont = TextCapture.inspectLore(List.of(
			Component.literal("§7Mine §a3 §cblocks").withStyle(style -> style.withFont(font))
		));
		check("字体变化不会命中旧 Lore 缓存", customFont != cached
			&& StyledText.of(customFont.getFirst().source().getFirst()).styleAt(0).getFont().equals(font));
		source.append(" changed after the packet");
		check("缓存证据不受原组件后续修改影响", cached.stream()
			.flatMap(observation -> observation.source().stream()).noneMatch(line -> line.getString().contains("changed")));

		for (int i = 1000; i < 1130; i++) {
			TextCapture.inspectLore(components("Mine " + i + " blocks"));
		}

		check("Lore 缓存有界并淘汰旧样本", cached != TextCapture.inspectLore(components("§7Mine §a3 §cblocks")));
		boolean skyBlockName = SkyZHConfig.get().translateSkyBlockName;

		try {
			SkyZHConfig.get().translateSkyBlockName = true;
			var named = TextCapture.inspectLore(components("Mine 3 SkyBlock blocks"));
			SkyZHConfig.get().translateSkyBlockName = false;
			var english = TextCapture.inspectLore(components("Mine 3 SkyBlock blocks"));
			check("玩法名设置改变会刷新缓存证据", named != english
				&& named.getFirst().diagnostic().evidence().getAsJsonArray("rendered_lines").toString().contains("空岛")
				&& english.getFirst().diagnostic().evidence().getAsJsonArray("rendered_lines").toString().contains("SkyBlock"));

			TranslationIndex repaired = new TranslationIndex();
			add(repaired, Surface.LORE, "fixed", "Mine %s blocks", "挖掘 %s 个方块", "");
			TranslationHarness.installIndex(repaired);
			check("语料重载后不复用旧缺数值诊断", TextCapture.inspectLore(components("Mine 3 blocks")).stream()
				.noneMatch(observation -> observation.diagnostic() != null
					&& observation.diagnostic().bucket() == Classifier.Bucket.VALUE));
		} finally {
			SkyZHConfig.get().translateSkyBlockName = skyBlockName;
			TranslationHarness.installIndex(broken);
		}
	}

	private static void repeatedFaults() throws Exception {
		Path folder = Files.createTempDirectory("skyzh-repeated-faults-");

		try {
			CaptureStore.root(folder);
			CaptureStore.clear(folder);
			CaptureStore.accept(new CaptureStore.Sighting(
				CaptureSurface.GUI_ITEM, "two-faults", new CaptureStore.Line(StyledText.of(Component.literal("§7Mine §a3 §cblocks"))),
				"Mining", "Dwarven Mines", "Menu", "Lore", 1
			));

			for (int i = 2; i <= 3; i++) {
				CaptureStore.accept(new CaptureStore.Sighting(
					CaptureSurface.GUI_ITEM, "two-faults", CaptureStore.Repeated.INSTANCE,
					"Mining", "Dwarven Mines", "Menu", "Lore", i
				));
			}

			CaptureStore.flush();

			for (String bucket : List.of("value", "colour")) {
				JsonObject document = JsonParser.parseString(Files.readString(
					folder.resolve(bucket + "/Mining/GUI_Item/Menu.json"))).getAsJsonObject();
				JsonObject capture = document.getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonObject("_capture");
				check("重复观测更新每个分类的次数 " + bucket, capture.get("count").getAsInt() == 3);
			}
		} finally {
			CaptureStore.clear(folder);
			Files.deleteIfExists(folder);
		}
	}

	private static void lorePipeline() throws Exception {
		Path folder = Files.createTempDirectory("skyzh-lore-pipeline-");
		try {
			CaptureStore.root(folder);
			CaptureStore.clear(folder);
			for (String menu : List.of("First", "Second")) {
				captureLore("Mining", menu, components("§7Mine §a3 §cblocks"));
				captureLore("Mining", menu, components("This grants 50 extra", "damage!"));
			}
			CaptureStore.flush();
			for (String bucket : List.of("value", "incomplete")) {
				Path path = folder.resolve(bucket + "/Mining/GUI_Lore/First.json");
				JsonArray records = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("lines");
				check("物品实际分类链只写一条 " + bucket, records.size() == 1);
				check("有证据诊断跨菜单去重 " + bucket, !Files.exists(folder.resolve(bucket + "/Mining/GUI_Lore/Second.json"))
					&& records.get(0).getAsJsonObject().getAsJsonObject("_capture").has("also_seen"));
			}
			check("数值检查不屏蔽颜色采集", Files.exists(folder.resolve("colour/Mining/GUI_Lore/First.json")));
			captureLore("Farming", "Second", components("§7Mine §a3 §cblocks"));
			CaptureStore.flush();
			check("诊断去重仍隔离玩法", Files.exists(folder.resolve("value/Farming/GUI_Lore/Second.json")));
		} finally {
			CaptureStore.clear(folder);
			try (var paths = Files.walk(folder)) {
				for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
			}
		}
	}

	private static void captureLore(String gameplay, String menu, List<Component> lines) {
		for (var observation : TextCapture.inspectLore(lines)) {
			var source = Component.empty();
			for (int i = 0; i < observation.source().size(); i++) {
				if (i > 0) {
					source.append("\n");
				}

				source.append(observation.source().get(i));
			}
			String note = menu + (observation.diagnostic() == null ? " Lore" : " / Item Lore");
			CaptureStore.Observation content = observation.diagnostic() == null
				? new CaptureStore.Line(StyledText.of(source), false)
				: new CaptureStore.Diagnosed(StyledText.of(source), observation.diagnostic());
			CaptureStore.accept(new CaptureStore.Sighting(
				observation.surface(), gameplay + menu + note + source.getString(), content,
				gameplay, "Dwarven Mines", menu, note, 1
			));
		}
	}

	private static void persist() throws Exception {
		Path folder = Files.createTempDirectory("skyzh-diagnostics-");
		try {
			CaptureStore.root(folder);
			CaptureStore.clear(folder);
			List<Component> source = components("This is the head", "and its English tail.");
			for (Classifier.Bucket bucket : List.of(Classifier.Bucket.LAYOUT, Classifier.Bucket.INCOMPLETE, Classifier.Bucket.VALUE)) {
				JsonObject evidence = TranslationDiagnostics.evidence("regression", source, components("已译首行", "and its English tail."), List.of());
				Classifier.Verdict verdict = TranslationDiagnostics.verdict(bucket, null, evidence);
				var sighting = new CaptureStore.Sighting(
					CaptureSurface.GUI_LORE, bucket.name(), new CaptureStore.Diagnosed(StyledText.of(source.getFirst()), verdict),
					"Mining", "Dwarven Mines", "Menu", "Item Lore", 1
				);
				Unplaced waiting = new Unplaced(4, 100);
				check("未知区域暂存 " + bucket, waiting.offer(sighting, null, 1).isEmpty());
				var placed = waiting.tick("Mining", 2).getFirst();
				check("暂存不丢诊断证据 " + bucket, placed.observation() == sighting.observation());
				CaptureStore.accept(placed);
				CaptureStore.accept(new CaptureStore.Sighting(
					placed.surface(), placed.key(), CaptureStore.Repeated.INSTANCE,
					placed.gameplay(), placed.area(), placed.name(), placed.note(), 2
				));
				CaptureStore.flush();
				Path file = folder.resolve(bucket.directory() + "/Mining/GUI_Lore/Menu.json");
				JsonObject doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				var records = doc.getAsJsonArray("lines");
				check("同一诊断去重 " + bucket, records.size() == 1);
				JsonObject stored = records.get(0).getAsJsonObject().getAsJsonObject("_capture");
				check("已诊断记录的重复观测更新次数 " + bucket, stored.get("count").getAsInt() == 2);
				check("保留完整两行证据 " + bucket, stored.getAsJsonObject("diagnostic").getAsJsonArray("original_lines").size() == 2);
			}
			var notices = CaptureAnnouncer.due(System.currentTimeMillis() + 10000);
			check("聊天报告包含三个新增分类", notices.stream().anyMatch(n -> n.getString().contains("排版异常")
				&& n.getString().contains("跨行残留") && n.getString().contains("数值异常")));
			Files.writeString(folder.resolve("keep.txt"), "keep");
			check("clear 清除三个新增分类", CaptureStore.clear(folder) == 3 && Files.exists(folder.resolve("keep.txt")));
		} finally {
			try (var paths = Files.walk(folder)) {
				for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
			}
		}
	}

	private static void add(TranslationIndex index, Surface surface, String id, String en, String zh, String layout) {
		add(index, surface, id, en, zh, layout, "");
	}

	private static void add(TranslationIndex index, Surface surface, String id, String en, String zh, String layout, String join) {
		TranslationEntry.Argument number = new TranslationEntry.Argument("number", "");
		add(index, surface, id, en, zh, layout, join, Map.of(1, number, 2, number));
	}

	private static void add(
		TranslationIndex index, Surface surface, String id, String en, String zh, String layout, String join,
		Map<Integer, TranslationEntry.Argument> arguments
	) {
		TranslationEntry entry = TranslationEntry.compile(new TranslationEntry.Definition(
			id, "regression", List.of(new TranslationEntry.Segment(en, zh)), arguments,
			new TranslationEntry.Options(false, layout, Set.of(), join)
		));
		index.add(surface, en, entry);
	}

	private static boolean incomplete(List<Component> lines) {
		return TranslationDiagnostics.lore(LoreTranslation.plan(lines)).stream()
			.anyMatch(finding -> finding.verdict().bucket() == Classifier.Bucket.INCOMPLETE);
	}
	private static List<Component> components(String... lines) {
		return Arrays.stream(lines).<Component>map(Component::literal).toList();
	}

	private static String plain(List<LoreTranslation.Unit> units) {
		return String.join("\n", units.stream().map(unit -> StyledText.of(unit.rendered()).plain()).toList());
	}

	private static String encoded(List<LoreTranslation.Unit> units) {
		return String.join("\n", units.stream().map(unit -> TranslationHarness.legacy(unit.rendered())).toList());
	}

	// Deterministic metrics for arithmetic tests; live runtime passes Font.width with resource-pack styles.
	private static int width(Component text) {
		StyledText styled = StyledText.of(text);
		int width = 0;

		for (int i = 0; i < styled.length(); i++) {
			char c = styled.plain().charAt(i);
			width += switch (c) {
				case ' ', 't', 'I' -> 4;
				case 'i', '.', ',', '!' -> 2;
				case 'l' -> 3;
				case 'f' -> 5;
				default -> c > 127 ? 8 : 6;
			};

			if (styled.styleAt(i).isBold()) {
				width++;
			}
		}

		return width;
	}

	private static int left(Component text) {
		StyledText styled = StyledText.of(text);
		int i = 0;

		while (i < styled.length() && styled.plain().charAt(i) == ' ') {
			i++;
		}

		return width(styled.slice(0, i));
	}

	private static double center(Component text) {
		return left(text) + (width(text) - left(text)) / 2.0;
	}

	private static void check(String title, boolean ok) {
		checks++;

		if (!ok) {
			failures.add(title);
		}
	}
}
