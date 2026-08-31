package io.github.bingkkni.skyzh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.Capture;
import io.github.bingkkni.skyzh.text.Glyphs;
import io.github.bingkkni.skyzh.text.OriginalLabel;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Runs the matching engine over the real corpus with no game around it, and fails the build if any
 * expected line comes out wrong.
 *
 * <p>Worth having because the corpus is the part of this project that will keep changing: records
 * get translated, Hypixel recolours a line, someone adds a {@code segments} array. None of that can
 * be checked by launching the game and squinting at a tooltip, and all of it can be checked here in
 * a second. Run with {@code ./gradlew checkTranslations}.
 *
 * <p>What it cannot cover is anything that needs a {@code Font} — line wrapping and centring both
 * measure pixels, and pixels need a loaded font atlas. Those are exercised in game.
 */
public final class TranslationHarness {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args.length > 0 ? args[0] : "original_text");
		Map<String, JsonObject> files = readCorpus(root);

		System.out.println("语料文件: " + files.size());
		TranslationIndex index = TranslationLoader.compile(files);
		System.out.println("可用记录总数: " + index.size());

		for (Surface surface : Surface.values()) {
			System.out.printf("  %-12s %d%n", surface, index.size(surface));
		}

		System.out.println();
		installIndex(index);

		// ---- every finished record still answers for its own text ----
		checkNoRecordIsShadowed(index, files);
		checkNoRivalRecords(files);
		checkNoDuplicateIds(files);
		checkPhrasePlaceholdersHaveExamples(files);

		// ---- a segments array has to be a faithful split of the record's own text ----
		checkSegmentsSpellTheText(files);
		checkNoHalfTranslatedSegments(files);
		checkSegmentOrderIsAPermutation(files);
		checkOrderedSegmentsSpellTheTranslation(files);
		checkBangSpacing(files);

		// ---- the two places an event's name is written have to agree ----
		checkEventNamesAgree(files);

		// ---- no sentence is left half Chinese and half English ----
		checkNoBrokenContinuation(files);
		checkContinuationOnlyInLore(files);
		// ---- ...and no sentence is drawn twice, which is the same edit gone the other way ----
		checkNoDuplicatedSentence(files);

		// ---- colour reconstruction, the known-issue-3 cases ----
		check("Lore 两段色 + 占位符", "§7Mining Speed: §6+450", Surface.ITEM, "§7挖掘速度: §6+450");
		check("Lore 复合挖掘速度值", "§7Mining Speed: §6+3,135 §9[+500] [+50] §d(+75) (+100)", Surface.ITEM,
			"§7挖掘速度: §6+3,135 §9[+500] [+50] §d(+75) (+100)");
		check("Lore 复合挖掘时运值", "§7Mining Fortune: §6+340 §9[+5] (+20) §d(+50)", Surface.ITEM,
			"§7挖掘时运: §6+340 §9[+5] (+20) §d(+50)");
		check("Lore 伤害行", "§7Damage: §c+65", Surface.ITEM, "§7伤害: §c+65");
		check("Lore 三段色 + 百分号", "§7Grants §2+20% Mithril Powder§7.", Surface.ITEM, "§7额外获得 §2+20% 秘银粉末§7。");
		check("Lore 双占位符", "§7Fuel: §23,000§8/3k", Surface.ITEM, "§7燃料: §23,000§8/3k");
		check("稀有度 + 类型标签", "§9§lRARE DRILL", Surface.ITEM, "§9§l稀有钻头");
		check("整行单色", "§8This item can be reforged!", Surface.ITEM, "§8该物品可以重铸!");
		check("跨行句首行", "§7Increases fuel capacity with part", Surface.ITEM, "§7装上配件后增加燃料容量。");
		check("Compact 效果首行合并完整句",
			"§7Gain §3+1☯ Mining Wisdom §7and a §a0.25%", Surface.ITEM,
			"§7获得 §3+1☯ 挖掘智慧§7,并有 §a0.25% 的概率额外掉落附魔物品。");
		check("宾果蓝染料颜色尾行不含 to", "§7to §1#002FA7§7!", Surface.ITEM, "§1#002FA7§7!");
		check("山峦之心限制行", "§4❣ §cRequires §5Heart of the Mountain Tier 2§c.", Surface.ITEM,
			"§4❣ §c需要§5山峦之心2级§c。");
		// NEU gives the exact empty-slot line, so it is a record of its own rather than a value caught
		// by the looser "Fuel Tank: %s" template — which still covers the state with a part in it.
		check("配件槽空位状态", "§7Fuel Tank: §cNot Installed", Surface.ITEM, "§7燃料箱: §c未安装");
		check("物品名整名翻译", "§9Mithril Drill SX-R226", Surface.ITEM, "§9秘银钻头 SX-R226");
		checkTooltipName("重铸前缀保留样式并翻译整名", "§6Fleet §dTitanium Drill DR-X655",
			"§6迅捷 §d钛钻头 DR-X655", false);
		checkTooltipName("重铸物品名对照保留原始整名", "§6Fleet §dTitanium Drill DR-X655",
			"§6迅捷 §d钛钻头 DR-X655§6（Fleet Titanium Drill DR-X655）", true);
		checkTooltipName("重铸前缀不猜测未收录物品", "§6Fleet Unknown Drill", "§6Fleet Unknown Drill", false);
		check("稀有度+类型标签", "§6§lLEGENDARY HELMET", Surface.ITEM, "§6§l传说头盔");
		checkNoMatch("宽泛 Requires 模板不再误吃普通 Lore", "§7Requires dirt or soil nearby so", Surface.ITEM);

		// ---- the colour a translation is painted in is the colour of the words, not of the value ----
		// SkyBlock colours a value differently from the sentence around it constantly, and a record
		// whose template opens with a placeholder opens inside that value.
		check("片段以占位符开头时用词本身的颜色", "§b§lLapis §6§lCORPSE LOOT!", Surface.CHAT,
			"§b§l青金石§6§l尸体战利品!");
		check("标签与数值各自保留颜色", "§7Fossil Dust: §a1,234", Surface.SCOREBOARD, "§7化石粉尘: §a1,234");

		checkColourLoss("数值自带颜色不算颜色丢失", "Fossil Dust: 1,234", "§7Fossil Dust: §a1,234",
			Surface.SCOREBOARD, false);
		checkColourLoss("词句本身中途变色才算颜色丢失", "This item can be reforged!",
			"§8This item can be §creforged!", Surface.ITEM, true);

		// Hypixel splits a line on a tooltip as readily as on a colour: the sacks message sends
		// " items" and the "." after it as two runs of the same yellow, the first of which carries
		// the "Added items:" hover. Judged by whole-Style equality that reads as a colour change
		// inside one fragment, and the capture then files a record whose segments are already right
		// into the colour pile — with advice to split them again at a boundary that has no colour on
		// either side of it. Only what is drawn counts as a colour.
		checkColourLossComponent("同色但只有一半带悬浮提示,不算颜色丢失", "This item can be reforged!",
			Component.empty()
				.append(Component.literal("This item can be ")
					.setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withInsertion("hover")))
				.append(Component.literal("reforged!").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))),
			Surface.ITEM, false);

		// ---- placeholders carry proper nouns through untouched ----
		check("NPC 台词占位符", "Today the King is Brammor.", Surface.CHAT, "今天当值的国王是 Brammor。");
		check("NPC 台词纯文本", "Hello Adventurer!", Surface.CHAT, "你好,冒险者!");

		// ---- padding: indentation is kept, the sentence still matches ----
		check("带缩进的台词", "   Hello Adventurer!", Surface.CHAT, "   你好,冒险者!");

		// ---- the speaker tag in front of dialogue: peeled off to match, put back to draw ----
		// This is the shape every NPC line actually reaches the screen in. Matching whole lines
		// against a corpus of bare sentences missed all of them, which is why dialogue stayed
		// English in game while its records sat there translated.
		check("台词带 [NPC] 前缀", "[NPC] King: Hello Adventurer!", Surface.CHAT,
			"[NPC] King: 你好,冒险者!");
		check("NPC 名字有空格", "[NPC] Banker Broadjaw: You're a long way from home!", Surface.CHAT,
			"[NPC] Banker Broadjaw: 你在离家很远的地方啊!");
		check("前缀 + 占位符", "[NPC] King: Today the King is Brammor.", Surface.CHAT,
			"[NPC] King: 今天当值的国王是 Brammor。");
		check("前缀保留自己的颜色", "§e[NPC] §eKing§f: §fHello Adventurer!", Surface.CHAT,
			"§e[NPC] King§f: 你好,冒险者!");
		check("前缀 + 缩进", "  [NPC] King:  Hello Adventurer!", Surface.CHAT,
			"  [NPC] King:  你好,冒险者!");
		checkNoMatch("玩家发言不当作台词", "[MVP+] Someone: Hello Adventurer!", Surface.CHAT);
		checkNoMatch("只有前缀没有台词时不匹配", "[NPC] King: ", Surface.CHAT);

		// ---- tab list rows: "<label>: <value>", where the corpus only has the label ----
		check("Tab 标签整行", "Commissions:", Surface.TABLIST, "委托:");
		check("Tab 标签带数值", " Commissions: 3/5", Surface.TABLIST, " 委托: 3/5");
		check("Tab 标签在语料里不带冒号", "Mining Speed: 450", Surface.TABLIST, "挖掘速度: 450");
		check("Tab 数值保留自己的颜色", "§9Mining Speed: §a450", Surface.TABLIST, "§9挖掘速度: §a450");
		checkNoMatch("Tab 行里的玩家名不匹配", "[MVP+] Someone", Surface.TABLIST);

		// A tab row is a label and a value, and both halves go through the term table when no record
		// answers for them — which is how the twenty-odd commission rows are Chinese without a
		// TabList record each saying what _shared/Terms.json already says.
		checkRow("Tab 行的数值也翻译", " §fCorpse Looter: §aDONE", " §f尸体搜刮者: §a已完成");
		// The commission rows from the 2026-08-27 session, which the capture filed as untranslated:
		// it decides that bucket by whether a *record* answered, and these are answered by the term
		// table's commission names through translateRow instead. Pinned here because they are on
		// screen the entire time a player is in the Dwarven Mines — between them the rows below were
		// seen some 40,000 times in two hours — and nothing else would notice if that path regressed.
		checkRow("Tab 委托行:区域 + 矿物", " §fRoyal Mines Titanium: §640%", " §f皇家矿区 - 钛: §640%");
		checkRow("Tab 委托行:两词区域名", " §fCliffside Veins Mithril: §c0%", " §f崖壁矿脉 - 秘银: §c0%");
		checkRow("Tab 委托行:收集员", " §fGlacite Collector: §c14.9%", " §f极冰收集员: §c14.9%");
		checkRow("Tab 委托行:宝石收集员保留宝石名", " §fCitrine Gemstone Collector: §cDONE",
			" §fCitrine 宝石收集员: §c已完成");
		checkRow("Tab 委托行:猎手", " §fGoblin Raid Slayer: §c0%", " §f哥布林突袭猎手: §c0%");
		checkRow("记录管标签、词表管数值", " §7Lapis§f: §c§lNOT LOOTED", " §7青金石§f: §c§l未搜刮");
		checkRow("词表里没有的标签保持英文", " §fOpal: §a✔ Found", " §fOpal: §a✔ 已找到");
		checkRow("数字数值原样留着", " §fCorpse Looter: §c0%", " §f尸体搜刮者: §c0%");
		checkRow("两半都查不到就原样不动", " §fSomething Nobody Wrote: §a42", " §fSomething Nobody Wrote: §a42");
		checkRow("没有冒号的行照常走记录", "§e§lSkills:", "§e§l技能:");

		// Two raw placeholders side by side split at the first space they can, so a two-word minion
		// type came out scrambled rather than merely untranslated. Saying the second half is a Roman
		// numeral is what makes the first half stop in the right place.
		checkRow("小人行:单词种类名", " 18x Snow XI §7[§aACTIVE§7]", " 18x 雪小人 XI §7[§a运行中§7]");
		checkRow("小人行:两个词的种类名", " 19x Hard Stone XII §7[§aACTIVE§7]",
			" 19x 硬石小人 XII §7[§a运行中§7]");
		checkRow("小人行:词表里没有的种类保持英文", " 2x Enderman XII §7[§cFULL§7]",
			" 2x Enderman 小人 XII §7[§c已满§7]");

		// A place named after its owner cannot be listed — the owner is a player name — so the table
		// holds what kind of place it is and the engine takes the possessive apart.
		check("某人的博物馆", "§7⏣ §3inkkni's Museum", Surface.ACTION_BAR, "§7⏣ §3inkkni 的博物馆");
		check("表里写全了的所有格不拆开", "§7⏣ §bRampart's Quarry", Surface.ACTION_BAR, "§7⏣ §b壁垒采石场");
		check("大厅地点按 location_name 翻译", "§7⏣ §eFarm", Surface.ACTION_BAR, "§7⏣ §e农场");
		check("欢迎语中的地点按 location_name 翻译", "Welcome to the §bElection Room§f!", Surface.CHAT,
			"欢迎来到§b选举室§f!");
		check("Mining 全图活动无时长写法", "§e§lPASSIVE EVENT §9§lGONE WITH THE WIND", Surface.BOSS_BAR,
			"§e§l活动 §9§l随风而逝");
		check("Hub 任务 BossBar", "§fObjective: §eGive Rose Red Dye to Marco", Surface.BOSS_BAR,
			"§f目标: §e把玫瑰红染料交给 Marco");
		checkRow("私人岛小人数量", "§b§lMinions: §f19§7/§r19", "§b§l小人: §f19§7/19");

		// A decimal point is not the end of a sentence. Before this the action bar's skill readout was
		// English for exactly the players who had reached max level.
		check("满级后括号里是小数百分比", "§3+10.9 Combat (20.34%)", Surface.ACTION_BAR,
			"§3+10.9 战斗 (20.34%)");
		check("满级前括号里是进度", "§3+112 Combat (138,556,517/0)", Surface.ACTION_BAR,
			"§3+112 战斗 (138,556,517/0)");

		// Hypixel marks the entry the cursor is on, and the text after the mark is the text the corpus
		// already has under the item's own name.
		check("行首的项目符号被剥掉再查", "§8 ■ §7Gain §a+15% §7more Powder while mining.", Surface.ITEM,
			"§8 ■ §7挖矿时§a多获得 +15% §7的粉末。");

		// ---- the action bar is a row of widgets, translated one at a time ----
		// SkyBlock lays five spaces between them. Looked up whole the line matches nothing, because
		// every number in it changes as the player walks; and the gaps have to come back untouched,
		// or the widgets stop lining up where SkyBlock put them.
		// The ⏣ goes back out as the server's own icon glyph, not as the symbol the record was
		// written with — see Glyphs. That is the point of writing records with the plain symbol.
		checkWidgets("动作栏逐部件翻译",
			"§62,610/2,235\uE010     §7\uE067 §bDwarven Base Camp     §265,321/100k Drill Fuel",
			"§62,610/2,235\uE010     §7\uE067 §b矮人营地     §265,321/100k 钻头燃料");
		checkWidgets("没有记录的部件原样留着",
			"§62,610/2,235\uE010     §2Something Nobody Wrote Down",
			"§62,610/2,235\uE010     §2Something Nobody Wrote Down");
		checkWidgets("单条动作栏消息不受切分影响", "§cPICK IT UP!", "§c捡起来!");
		// A record that spells the whole padded line out still wins over the shape.
		checkWidgets("整行有记录时不切开", "   §cPICK IT UP!   ", "   §c捡起来!   ");

		// ---- exact live event strings whose decoration or value shape changes the match ----
		check("新年庆典横幅保留完整装饰",
			"§b§k§lA§r §e§lEvent§r: §aNew Year's Celebration! §r§b§k§lA", Surface.CHAT,
			"§b§l§kA §e§l活动: §a新年庆典! §b§l§kA");
		check("新年庆典村庄行带装饰前缀",
			"§b§k§lA§r §aEveryone is having a party in the §bVillage§a!", Surface.CHAT,
			"§b§l§kA §a大家都在§b村庄§a里开派对!");
		check("新年庆典点击提示保留完整装饰",
			"§b§k§lA§r §e§lCLICK HERE §eto get your §c§lSPECIAL §enew year cake!", Surface.CHAT,
			"§b§l§kA §e§l点击这里§e领取你的§c§l特殊§e新年蛋糕!");
		check("新年庆典倒计时单数 day", "§aThe §dNew Year's Celebration §aevent is starting in §b1 §aday!",
			Surface.CHAT, "§d新年庆典§a活动将在 §b1§a 天后开始!");
		check("新年庆典倒计时复数 days", "§aThe §dNew Year's Celebration §aevent is starting in §b2§a days!",
			Surface.CHAT, "§d新年庆典§a活动将在 §b2§a 天后开始!");
		check("雪炮装填完成显示鼠标右键", "§e§lRIGHT-CLICK §fto §6§lFIRE", Surface.ACTION_BAR,
			"§e§l按鼠标右键§6§l开炮");
		check("雪炮装填中显示倒计时", "§e§l2.2s §fto §6§lFIRE", Surface.ACTION_BAR,
			"§e§l2.2秒后§6§l可开炮");
		check("极冰隧道保底宝石数量不是装备槽", "§7Gemstones: §e8-10", Surface.ITEM,
			"§7宝石: §e8-10");
		check("极冰隧道保底标题", "Glacite Tunnels Pity", Surface.GUI_TITLE, "极冰隧道保底进度");
		check("保底总览标题", "Pity", Surface.GUI_TITLE, "保底进度");

		// ---- the rows that are on screen every frame, from the 2026-08-20 session ----
		// The day number gets air on both sides. Written together — 立秋20日 — the three glyphs and the
		// two digits run into one another on a sidebar that is already the densest text on screen.
		check("侧边栏日期", "§f Early Autumn 20th", Surface.SCOREBOARD, "§f 立秋 20 日");
		check("序数后缀换一种也认得", "§f Late Winter 1st", Surface.SCOREBOARD, "§f 大寒 1 日");
		// The clock keeps SkyBlock's 12-hour time and replaces am/pm with the Chinese time-of-day word,
		// which is how the language actually says a clock time. English 12-hour time is ambiguous at
		// twelve — 12:30am is the middle of the night — and 凌晨/上午/中午/下午/晚上 each cover one stretch,
		// so the ambiguity does not survive the translation. The engine cannot do arithmetic, so the
		// hour and its word are literal in the template, one record per hour; the minute and the
		// day/night icon are placeholders and the icon keeps the colour the server drew it in.
		check("下午", "§e §73:30pm §e☀", Surface.SCOREBOARD, "§e §7下午 3:30 §e☀");
		check("凌晨", "§b §72:50am §b☽", Surface.SCOREBOARD, "§b §7凌晨 2:50 §b☽");
		check("半夜的 12 点是凌晨", "§b §712:20am §b☽", Surface.SCOREBOARD, "§b §7凌晨 12:20 §b☽");
		check("白天的 12 点是中午", "§e §712:00pm §e☀", Surface.SCOREBOARD, "§e §7中午 12:00 §e☀");
		check("傍晚之后是晚上", "§e §77:10pm §e☽", Surface.SCOREBOARD, "§e §7晚上 7:10 §e☽");
		check("天亮之后是上午", "§e §79:20am §e☀", Surface.SCOREBOARD, "§e §7上午 9:20 §e☀");
		// The leading space and the core are both §7, so the legacy string does not repeat the code.
		check("矿井里不带图标的写法", "§7 §72:50am", Surface.SCOREBOARD, "§7 凌晨 2:50");
		// 1: and 11: must not be confused for one another — the pattern is anchored, but this is the
		// kind of thing that only stays true while somebody checks.
		check("11 点不会被 1 点那条抢走", "§b §711:40pm §b☽", Surface.SCOREBOARD, "§b §7晚上 11:40 §b☽");
		check("侧边栏硬币", "§fPurse: §67,825,468", Surface.SCOREBOARD, "§f硬币: §67,825,468");
		// The bracket and the plus belong to the gain, not to the label. Recorded as one flat line they
		// were painted in the label's white while the number beside them stayed the server's colour,
		// which is what a white "(+" next to a coloured 5 on the sidebar was.
		// The space between the two moves into the gain's colour, which nobody can see: a space has no
		// glyph. What matters is that the bracket does not stay white.
		check("硬币变动的括号跟着增减量的颜色", "§fPurse: §67,825,468 §a(+5)", Surface.SCOREBOARD,
			"§f硬币: §67,825,468§a (+5)");
		// Same shape, same fix: the snowflake is part of the value and is drawn in the value's colour.
		check("寒冷值的图标和数值同色", "§fCold: §b-20❄", Surface.SCOREBOARD, "§f寒冷: §b-20❄");
		check("炙热值的图标和数值同色", "§fHeat: §cIMMUNE♨", Surface.SCOREBOARD, "§f炙热: §c免疫♨");
		check("侧边栏点券", "§fBits: §b53,998", Surface.SCOREBOARD, "§f点券: §b53,998");
		// Hypixel writes the noun in agreement with the count, so a new profile says "Bit: 1".
		// Chinese has no plural, so one record answers for both spellings — see eitherNumber.
		check("单数写法也认得", "§fBits: §b1", Surface.SCOREBOARD, "§f点券: §b1");
		check("Bit: 1 也认得", "§fBit: §b1", Surface.SCOREBOARD, "§f点券: §b1");
		check("收纳袋只收进一件时", "§6[Sacks] §a+1§e item§e.§8 (Last 1s.)", Surface.CHAT,
			"§6[收纳袋] §a+1§e 件物品。§8(最近 1 秒)");
		check("抽奖只投一张票时", "§eYou registered §a1 ticket §ein the raffle event!", Surface.CHAT,
			"§e你在抽奖箱活动里投了 §a1 张票券§e!");
		// And the loosening must not reach across a word: "Bi" is not "Bit" with the s taken off.
		checkNoMatch("不会把词本身吃掉一截", "§fBi: §b1", Surface.SCOREBOARD);
		check("侧边栏粉末行(行里没有 Powder 这个词)", "§2᠅ §fMithril: §23,661,900", Surface.SCOREBOARD,
			"§2᠅ §f秘银粉末: §23,661,900");
		check("Tab 技能行", " Mining 60: §c§lMAX", Surface.TABLIST, " 挖矿 60 级: §c§lMAX");
		check("Tab 小节标题", "§e§lSkills:", Surface.TABLIST, "§e§l技能:");
		check("Tab Upgrades 标题", "Upgrades", Surface.TABLIST, "升级");
		check("Tab Info 标题", "Info", Surface.TABLIST, "信息");
		check("Tab Gems 保留英文", "Gems:", Surface.TABLIST, "Gems:");
		check("聊天里的收纳袋合并提示",
			"§6[Sacks] §a+3,598§e items§e.§8 (Last 30s.)", Surface.CHAT,
			"§6[收纳袋] §a+3,598§e 件物品。§8(最近 30 秒)");
		// The version number has a full stop in it, which is exactly what a `raw` placeholder refuses.
		// SkyBlock itself is swapped by translateSkyBlockName after the record has done its part.
		check("版本公告里的版本号", "§eLatest update: §bSkyBlock v0.27 §e§lCLICK", Surface.CHAT,
			"§e最新更新: §b空岛生存 v0.27 §e§l点击查看");

		// ---- surface isolation ----
		checkNoMatch("聊天语料不答界面标题", "Hello Adventurer!", Surface.GUI_TITLE);
		checkNoMatch("物品语料不答聊天", "§8This item can be reforged!", Surface.CHAT);
		checkNoMatch("Tab 语料不答聊天", "Mining Speed: 450", Surface.CHAT);
		// Peeling a value off after a colon is a tab-list shape only. The sidebar is full of
		// "Purse: 1,234" lines whose labels would start matching tab-list records otherwise.
		checkNoMatch("计分板不做标签剥离", "Mining Speed: 450", Surface.SCOREBOARD);

		// ---- untranslated text passes through unchanged ----
		check("未翻译的行保持英文", "§7Some Line Nobody Translated", Surface.ITEM, "§7Some Line Nobody Translated");

		// ---- component-supplied colours, not legacy codes ----
		Component nested = Component.empty()
			.append(Component.literal("Mining Speed: ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
			.append(Component.literal("+450").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
		checkComponent("组件式着色(非 §码)", nested, Surface.ITEM, "§7挖掘速度: §6+450");

		// ---- a placeholder holds a value, not a sentence (Capture) ----
		// Hypixel wraps a long lore line, and the commission-name record "%s Mithril" used to fit the
		// first half of "Slay 1 Boss Corleone in the Mithril / Deposits." — drawing a sentence about a
		// boss as "Slay 1 Boss Corleone in the秘银".
		checkNoMatch("委托名模板不吞下半句话", "Slay 1 Boss Corleone in the Mithril", Surface.ITEM);
		checkNoMatch("区域名占位符不接受小句", "Mine 5 blocks in the Titanium", Surface.ITEM);
		check("真正的区域名照常匹配", "Upper Mines Titanium", Surface.ITEM, "上层矿区 - 钛");
		Map<String, String> rabbitEmployees = Map.of(
			"Bro", "兄弟", "Cousin", "表亲", "Sis", "姐妹", "Daddy", "爸爸",
			"Granny", "奶奶", "Uncle", "叔叔", "Dog", "狗狗"
		);
		for (Map.Entry<String, String> employee : rabbitEmployees.entrySet()) {
			check("兔子员工称谓: " + employee.getKey(),
				"Rabbit " + employee.getKey() + " - [220] Board Member", Surface.ITEM,
				"兔子" + employee.getValue() + " - [220] 董事会成员");
		}

		// ---- values the term table knows, and the space around the ones it does not ----
		check("区域名查词表译出", "Royal Mines Mithril", Surface.ITEM, "皇家矿区 - 秘银");
		check("宝石名保持英文并自动补空格", "Amber Gemstone Collector", Surface.ITEM, "Amber 宝石收集员");
		check("数字和汉字之间不补空格", "- 1,000 Glacite Powder", Surface.ITEM, "- 1,000极冰粉末");
		// A player's name is never translated — the term table is not even consulted for that kind of
		// placeholder — so this is the case the seam rule exists for.
		check("英文值和汉字之间补空格", "inkkni has obtained [Lvl 1] Bal!", Surface.CHAT,
			"inkkni 获得了 [1 级] Bal!");

		// ---- NPC dialogue, the shapes it actually arrives in ----
		// The corpus stores one game line per record. It used to store several lines run together,
		// which matches nothing; these check the split-out versions answer for the real thing.
		check("教团台词按行匹配", "§e[NPC] Dalir§f: Wow there, you found us!", Surface.CHAT,
			"§e[NPC] Dalir§f: 哇哦,你找到我们了!");
		check("语序调换时空出的颜色段不画", "§fLong live the §dFallen Star§f!!!!", Surface.CHAT,
			"§d陨落之星§f万岁!!!!");
		check("同一句里人名保持英文并两边留白", "§fRight, the master plan. §5Thondin§f, care to update us?",
			Surface.CHAT, "§f对了,那个宏伟计划。§5Thondin§f,你来给大家讲讲进展?");

		// ---- the SkyBlock name switch: only where the result cannot come out half-English ----
		// On a line nobody has translated, the word is swapped only when it is the whole of that
		// line's English. Anything looser produced "空岛生存 Level 42" all over the parts of SkyBlock
		// the corpus has not reached, which is the complaint this rule exists to answer.
		check("整行只有它时替换", "§6SKYBLOCK", Surface.CHAT, "§6空岛生存");
		// The sample has to be a line the corpus does *not* cover, or this checks the wrong path.
		check("句中还有别的英文词时保持英文", "§eYour SkyBlock profile was saved", Surface.CHAT,
			"§eYour SkyBlock profile was saved");
		// The same line once the corpus does cover it: the swap runs over the finished Chinese, which
		// is the documented way to make a particular line translate sooner.
		check("已翻译的行照常替换", "§eWelcome to Hypixel SkyBlock!", Surface.CHAT,
			"§e欢迎来到 Hypixel 空岛生存!");
		check("未翻译的标题保持英文", "SkyBlock Warp Menu", Surface.GUI_TITLE, "SkyBlock Warp Menu");
		// And the same title once a record covers it: the swap runs over the finished Chinese.
		check("已翻译的标题照常替换", "SkyBlock Menu", Surface.GUI_TITLE, "空岛菜单");
		check("带英文后缀的标题保持英文", "§6§lSKYBLOCK CO-OP", Surface.SCOREBOARD, "§6§lSKYBLOCK CO-OP");

		// ---- the name substitution itself: spacing and the compound short form ----
		// These lines are all Chinese apart from the word, which is what a finished translation looks
		// like — the translator keeps "SkyBlock" in the zh so the config switch still decides.
		check("复合词用简称并去掉多余空格", "SkyBlock 菜单", Surface.CHAT, "空岛菜单");
		check("两侧汉字的空格都去掉", "你的 SkyBlock 等级", Surface.CHAT, "你的空岛等级");
		check("数字一侧的空格保留", "+150 SkyBlock 经验", Surface.CHAT, "+150 空岛经验");
		check("译者没写空格时同样成立", "SkyBlock菜单", Surface.CHAT, "空岛菜单");
		check("助词不算复合词,仍用全称", "在 SkyBlock 中", Surface.CHAT, "在空岛生存中");
		check("全大写同样替换", "§6SKYBLOCK", Surface.CHAT, "§6空岛生存");
		check("替换继承原颜色", "§6SkyBlock §7等级", Surface.CHAT, "§6空岛§7等级");

		// ---- the scoreboard title's shimmer: per-character colour must survive the alphabet change ----
		// Hypixel re-sends the title each tick with the highlight one letter further along. Eight
		// letters become four characters, so each character covers two letters and the highlight is
		// held for two frames rather than dropped on the odd ones.
		check("高光在第 1 个字母", "§e§lS§6§lKYBLOCK", Surface.SCOREBOARD, "§e§l空§6§l岛生存");
		check("高光在第 2 个字母(同一格,构成停留)", "§6§lS§e§lK§6§lYBLOCK", Surface.SCOREBOARD, "§e§l空§6§l岛生存");
		check("高光扫到第 5 个字母", "§6§lSKYB§e§lL§6§lOCK", Surface.SCOREBOARD, "§6§l空岛§e§l生§6§l存");
		check("高光扫到末字母", "§6§lSKYBLOC§e§lK", Surface.SCOREBOARD, "§6§l空岛生§e§l存");
		check("整词同色时不产生多余分段", "§6§lSKYBLOCK", Surface.SCOREBOARD, "§6§l空岛生存");

		// ---- container titles: the padding is stripped for matching and reported for re-centring ----
		check("界面标题", "Select Process", Surface.GUI_TITLE, "选择工序");
		Translator.Result padded = Translator.translate(Component.literal("      Select Process"), Surface.GUI_TITLE);
		report("服务器居中空格被识别出来(供重算居中)", padded.matched() && padded.centredByServer(),
			"matched=" + padded.matched() + " centred=" + padded.centredByServer());

		Translator.Result flush = Translator.translate(Component.literal("Select Process"), Surface.GUI_TITLE);
		report("没有空格的标题不当作居中", flush.matched() && !flush.centredByServer(),
			"matched=" + flush.matched() + " centred=" + flush.centredByServer());

		// A speaker tag is not centring padding: peeling one must not make chat lines get re-centred
		// the way container titles are.
		Translator.Result tagged = Translator.translate(
			Component.literal("[NPC] King: Hello Adventurer!"), Surface.CHAT
		);
		report("前缀不被误当成居中空格", tagged.matched() && !tagged.centredByServer(),
			"matched=" + tagged.matched() + " centred=" + tagged.centredByServer());

		// ---- multi-line blocks (the tab list header and footer arrive as one component) ----
		String block = legacy(Translator.translateBlock(
			Component.literal("Commissions:\nHello Adventurer!"), Surface.TABLIST
		));
		report("多行整块逐行翻译", "委托:\nHello Adventurer!".equals(block), "实际 [" + block + "]");

		// ---- continuation marker ----
		TranslationEntry tail = index.lookup(Surface.ITEM, "installed.");
		report("续行被识别为 continuation", tail != null && tail.continuation(),
			tail == null ? "未匹配到 installed." : "continuation=" + tail.continuation());
		TranslationEntry compactTail = index.lookup(Surface.ITEM, "chance to drop an enchanted item.");
		report("Compact 英文尾行被识别为 continuation", compactTail != null && compactTail.continuation(),
			compactTail == null ? "未匹配到 Compact 尾行" : "continuation=" + compactTail.continuation());

		// ---- the server's icon font, and the symbols the corpus is written with ----
		// SkyBlock sends private-use codepoints where the wiki (and older NEU dumps) wrote ❤ ☘ ⸕.
		// Both spellings have to find the same record, and the icon has to be drawn back the way the
		// server sent it rather than as the symbol a translator typed. See Glyphs.
		check("私用区图标能匹配上通用符号写的记录",
			"MAYHEM! You received a \uE006 Cold Resistance buff from your Mineshaft Mayhem perk!",
			Surface.CHAT, "矿井狂乱! 你的「矿井狂乱」天赋给了你 \uE006 抗寒增益!");
		check("通用符号写的原文照样匹配",
			"MAYHEM! You received a ❄ Cold Resistance buff from your Mineshaft Mayhem perk!",
			Surface.CHAT, "矿井狂乱! 你的「矿井狂乱」天赋给了你 ❄ 抗寒增益!");

		// ---- a symbol is as identifying as a word, and the sidebar's zone row has nothing else ----
		check("符号开头的模板可以只有占位符", "§7⏣ §2Dwarven Mines", Surface.SCOREBOARD, "§7⏣ §2矮人矿山");

		// ---- Hypixel 藏在词中间的 §q ----
		// 计分板的每一行必须是互不相同的字符串,Hypixel 的办法是往里塞一个 § 加一个字母:位置每行不同,
		// 字母也不是 Minecraft 认得的颜色码。渲染器会把「§ 加任意一个字符」整对吃掉,所以屏幕上看不见;
		// 只按"认得的颜色码"去剥,剥完就成了 W§qind Compass,和语料里的 Wind Compass 对不上——
		// 计分板上这一行一直没被翻译,就是这么来的,和记录、和钩子位置都无关。
		check("藏在词中间的 §q 不算内容", "§9W§qind Compass", Surface.SCOREBOARD, "§9风向罗盘");
		check("藏在行尾的 §q 也不算", "§7⏣ §2Dwarven Mines§q", Surface.SCOREBOARD, "§7⏣ §2矮人矿山");
		check("§q 把地名劈成两半也照样匹配", "§7⏣ §2Dwarven M§qines", Surface.SCOREBOARD, "§7⏣ §2矮人矿山");


		// ---- a sentence whose Chinese says the same things in a different order ----
		// English names the reward first and the kill last; Chinese names the kill first. Each colour
		// run keeps its own placeholder and its own colour on the way past — the gold-bold mob name is
		// still gold and bold after it has moved to the front of the sentence.
		check("语序调换后每段仍带着自己的颜色和占位符",
			"§aYou received §2750 ᠅ Mithril Powder §afrom killing a §6§lGolden Goblin§a!", Surface.CHAT,
			"§a你通过击杀§6§l黄金哥布林§a获得了 §2750 ᠅ 秘银粉末§a!");
		check("矿井狂乱那一句的语序也是调换过的",
			"§d§lMAYHEM! §r§7You received a §b❄ Cold Resistance §7buff from your §dMineshaft Mayhem §7perk!",
			Surface.CHAT,
			"§d§l矿井狂乱! §7你的§d「矿井狂乱」§7天赋给了你 §b❄ 抗寒§7增益!");

		// ---- English ordinals: the number is the value, the two letters after it are grammar ----
		// One record covers all four suffixes, which is why the calendar does not need st/nd/rd/th
		// spelled out four times over the way the sidebar's dates still are.
		check("届数只留数字", "§627th Spooky Festival", Surface.ITEM, "§6第 27 届惊魂节");
		check("st 后缀同一条记录管", "§d1st Hoppity's Hunt", Surface.ITEM, "§d第 1 届 Hoppity 的寻兔行动");
		check("rd 后缀也一样", "§c3rd Season of Jerry", Surface.ITEM, "§c第 3 届 Jerry 季");
		// The same event's name reached through the other road: a value a placeholder caught, which
		// only the term table answers for. Both roads, one name.
		check("活动名走词表那一路", "  §eSPOOKY FESTIVAL STARTED!", Surface.CHAT, "  §e「惊魂节」开始了!");

		// ---- the floating text over an NPC's head, which is a surface of its own ----
		// CLICK above a shopkeeper is an instruction; CLICK at the end of the version announcement is
		// part of a sentence. Same word, two records, and neither may answer for the other's surface.
		check("NPC 头顶的操作提示", "§e§lCLICK", Surface.HOLOGRAM, "§e§l右键点击");
		check("聊天里的 CLICK 还是它自己那条", "§eLatest update: §bSkyBlock v0.27 §e§lCLICK", Surface.CHAT,
			"§e最新更新: §b空岛生存 v0.27 §e§l点击查看");

		// ---- the same value, shouted in one place and written in title case in another ----
		check("词表匹配不分大小写", "  GONE WITH THE WIND STARTED!", Surface.CHAT, "  「随风而逝」开始了!");
		check("词表原样大小写也匹配", "Mining Event: Gone with the Wind", Surface.SCOREBOARD,
			"挖矿活动: 随风而逝");

		// ---- Hub final audit: strict dynamic values and current-capture shapes ----
		check("时光塔刚启用的时分秒", "§7Status: §a§lACTIVE §f1h00m00s", Surface.ITEM,
			"§7状态: §a§l生效中 §f1小时 00分 00秒");
		check("时光塔不足一小时的分秒", "§7Status: §a§lACTIVE §f59m58s", Surface.ITEM,
			"§7状态: §a§l生效中 §f59分 58秒");
		check("时光塔下次充能单位", "§7Next Charge: §a7h59m57s", Surface.ITEM,
			"§7下次充能: §a7小时 59分 57秒");
		check("时光塔说明的一小时", "§6Chocolate Factory §7by §6+1.1x §7for §a1h§7.", Surface.ITEM,
			"§6巧克力工厂§7产量提高 §61.1 倍§7，持续 §a1 小时§7。");
		check("每秒产量按中文语序", "§618,017.74 §8per second", Surface.ITEM,
			"§8每秒 §618,017.74");
		check("员工每秒产出不重复", "§7produce §6+220 Chocolate §7per second!", Surface.ITEM,
			"§7每秒产出 §6+220 巧克力§7!");
		check("金锭不受 Gold 词条影响", "§fGold Ingot §8x3", Surface.ITEM, "§f金锭 §8x3");
		check("已选宠物查询共享词表", "§7Selected pet: §6Glacite Golem", Surface.ITEM,
			"§7已选宠物: §6极冰石魔");
		check("命令占位符不查共享词表", "§8Also accessible via /mining", Surface.ITEM,
			"§8也可通过 /mining 打开");
		check("当前嘉年华及届数", "§7Active Event: §e19th Carnival", Surface.ITEM,
			"§7进行中的活动: §e第19届嘉年华");
		check("当前活动结束倒计时", "§7Ends in: §e1d 16h 22m 14s", Surface.ITEM,
			"§7距结束: §e1天 16小时 22分 14秒");
		check("下一活动固定为 Jerry 季", "§7Next Event: §c477th Season of Jerry", Surface.ITEM,
			"§7下一活动: §c第477届 Jerry 季");
		checkNoMatch("活动名加时间的过宽模板已删除", "19th Something Nobody Named 1d", Surface.ITEM);
		check("巧克力晚餐蛋聊天语序", "§d§lHOPPITY'S HUNT §dYou found a §aChocolate Dinner Egg §dnear the Wheat Minion!",
			Surface.CHAT, "§d§lHoppity 的寻兔行动: §d你在 Wheat Minion 附近找到了§a巧克力晚餐蛋!");

		// ---- the fifteen items reported from the 2026-08-27 session ----
		// Every one of these is a line a player saw in English or read wrong, so each is pinned to the
		// exact bytes the capture recorded rather than to a tidied-up version of them.
		check("抽奖开奖标题", "                                 Lucky Winners", Surface.CHAT,
			"                                 幸运得主");
		check("抽奖全服票数", "§f            §a§lNICE! §fPlayers pooled a total of §6648 §ftickets!",
			Surface.CHAT, "§f            §a§l不错! §f大家一共投了 §6648 §f张票券!");
		check("抽奖个人票数", "§f             §a§lCOOL! §fYou personally collected §a88 §ftickets!",
			Surface.CHAT, "§f             §a§l厉害! §f你个人收集了 §a88 §f张票券!");
		check("抽奖点券奖励", "§8+§b41 Bits", Surface.CHAT, "§8+§b41 点券");
		check("抽奖挖掘速度奖励", "§8+§6100 Mining Speed", Surface.CHAT, "§8+§6100 挖掘速度");
		check("活动结算的挖矿经验", "+20,000 Mining Experience", Surface.CHAT, "+20,000 挖矿经验");
		check("在别的房间领过奖(第一行)", "You already claimed rewards for this event on another",
			Surface.CHAT, "你已经在另一个房间领过");
		check("在别的房间领过奖(第二行)", "server!", Surface.CHAT, "这次活动的奖励了！");
		// Tab: the durations Hypixel abbreviates, which used to reach the screen as "35d".
		checkRow("Tab 离线天数带缩写", "§ainkkni §7(Offline 35d)", "§ainkkni §7(离线 35天)");
		checkRow("Tab 离线天数封顶写法", "§ainkkni §7(Offline 35d+)", "§ainkkni §7(离线 35天以上)");
		check("时长缩写按单位换算", "§7Duration: §a1h30m", Surface.ITEM, "§7持续时间: §a1小时30分");
		checkRow("Tab 宠物训练剩余时间", " 1: §7[Lvl 99] §5Ghoul §b22d", " 1: §7[99 级] §5食尸鬼 §b22天");
		check("Tab 小时复数", "§f9 Hours", Surface.TABLIST, "§f9 小时");
		check("Tab 不足一小时", "§fLess than an hour", Surface.TABLIST, "§f不足 1 小时");
		check("Tab 活动小时倒计时不加后", "Starts In: §e3h", Surface.TABLIST, "距开始: §e3 小时");
		check("Tab 活动分钟倒计时不加后", "Starts In: §e26m", Surface.TABLIST, "距开始: §e26 分钟");
		check("宾果活动倒计时不加后", "§7Event Starts: §a43h", Surface.ITEM, "§7距活动开始: §a43 小时");
		check("Tab 只剩一小时", "§f1 Hour", Surface.TABLIST, "§f1 小时");
		check("Tab 曲奇增益小节", "Cookie Buff", Surface.TABLIST, "曲奇增益");
		check("Tab 魔法寻宝剩余小时", "§eMagic Find V §f7 Hours", Surface.TABLIST, "§e魔法寻宝 V §f7 小时");
		// The unit letter must not be written into a template: the server picks the unit by how much
		// time is left, so a record saying "%sh" is English for every player who looks at it on a day
		// when the answer is in days.
		checkRow("选举倒计时按天", "§e§lElection: §b2d", "§e§l选举: §b2天");
		checkRow("选举倒计时按小时", "§e§lElection: §b40h", "§e§l选举: §b40小时");
		// Two Chinese units run together — 2小时24分, not 2小时 24分, which reads as two clocks.
		check("多段时长中间不留空格", "§7Interest in: §b02h 24m 56s", Surface.ITEM,
			"§7距结息: §b02小时24分56秒");
		// Same sentence, three server writings of the remaining time. Only the "hours" one had a
		// record, so the tab footer was English for the whole last hour of every potion.
		check("神药剩余小时", "§7You have a §cGod Potion §7active! §d2 hours", Surface.TABLIST,
			"§c神药§7生效中! 剩余 §d2 小时");
		check("神药剩余分钟", "§7You have a §cGod Potion §7active! §d32 minutes", Surface.TABLIST,
			"§c神药§7生效中! 剩余 §d32 分钟");
		check("神药最后几分钟的紧凑倒计时", "§7You have a §cGod Potion §7active! §d5m 59s",
			Surface.TABLIST, "§c神药§7生效中! 剩余 §d5分59秒");
		// [SECURITY] Sloth wears a tag the shape of a player's line, so the speaker used to be read as
		// dialogue and the sentence never looked up. The tag has to be peeled the same way [NPC] is.
		check("Sloth 的安全提示剥掉 [SECURITY] 前缀", "§c[SECURITY] Sloth§f: Downloading suspicious mods"
			+ " or visiting untrusted discord servers can put your account at risk. It is up to you to"
			+ " keep your account secure!", Surface.CHAT, "§c[SECURITY] Sloth§f: 下载来路不明的 Mod、进不"
			+ "可信的 Discord 群，都可能让你的账号出问题。账号安不安全，得靠你自己把关!");
		check("服务器重启的聊天预告", "§c[Important] §eThis server will restart soon: §bScheduled Reboot",
			Surface.CHAT, "§c[重要] §e本房间即将重启: §b例行重启");
		check("重启大标题", "§e§lSERVER REBOOT!", Surface.MISC, "§e§l房间即将重启!");
		check("重启副标题带倒计时", "§aScheduled Reboot §7(in §e1:00§7)", Surface.MISC,
			"§a例行重启 §7(剩余 §e1:00§7)");
		// One record plus the term table covers all 61 slots of the Crafted Minions menu. Writing a
		// record per minion would be the term table copied a second time, and Hypixel adds minions.
		check("小人名走词表", "§eObsidian Minion", Surface.ITEM, "§e黑曜石小人");
		check("小人名两个词的种类", "§eEnd Stone Minion", Surface.ITEM, "§e末地石小人");
		// Voidling is a coined word and stays English by decision; the seam rule puts the space in.
		check("词表里没有的小人种类保持英文", "§eVoidling Minion", Surface.ITEM, "§eVoidling 小人");
		check("带档位的小人名", "§eInferno Minion XI", Surface.ITEM, "§e炽焰小人 XI");
		// The tier placeholder is what stops "Gingerbread Man Minion Skin" being read as a tiered name.
		checkNoMatch("小人皮肤不算带档位的小人名", "§eGingerbread Man Minion Skin", Surface.ITEM);
		check("已制作的档位", "§a✔ Tier IV", Surface.ITEM, "§a✔ 第 IV 档");
		check("未制作的档位", "§c✖ Tier XII", Surface.ITEM, "§c✖ 第 XII 档");
		// "To Collections" used to be eaten by "%1$s Collections", rendering as "To 收藏品".
		check("返回收藏品不被类别模板吃掉", "§7To Collections", Surface.ITEM, "§7返回收藏品");
		check("返回设置同理", "§7To Settings", Surface.ITEM, "§7返回设置");
		check("银行结息倒计时整段换算", "§7Interest in: §b1h 57m", Surface.ITEM, "§7距结息: §b1小时57分");
		check("银行结息只剩分秒", "§7Until interest: §b4m 48s", Surface.ITEM, "§7距结息: §b4分48秒");
		check("银行结息整点小时", "§7Until interest: §b10 Hours", Surface.ITEM, "§7距结息: §b10 小时");
		// Enchantment names, and the line that lists several of them. Vanilla enchantments use the
		// official Chinese; SkyBlock's own are left English for now except Ice Cold.
		check("原版附魔名", "§9Efficiency X", Surface.ITEM, "§9效率 X");
		check("原版附魔名两个词", "§9Silk Touch I", Surface.ITEM, "§9精准采集 I");
		check("空岛专属附魔 Ice Cold", "§9Ice Cold V", Surface.ITEM, "§9抗寒 V");
		check("抗寒的说明", "§7Grants §b+5❄ Cold Resistance§7.", Surface.ITEM, "§7提供 §b+5❄ 抗寒§7。");
		checkEnchantments("附魔列表逐段翻译", "§9Depth Strider III, Feather Falling V, Growth V",
			"§9深海探索者 III, 摔落缓冲 V, Growth V");
		checkEnchantments("附魔列表混着没翻的也照样翻已翻的", "§9Power V, Punch II, Snipe III",
			"§9力量 V, 冲击 II, Snipe III");
		// Every piece untranslated: hand the line back untouched rather than a rebuilt copy of itself.
		checkEnchantments("整列都没翻就原样不动", "§9Critical V, Experience III, First Strike IV", null);
		// The structural gate. Prose has commas too, and half a sentence looked up on its own is how a
		// line gets scrambled rather than merely left English.
		checkEnchantments("带逗号的散文不按逗号切开", "§7Forge helpful accessories, armor", null);
		checkEnchantments("句子里的逗号不算列表", "§7Deals up to 30% of your damage, dealt back", null);
		// Blast Protection and Projectile Protection have a word-for-word identical first line and
		// differ only on the second. Folding the whole sentence into the first would make one of them
		// read "抵抗爆炸" on an item that protects against arrows — two records, one template, only one
		// of them ever drawn. So the first line stops at 抵抗 and the hazard is the second line's job.
		check("防御说明的共用首行只翻到抵抗", "§7Grants §a+210 ❈ Defense §7against", Surface.ITEM,
			"§7提供 §a+210 ❈ 防御力§7,用于抵抗");
		check("爆炸保护的尾行", "§7explosions.", Surface.ITEM, "§7爆炸。");
		check("弹射物保护的尾行", "§7projectiles.", Surface.ITEM, "§7弹射物。");
		check("单行的保护说明", "§7Grants §a+20 ❈ Defense§7.", Surface.ITEM, "§7提供 §a+20 ❈ 防御力§7。");
		// Number on the second line, so the sentence cannot be merged into the first without losing it.
		check("抢夺说明的前半", "§7Increases the chance of a Monster", Surface.ITEM, "§7怪物掉落物品的概率");
		check("抢夺说明的后半带百分比", "§7dropping an item by §a45%§7.", Surface.ITEM,
			"§7提高 §a45%§7。");
		// The category records used to write the space between value and noun by hand, which is right
		// only while the value stays English. Adding a term for one of them — Fishing, for the minion
		// menu — then produced 「钓鱼 宠物」, a space between two Chinese words. The seam rule decides it
		// per line instead, so both spellings come out right and neither has to be maintained.
		check("类别名有译名时不留空格", "§8Fishing Pet", Surface.ITEM, "§8钓鱼宠物");
		check("技能类别名都有译名", "§8Combat Pet", Surface.ITEM, "§8战斗宠物");
		// A value that stays English on purpose rather than for want of a term: gemstone names are
		// kept in English by policy (GLOSSARY.md), so this is the spelling of the seam rule that
		// cannot be invalidated by somebody filling in one more term.
		check("类别名仍是英文时补空格", "§9Amber Crystal Hunter", Surface.ITEM, "§9Amber 水晶猎人");
		// The seam spans colour runs, and the space it inserts lands at the start of the following run
		// rather than the end of the previous one. Same pixels either way — a space has no colour.
		check("跨颜色段的接缝也补空格", "§7View your §aFarming Collections§7!", Surface.ITEM,
			"§7查看你的§a农业收藏品§7!");
		// Item lore reported as untranslated in the same session.
		checkTooltipName("吉兆前缀的钛钻头", "§dAuspicious §6Titanium Drill DR-X655",
			"§d吉兆 §6钛钻头 DR-X655", false);
		checkTooltipName("吉兆钻头保留英文对照", "§dAuspicious §6Titanium Drill DR-X655",
			"§d吉兆 §6钛钻头 DR-X655§d（Auspicious Titanium Drill DR-X655）", true);
		check("钻头配件名", "§aBlue Cheese Goblin Omelette Part", Surface.ITEM,
			"§a蓝纹奶酪哥布林煎蛋卷配件");
		check("动态挖掘速度加成", "§7Grants §6+300%  Mining Speed §7for", Surface.ITEM,
			"§7提供 §6+300%  挖掘速度§7，持续");
		check("纯净属性行", "§7Pristine: §5+3", Surface.ITEM, "§7纯净: §5+3");
		check("抗热属性行", "§7Heat Resistance: §c+10", Surface.ITEM, "§7抗热: §c+10");
		check("按最大法力值计的消耗", "§8Mana Cost: §b50% of max", Surface.ITEM,
			"§8法力消耗: §b最大法力值的 50%");
		// The whole sentence, on the head line: Hypixel wraps "Apply Drill Parts to this Drill by
		// talking to a Drill Mechanic!" across two lore lines, and the tail is a continuation the
		// tooltip removes. Both halves once carried their own translation, so the tooltip said the
		// same thing twice — see the continuation pair check below.
		check("配件安装提示整句", "§7Apply Drill Parts to this Drill by", Surface.ITEM,
			"§7找「钻头技师」为这个钻头安装配件!");
		check("凿子挖掘次数", "§7Charges: §e3", Surface.ITEM, "§7挖掘次数: §e3");
		check("宝藏加成是动态值", "§7Bonus Treasure: §a+300%", Surface.ITEM, "§7宝藏加成: §a+300%");
		check("鬼火加成不重复说额外获得", "§6• §7Grants §6+100 Mining Speed§7.", Surface.ITEM,
			"§6- +100 挖掘速度§7。");
		check("提灯加成同一写法", "§5• §7Grants §6+100 Mining Speed§7.", Surface.ITEM,
			"§5- §6+100 挖掘速度§7。");
		check("右键使用不是食用", "§eRight-click to consume!", Surface.ITEM, "§e右键点击使用!");
		check("活动预告不说被动活动", "§eThis is a passive event! §bIt's happening everywhere in the §bDwarven Mines!",
			Surface.CHAT, "§b这个活动在整个矮人矿山都会生效!");
		check("秘银老饕折行版本的数量与量词同色",
			"§bVeins§f. Bring §c200§a Tasty Mithril§f to him at the §bDwarven Village§f!", Surface.CHAT,
			"§b矿脉§f的美味秘银,带 §c200 份§f到§b矮人村庄§f交给他!");
		check("秘银老饕完整地名版本的数量与量词同色",
			"§fBring §c200 §aTasty Mithril §fto him at the §bDwarven Village§f!", Surface.CHAT,
			"§f带 §c200 份§a美味秘银§f到§b矮人村庄§f交给他!");
		check("连杀硬币提示的语序与颜色",
			"§a§l+10 Kill Combo §8+§610 §7coins per kill", Surface.CHAT,
			"§a§l+10 连杀§7 每次击杀§8 +§610 §7硬币");
		checkLayout(files, "Mining/ChatMessage/Mining_Events.json", "mining_raffle_intro_1", "center_chat_banner");
		checkLayout(files, "Mining/ChatMessage/Mining_Events.json", "mining_raffle_intro_2", "center_chat_banner");
		checkLayout(files, "Mining/ChatMessage/Mining_Events.json", "mining_raffle_intro_3", "center_chat_banner");
		check("BossBar 带时长的活动", "PASSIVE EVENT 2X POWDER RUNNING FOR 5m", Surface.BOSS_BAR,
			"活动「双倍粉末」进行中,剩余 5m");
		check("集市创建购买订单", "§aCreate Buy Order", Surface.ITEM, "§a创建购买订单");
		check("集市领取出售报价的成交总额", " §7worth §614.5k coins §7by §b[MVP§9+§b] inkkni",
			Surface.ITEM, "§7 共 §614.5k 硬币,§7由 §b[MVP§9+§b] inkkni 操作");
		check("集市确认购买订单标题", "Confirm Buy Order", Surface.GUI_TITLE, "确认购买订单");
		// The ampersand is a word inside a name, and a category whose own name has one used to fall
		// through the template and leave the whole title in English. Both halves are checked: the name
		// is accepted between two words, and still refused at either end, where it is not a name.
		check("分类名里的 & 不挡匹配", "Bazaar ➜ Woods & Fishes", Surface.GUI_TITLE, "集市 ➜ 木材与鱼类");
		checkNoMatch("& 结尾的残句不算分类名", "Bazaar ➜ Woods &", Surface.GUI_TITLE);
		check("设置页标题按类别名走词表", "Personal Settings", Surface.GUI_TITLE, "个人设置");
		check("查不到的类别名保持英文并补接缝空格", "API Settings", Surface.GUI_TITLE, "API 设置");
		// Same meal, two English names, two Chinese ones — see gui_title_chocolate_dinner_egg.
		check("Dinner 蛋和 Supper 蛋不同名", "Chocolate Supper Egg", Surface.GUI_TITLE, "巧克力晚膳蛋");
		// A setting row is a state mark and a name. The mark must not be swallowed into a capture:
		// "%1$s Collections" out of the collections menu used to match this and render "✔ API:收藏品",
		// because the whole line is offered before the mark is stepped over and ✔ passed for the start
		// of a name. Both marks are checked, since each is a different colour on a different row state.
		// The garden broadcasts carry a rank alongside the name, which player_name refuses, so the
		// placeholder is raw. Both forms are checked: with a rank and without one.
		check("花园广播带称号", "§b[MVP§9+§b] inkkni §cenabled Garden Plot Holograms!", Surface.CHAT,
			"§b[MVP§9+§b] inkkni §c开启了花园的地块悬浮字!");
		check("花园广播不带称号", "§7Steve123 §cdisabled Garden Plot Holograms!", Surface.CHAT,
			"§7Steve123 §c关闭了花园的地块悬浮字!");
		check("花园来访广播的取值走词表", "§b[MVP§9+§b] inkkni §aenabled Garden Visits: §2Guild§a!",
			Surface.CHAT, "§b[MVP§9+§b] inkkni §a开启了花园的「来访: §2公会§a」!");
		// A setting's confirmation line: four records carry the state, the term table carries the name.
		// Both numbers are checked because the server picks the verb from the name's own plurality and
		// Chinese does not mark it, so the pair has to come out identical.
		check("开关确认行(单数)", "§cAdvanced Supercraft is now enabled!", Surface.CHAT, "§c高级超级合成已开启!");
		check("开关确认行(复数同译)", "§cRare Drop Sounds are now enabled!", Surface.CHAT, "§c稀有掉落音效已开启!");
		check("开关确认行(关掉)", "§cFishing Timer is now disabled!", Surface.CHAT, "§c钓鱼计时已关闭!");
		// This name is not a name-shaped value — "breaking" and "ungrown" are lowercase words in the
		// middle of it — which is why the placeholder is typed raw rather than category_name.
		check("开关名中间夹小写词也能捕到", "§cConfirm breaking ungrown Mutations are now enabled!",
			Surface.CHAT, "§c打断未成熟变异作物前先确认已开启!");
		// The colours differ between the two menus and each keeps its own: the main menu draws the name
		// grey behind the mark, the sub-menu draws it green-and-yellow, and the record's segments do not
		// repaint a row the server sent differently.
		check("勾号不算类别名的一部分", "§a✔ §7API: Collections", Surface.ITEM, "§a✔ §7API: 收藏品");
		check("叉号同理", "§c✖ §7API: Collections", Surface.ITEM, "§c✖ §7API: 收藏品");
		check("子菜单里同一行没有记号", "§aAPI: §eCollections", Surface.ITEM, "§aAPI: §e收藏品");
		check("化石物品名", "§6Tusk Fossil", Surface.ITEM, "§6象牙化石");
		check("化石物品名(另一种)", "§6Clubbed Fossil", Surface.ITEM, "§6棒状化石");
		checkTooltipName("化石名带英文对照", "§6Tusk Fossil", "§6象牙化石（Tusk Fossil）", true);
		// The date reads as Chinese does: the year first, then the solar term and the day.
		check("空岛菜单日期语序", "§7Date: §a10th Autumn 510", Surface.ITEM,
			"§7日期: 第 §a510 年 · 秋分 10 日");

		// ---- the 2026-08-31 session: an item's stat line once it has a reforge on it ----
		// The bonus brackets are the normal case, not the exception — an item with nothing on it is a
		// freshly crafted one. These placeholders were typed `number`, which stops at the first value,
		// so every real item's stat lines missed their record. The brackets keep the colours the
		// server sent them in because a placeholder's value is copied across verbatim.
		check("属性行带一个加成括号", "§7Damage: §c+285 §9(+165)", Surface.ITEM, "§7伤害: §c+285 §9(+165)");
		check("属性行带四个加成括号", "§7Strength: §c+261 §e(+30) §9(+199) §d(+32) §8(+1,119.69)",
			Surface.ITEM, "§7力量: §c+261 §e(+30) §9(+199) §d(+32) §8(+1,119.69)");
		check("属性行没有加成括号时照旧", "§7Intelligence: §b+110", Surface.ITEM, "§7智力: §b+110");

		// ---- the 2026-08-31 session: one template per upgrade *line*, not per upgrade *name* ----
		// Sixteen account/profile upgrades share four line shapes. Spelling a name into each shape is
		// what left the menu Chinese for whichever upgrade the capturing player happened to be running.
		check("升级状态行的升级名走词表", "§7Profile: §aGuests Limit I §7(§e47 Hours§7)", Surface.ITEM,
			"§7存档: §a访客上限 I §7(§e47 小时§7)");
		check("同一行换一个升级名照样翻", "§7Account: §dHeart of the Mountain I §7(§e5 Days§7)",
			Surface.ITEM, "§7账号: §d山峦之心 I §7(§e5 天§7)");
		check("账号升级不可与合作成员叠加", "§7Does NOT impact co-op partners.", Surface.ITEM,
			"§7不可与合作成员叠加。");
		check("升级历史的时间走 time 占位符", "§82m ago §binkkni §eclaimed §aMinion Slots V", Surface.ITEM,
			"§82分前 §binkkni §e领取了§a小人槽位 V");
		check("刚发生的升级历史写 now", "§8now §binkkni §estarted §aGuests Limit I", Surface.ITEM,
			"§8刚刚 §binkkni §e开始升级§a访客上限 I");
		check("Tab 页脚的升级行", "§eGuests Limit I §f47 Hours", Surface.TABLIST, "§e访客上限 I §f47 小时");
		check("开始升级的聊天广播", "§eYou started the §aGuests Limit I §eupgrade!", Surface.CHAT,
			"§e你开始了§a访客上限 I§e 升级!");
		// Written singular, the template only ever matched the tier that happened to give one slot.
		check("每级槽位数是复数写法", "§7Each tier: §a+2 slots", Surface.ITEM, "§7每级: §a+2 个槽位");
		check("同一条也管单数", "§7Each tier: §a+1 slot", Surface.ITEM, "§7每级: §a+1 个槽位");

		// ---- the 2026-08-31 session: the month in the Bingo menus ----
		// Two paths, one译名: the title's month is a placeholder value out of Terms.json, the item
		// subtitle is a whole line out of _shared/Months.json. Both have to say 2026 年 9 月.
		check("宾果菜单标题的月份", "Bingo - September 2026", Surface.GUI_TITLE, "宾果 - 2026 年 9 月");
		check("宾果格子副标题的月份", "§8September 2026", Surface.ITEM, "§82026 年 9 月");

		// ---- the English kept beside the Chinese, which is what the Bazaar is searched by ----
		report("物品名带上英文对照(颜色码不进括号)",
			"秘银镐（Mithril Pickaxe）".equals(
				OriginalLabel.append(Component.literal("秘银镐"), Component.literal("§9Mithril Pickaxe")).getString()),
			"实际 [" + OriginalLabel.append(Component.literal("秘银镐"), Component.literal("§9Mithril Pickaxe")).getString() + "]");
		report("没翻译的名字不加对照",
			"Bazaar".equals(OriginalLabel.append(Component.literal("Bazaar"), Component.literal("Bazaar")).getString()),
			"实际 [" + OriginalLabel.append(Component.literal("Bazaar"), Component.literal("Bazaar")).getString() + "]");

		System.out.println();
		System.out.println("通过 " + passed + " / 失败 " + failed);
		System.exit(failed == 0 ? 0 : 1);
	}

	/**
	 * Every continuation line has a translated line above it to belong to.
	 *
	 * <p>{@code continuation: true} means "this line is the tail of the sentence above, delete it and
	 * re-wrap the Chinese". The line above therefore has to be the head of that sentence and has to be
	 * translated. When the middle line of a three-line sentence is left unmarked, the head's Chinese is
	 * drawn, the middle stays in English, and the tail vanishes — half a Chinese sentence with half an
	 * English one wedged into it, which is the worst thing this project can put on screen and which
	 * nothing else here notices: every record involved is well-formed on its own.
	 *
	 * <p>Found exactly that in {@code Suspicious_Scrap.json} and a four-line variant of it in
	 * {@code Beacon_I.json}, where the sentence had been translated as four unrelated fragments spread
	 * across four lore lines.
	 */
	/**
	 * Every {@code continuation} record is on the one surface that can act on it.
	 *
	 * <p>The marker means "this line is the tail of the line above, delete it once the head has been
	 * translated", and only {@link io.github.bingkkni.skyzh.text.TooltipTranslator} can do that: a
	 * lore array arrives as a list and the mod can drop a member of it. Chat messages and sidebar rows
	 * arrive one at a time, so there is nothing to delete the tail from and nothing that knows the
	 * head was translated — the head comes out Chinese and the tail stays English, on screen, forever.
	 *
	 * <p>Found exactly that on the raffle hint in {@code Mining_Sidebar.json}, three sidebar rows that
	 * had been marked up as if they were lore. The fix there was to break the Chinese across the same
	 * three rows, which is what any surface other than lore has to do.
	 */
	private static void checkContinuationOnlyInLore(Map<String, JsonObject> files) {
		List<String> misplaced = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			Surface surface = surfaceOf(relative);

			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (source.has("continuation") && source.get("continuation").getAsBoolean()
					&& surface != Surface.ITEM) {
					misplaced.add(relative + "#" + text(record, "id") + "(" + surface + ")");
				}
			}
		}

		report("continuation 只用在物品 Lore 上", misplaced.isEmpty(),
			"这些面上尾行不会被删掉，会留下半句英文: " + String.join(", ", misplaced));
	}

	private static void checkNoBrokenContinuation(Map<String, JsonObject> files) {
		List<String> broken = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (List<JsonObject> group : groupsOf(files.get(relative))) {
				for (int i = 0; i < group.size(); i++) {
					JsonObject record = resolveRef(group.get(i), files);

					if (!record.has("continuation") || !record.get("continuation").getAsBoolean()) {
						continue;
					}

					if (i == 0) {
						broken.add(relative + '#' + id(group.get(i)) + " —— 文件里第一条就是 continuation，没有可归属的首行");
						continue;
					}

					JsonObject above = resolveRef(group.get(i - 1), files);
					boolean aboveIsHead = above.has("continuation") && above.get("continuation").getAsBoolean();

					if (!aboveIsHead && !hasTranslation(above)) {
						broken.add(relative + '#' + id(group.get(i)) + " —— 上一行 " + id(group.get(i - 1))
							+ " 既没译文也没标 continuation，中间这行会留成英文");
					}
				}
			}
		}

		report("没有断头的跨行整句（continuation 上面一定有译好的首行）", broken.isEmpty(),
			String.join("\n      ", broken));
	}

	/**
	 * The other way a wrapped sentence goes wrong: the head was rewritten into a whole sentence and
	 * the tail kept the translation it had when it was half of one, so the tooltip says the same thing
	 * twice.
	 *
	 * <p>{@link #checkNoBrokenContinuation} catches a tail whose head is still English. This catches
	 * the head that no longer needs a tail at all. On screen it reads
	 *
	 * <pre>
	 *   找「钻头技师」为这个钻头安装配件!
	 *   「钻头技师」聊聊吧!
	 * </pre>
	 *
	 * <p>and has now happened twice by hand — the drill parts hint and the carnival ticket — because
	 * the two records are edited at different times and nothing about the head says a tail exists.
	 * The evidence is the English: a head that does not end on sentence punctuation followed by a tail
	 * that begins on a lowercase word is one sentence Hypixel wrapped, so if the head's Chinese has
	 * already closed the sentence, the tail belongs to it and must be a {@code continuation}.
	 */
	private static void checkNoDuplicatedSentence(Map<String, JsonObject> files) {
		List<String> duplicated = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (List<JsonObject> group : groupsOf(files.get(relative))) {
				for (int i = 0; i + 1 < group.size(); i++) {
					JsonObject head = resolveRef(group.get(i), files);
					JsonObject tail = resolveRef(group.get(i + 1), files);

					if (falsey(tail, "translate")
						|| (tail.has("continuation") && tail.get("continuation").getAsBoolean())) {
						continue;
					}

					String headEnglish = text(head, "text").strip();
					String tailEnglish = text(tail, "text").strip();

					if (headEnglish.isEmpty() || tailEnglish.isEmpty()) {
						continue;
					}

					// An English head that closed its sentence has no tail; a tail begins mid-sentence,
					// which in English means a lowercase word.
					if ("。！？!?.:：,，、;；".indexOf(headEnglish.charAt(headEnglish.length() - 1)) >= 0
						|| !Character.isLowerCase(tailEnglish.charAt(0))) {
						continue;
					}

					String headChinese = drawnChinese(head).strip();
					String tailChinese = drawnChinese(tail).strip();

					if (headChinese.isEmpty() || tailChinese.isEmpty()) {
						continue;
					}

					// The head claims to be a finished sentence, and the tail still draws something.
					if ("。！？!?.".indexOf(headChinese.charAt(headChinese.length() - 1)) >= 0) {
						duplicated.add(relative + '#' + id(group.get(i)) + " [" + headChinese + "] 之后，"
							+ id(group.get(i + 1)) + " 又画了 [" + tailChinese + "]，同一句说了两遍"
							+ "（尾行应标 continuation 并留空 zh）");
					}
				}
			}
		}

		report("跨行整句不重复画第二遍", duplicated.isEmpty(), String.join("\n      ", duplicated));
	}

	/** What a record actually draws in Chinese: its own {@code zh}, or its segments joined. */
	private static String drawnChinese(JsonObject record) {
		if (record.has("segments") && record.get("segments").isJsonArray()) {
			StringBuilder joined = new StringBuilder();

			for (JsonElement element : record.getAsJsonArray("segments")) {
				JsonObject segment = element.getAsJsonObject();

				// "omit": true means this run has no Chinese of its own because its words moved into
				// a neighbouring one, so it draws nothing.
				if (!(segment.has("omit") && segment.get("omit").getAsBoolean())) {
					joined.append(text(segment, "zh"));
				}
			}

			return joined.toString();
		}

		return text(record, "zh");
	}

	private static String id(JsonObject record) {
		return record.has("id") ? record.get("id").getAsString() : "(无 id)";
	}

	/** Whether this record puts any Chinese on screen of its own. */
	private static boolean hasTranslation(JsonObject record) {
		if (!text(record, "zh").isEmpty()) {
			return true;
		}

		if (record.has("segments") && record.get("segments").isJsonArray()) {
			for (JsonElement element : record.getAsJsonArray("segments")) {
				if (element.isJsonObject() && !text(element.getAsJsonObject(), "zh").isEmpty()) {
					return true;
				}
			}
		}

		// A blank spacer line and a deliberately-untranslated proper noun both sit between the lines
		// of a lore block quite legitimately, and neither is a broken sentence.
		return text(record, "text").isBlank() || falsey(record, "translate");
	}

	/**
	 * Feeds every finished record its own text back and insists the index returns that record.
	 *
	 * <p>The failure this catches does not look like a failure in the data file: the record is there,
	 * its {@code zh} is filled in, and nothing logs a complaint — but on screen the line stays English
	 * or comes out as somebody else's translation, because a second record on the same surface also
	 * matches it and won. One record with a template of {@code "%s: %s"} is enough to swallow every
	 * "Label: value" line in the sidebar. Nothing else in this harness would notice.
	 */
	private static void checkNoRecordIsShadowed(TranslationIndex index, Map<String, JsonObject> files) {
		Map<Surface, Map<String, Set<String>>> expected = new HashMap<>();
		List<String[]> samples = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			Surface surface = surfaceOf(relative);

			if (surface == null) {
				continue;
			}

			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("id") || falsey(source, "translate") || !hasChinese(source)) {
					continue;
				}

				String id = record.get("id").getAsString();
				String template = templateOf(source);

				expected.computeIfAbsent(surface, key -> new HashMap<>())
					.computeIfAbsent(template, key -> new HashSet<>())
					.add(id);
				samples.add(new String[] { surface.name(), template, id, relative,
					fillPlaceholders(template, source) });
			}
		}

		List<String> shadowed = new ArrayList<>();

		for (String[] sample : samples) {
			Surface surface = Surface.valueOf(sample[0]);
			TranslationEntry found = index.lookup(surface, sample[4]);
			Set<String> acceptable = expected.get(surface).get(sample[1]);

			if (found == null) {
				shadowed.add(sample[3] + "#" + sample[2] + " 没有任何记录应答");
			} else if (!acceptable.contains(found.id()) && !spellsOutTheSample(found, sample[4])) {
				shadowed.add(sample[3] + "#" + sample[2] + " 被 " + found.sourceFile() + "#" + found.id() + " 顶掉");
			}
		}

		report("语料自查:每条已翻译记录都能应答自己(" + samples.size() + " 条)", shadowed.isEmpty(),
			String.join("; ", shadowed.subList(0, Math.min(shadowed.size(), 8))));
	}

	/**
	 * Whether the record that won spells the sample out letter for letter, with no placeholder of its
	 * own — the specific record beating the general one, which is the corpus doing what it meant to.
	 *
	 * <p>This became worth saying once {@link #sampleValue} started feeding a record's real
	 * {@code example} instead of {@code 1}. {@code "Fuel Tank: %s"} has {@code example: "Not Installed"}
	 * and is filled in as {@code "Fuel Tank: Not Installed"} — which
	 * {@code Divan's_Drill.json#divan_s_drill_fuel_tank_not_installed} writes out as literal text,
	 * deliberately: an uninstalled part is translated, an installed part's name is a proper noun, and
	 * the gloss on the shared record says so ("两种情况分开处理"). The exact record has to win there, and
	 * the general one is not shadowed by it — every other value still reaches the general record.
	 *
	 * <p>The template is checked for a placeholder rather than just compared for equality, so this
	 * cannot excuse a genuine rival: two templates that both hold a {@code %s} and fit each other's
	 * text are the case {@link #checkNoRivalRecords} exists for and are still reported here.
	 */
	private static boolean spellsOutTheSample(TranslationEntry found, String sample) {
		return found.template().indexOf('%') < 0 && found.template().equals(sample);
	}

	/**
	 * Every placeholder the engine cannot bound by its {@code type} has an {@code example} to be
	 * bounded by instead.
	 *
	 * <p>A {@code type} of {@code raw} — or no {@code placeholders} entry at all — compiles to
	 * {@link Capture#PHRASE}, which accepts any value that is not a whole sentence. That is loose
	 * enough for a record to answer for a line it knows nothing about: {@code "Gemstones: %s"}, written
	 * about the sockets on a drill, matched the Glacite Tunnels pity row {@code "Gemstone: 8-10"} and
	 * drew it as 宝石槽, while the fully-Chinese result told the capture there was nothing to report.
	 * {@link io.github.bingkkni.skyzh.text.ValueShape} closes that by holding the value to the kind of
	 * thing the record's own {@code example} is.
	 *
	 * <p>Which only works while the examples are there. They are, today — every one of the corpus's
	 * {@code raw} placeholders carries one — and a record added tomorrow without one would compile to
	 * the old unbounded {@code PHRASE} and reopen the hole for exactly one line, silently, in the one
	 * failure mode the capture cannot see. So the precondition is a build failure rather than a habit.
	 */
	private static void checkPhrasePlaceholdersHaveExamples(Map<String, JsonObject> files) {
		List<String> unbounded = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			if (surfaceOf(relative) == null) {
				continue;
			}

			for (JsonObject record : recordsOf(files.get(relative))) {
				if (record.has("ref") || !record.has("placeholders")
					|| !record.get("placeholders").isJsonArray()) {
					continue;
				}

				// Only records that become an entry. A `translate: false` proper noun and a template
				// that is nothing but its placeholder are both discarded by TranslationEntry#compile,
				// so neither can match anything and neither has a value to be bounded — demanding an
				// example there would be asking a translator to document a line the engine never reads.
				if (falsey(record, "translate") || !hasChinese(record)) {
					continue;
				}

				JsonArray declared = record.getAsJsonArray("placeholders");

				for (int i = 0; i < declared.size(); i++) {
					if (!declared.get(i).isJsonObject()) {
						continue;
					}

					JsonObject placeholder = declared.get(i).getAsJsonObject();

					if (Capture.of(text(placeholder, "type")) != Capture.PHRASE) {
						continue;
					}

					if (text(placeholder, "example").isEmpty()) {
						unbounded.add(relative + "#" + id(record) + " 的 "
							+ (text(placeholder, "token").isEmpty() ? "%s" : text(placeholder, "token"))
							+ " 是 raw 类型却没写 example,占位符将不受种类约束");
					}
				}
			}
		}

		report("raw 占位符都有 example 可供约束(" + unbounded.size() + " 处缺失)", unbounded.isEmpty(),
			String.join("\n      ", unbounded));
	}

	/**
	 * No two records on one surface are the same sentence split differently.
	 *
	 * <p>Two records with identical templates both fit the same line, and exactly one of them can win.
	 * Which one is decided by specificity, and when the templates are identical so is the specificity,
	 * so the tie falls to whichever file sorts first — meaning the answer changes if somebody renames a
	 * file. The loser is dead weight nothing will ever draw.
	 *
	 * <p>That is a nuisance when the two agree and a bug when they do not, and they usually do not
	 * agree about <em>colour</em>. {@code "- %s Glacite Powder"} was written twice, once in the
	 * commission board's file as one flat run and once in the Heart of the Mountain's with a
	 * {@code segments} array; the flat one sorted first, so the reimbursement list drew "- 5,000 极冰粉末"
	 * entirely in the dark grey of the bullet while the mithril line beside it came out right. Nothing
	 * else here notices: both records are well-formed, both are translated, and
	 * {@link #checkNoRecordIsShadowed} deliberately accepts either of them answering for the other's
	 * text.
	 *
	 * <p>The fix in the corpus is §5.6's: one copy in {@code _shared/} and a {@code ref} from each
	 * place that needs it. Records that reach the same shape by different routes are fine — what is
	 * compared is the finished split, not where it came from.
	 */
	private static void checkNoRivalRecords(Map<String, JsonObject> files) {
		Map<Surface, Map<String, Map<String, List<String>>>> byTemplate = new LinkedHashMap<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			Surface surface = surfaceOf(relative);

			if (surface == null) {
				continue;
			}

			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("id") || falsey(source, "translate") || !hasChinese(source)) {
					continue;
				}

				byTemplate
					.computeIfAbsent(surface, key -> new LinkedHashMap<>())
					.computeIfAbsent(templateOf(source), key -> new LinkedHashMap<>())
					.computeIfAbsent(shapeOf(source), key -> new ArrayList<>())
					.add(relative + "#" + id(record));
			}
		}

		List<String> rivals = new ArrayList<>();

		byTemplate.forEach((surface, templates) -> templates.forEach((template, shapes) -> {
			if (shapes.size() > 1) {
				List<String> names = new ArrayList<>();
				shapes.values().forEach(ids -> names.add(ids.getFirst()));
				rivals.add(surface + " 上的「" + template + "」有 " + shapes.size()
					+ " 种写法互相顶掉: " + String.join(" / ", names));
			}
		}));

		report("同一渲染面上没有两条模板相同、分段却不同的记录", rivals.isEmpty(),
			String.join("\n      ", rivals));
	}

	/** IDs only need to be unique within their source file, where refs address them. */
	private static void checkNoDuplicateIds(Map<String, JsonObject> files) {
		List<String> duplicates = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			Set<String> seen = new HashSet<>();

			for (JsonObject record : recordsOf(files.get(relative))) {
				String id = id(record);

				if (!"(无 id)".equals(id) && !seen.add(id)) {
					duplicates.add(relative + '#' + id);
				}
			}
		}

		report("同一语料文件内没有重复 id", duplicates.isEmpty(), String.join(", ", duplicates));
	}

	/**
	 * How a record splits its sentence up: the English of each segment, its Chinese, and where it is
	 * drawn. Two records with this much in common draw the same pixels whichever of them wins.
	 */
	private static String shapeOf(JsonObject source) {
		// A continuation with nothing of its own to say draws nothing, whatever its segments look
		// like: the sentence was folded into the line above and this line is deleted. Two of those
		// are the same record as far as the screen is concerned, so they are not rivals.
		if (!source.has("segments") && !hasChinese(withoutContinuation(source))) {
			return "continuation";
		}

		if (!source.has("segments") || !source.get("segments").isJsonArray()) {
			return "flat\u0000" + text(source, "zh");
		}

		if (!hasChinese(withoutContinuation(source))) {
			return "continuation";
		}

		StringBuilder shape = new StringBuilder();

		for (JsonElement element : source.getAsJsonArray("segments")) {
			JsonObject segment = element.getAsJsonObject();
			shape.append(text(segment, "text")).append('\u0000')
				.append(truthy(segment, "omit") ? "(omit)" : text(segment, "zh")).append('\u0000')
				.append(segment.has("order") ? segment.get("order").getAsInt() : -1).append('\u0001');
		}

		return shape.toString();
	}

	/**
	 * An event's name says the same thing in the term table as it does in the menu.
	 *
	 * <p>An event's name reaches the screen down two roads that share no code. The Events menu draws
	 * it as an <em>item name</em>, which only a record can answer for; the sidebar and the boss bar
	 * draw it as a <em>value</em> a placeholder caught, which only {@code _shared/Terms.json} can
	 * answer for. So the name is written twice, and two copies of a translation drift — which is
	 * exactly how "Cost" ended up as 价格 in one menu and 花费 in another until somebody noticed on
	 * screen.
	 *
	 * <p>Only the record that <em>is</em> that event's name is compared — the name on its own, or the
	 * name behind the ordinal the menu puts in front of it. Matching the term anywhere inside the
	 * record's English would pair {@code Winter Island} with the table's {@code Island}, and complain
	 * that 寒冬岛 does not contain 岛屿. On the Chinese side containment is right, because the record
	 * usually says more than the term does: {@code "%s Spooky Festival"} is 第 %s 届惊魂节 while the
	 * term is 惊魂节. What must not happen is the record saying 万圣节 while the table says 惊魂节.
	 */
	private static void checkEventNamesAgree(Map<String, JsonObject> files) {
		JsonObject terms = files.get("_shared/Terms.json");
		JsonObject events = files.get("_shared/Event_Names.json");

		if (terms == null || events == null) {
			report("活动名在词表和菜单里说的是同一件事", false, "找不到 _shared/Terms.json 或 _shared/Event_Names.json");
			return;
		}

		Map<String, String> byEnglish = new HashMap<>();

		for (JsonElement element : terms.getAsJsonArray("terms")) {
			JsonObject term = element.getAsJsonObject();
			byEnglish.put(text(term, "en"), text(term, "zh"));
		}

		List<String> disagreeing = new ArrayList<>();

		for (JsonObject record : recordsOf(events)) {
			String english = text(record, "text");
			String chinese = text(record, "zh");

			if (chinese.isEmpty()) {
				// Deliberately untranslated — Starlyn Contest and anything else nobody has pinned down.
				continue;
			}

			// The name with the menu's ordinal prefix taken off, which is the name the table files it under.
			String name = english.startsWith("%s ") ? english.substring(3) : english;
			String term = byEnglish.get(name);

			if (term != null && !term.isEmpty() && !chinese.contains(term)) {
				disagreeing.add(id(record) + " 写的是「" + chinese + "」，词表里 "
					+ name + " 却是「" + term + "」");
			}
		}

		report("活动名在词表和菜单里说的是同一件事", disagreeing.isEmpty(),
			String.join("\n      ", disagreeing));
	}

	/** The same record with its {@code continuation} flag off, so {@link #hasChinese} answers about its text. */
	private static JsonObject withoutContinuation(JsonObject source) {
		JsonObject copy = source.deepCopy();
		copy.remove("continuation");

		return copy;
	}

	/**
	 * A {@code segments} array spells out the same English as the record's own {@code text}.
	 *
	 * <p>The two are written by hand, one under the other, and only the segments are matched against
	 * anything — {@code text} is what the file is indexed and searched by, and what a person reads to
	 * see what the record is for. Letting them drift means searching the corpus for a sentence and
	 * finding a record that no longer covers it, or reading a record and being told the wrong thing
	 * about which line it answers for. A dropped word between two segments does not fail anything else
	 * here: the record still compiles and still matches, just not the line anybody meant.
	 */
	private static void checkSegmentsSpellTheText(Map<String, JsonObject> files) {
		List<String> mismatched = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("segments") || !source.get("segments").isJsonArray()) {
					continue;
				}

				StringBuilder joined = new StringBuilder();

				for (JsonElement element : source.getAsJsonArray("segments")) {
					joined.append(text(element.getAsJsonObject(), "text"));
				}

				if (!joined.toString().equals(text(source, "text"))) {
					mismatched.add(relative + "#" + id(record) + "\n        segments 拼起来 [" + joined
						+ "]\n        text 写的是   [" + text(source, "text") + "]");
				}
			}
		}

		report("segments 拼起来就是 text 本身", mismatched.isEmpty(), String.join("\n      ", mismatched));
	}

	/**
	 * No record has some of its {@code segments} translated and one left blank.
	 *
	 * <p>An empty {@code zh} means "nobody has translated this yet, leave it in English", which is the
	 * right answer for a whole record and the wrong one for a single colour run inside a translated
	 * one: the line reaches the screen as Chinese with an English fragment stuck to it. The drill
	 * part's lore read 「为已解锁的所有山峦之心天赋各提升 +1 级。Heart of」 for exactly this reason —
	 * the last run held the perk tree's name, its meaning had been folded into an earlier run, and
	 * nothing recorded that.
	 *
	 * <p>{@code omit: true} is how a run says it has no Chinese of its own, so it is accepted here.
	 * Runs holding no letters are accepted too: a number, an icon or a bracket carries itself across
	 * unchanged, and making a translator write {@code "zh": "%s"} for every one of those would bury
	 * the cases that matter.
	 *
	 * <p>This is the check the failure above would have needed. Nothing else catches it — the record
	 * compiles, matches its own line, spells out its own {@code text}, and every other segment has a
	 * translation.
	 */
	private static void checkNoHalfTranslatedSegments(Map<String, JsonObject> files) {
		List<String> half = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("segments") || !source.get("segments").isJsonArray()
					|| source.has("translate") && !source.get("translate").getAsBoolean()) {
					continue;
				}

				JsonArray segments = source.getAsJsonArray("segments");
				boolean anyTranslated = false;

				for (JsonElement element : segments) {
					if (!text(element.getAsJsonObject(), "zh").isBlank()) {
						anyTranslated = true;
						break;
					}
				}

				if (!anyTranslated) {
					// Wholly untranslated: the line stays English, which is a gap and not a bug.
					continue;
				}

				for (int i = 0; i < segments.size(); i++) {
					JsonObject segment = segments.get(i).getAsJsonObject();
					String english = text(segment, "text");

					if (!text(segment, "zh").isBlank()
						|| segment.has("omit") && segment.get("omit").getAsBoolean()
						|| english.chars().noneMatch(Character::isLetter)) {
						continue;
					}

					half.add(relative + "#" + id(record) + " 的第 " + i + " 段 [" + english
						+ "] 没有译文，别的段却翻了；这一段在屏幕上会是英文。"
						+ "意思挪进别的段里了就标 omit: true，否则补上 zh");
				}
			}
		}

		report("没有半中半英的 segments", half.isEmpty(), String.join("\n      ", half));
	}

	/**
	 * Every {@code segments[].order} is a permutation of the array's own positions.
	 *
	 * <p>{@code order} says where a colour run lands in the finished Chinese, which is how a sentence
	 * whose word order differs from the English keeps each run's colour and placeholder attached to
	 * its own words. Two runs claiming the same position means one of them is never drawn — half a
	 * sentence missing from the screen, and the record reads correctly in the file. The engine falls
	 * back to the English order and says so in the log, which nobody reads while translating.
	 */
	private static void checkSegmentOrderIsAPermutation(Map<String, JsonObject> files) {
		List<String> broken = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("segments") || !source.get("segments").isJsonArray()) {
					continue;
				}

				JsonArray segments = source.getAsJsonArray("segments");
				Set<Integer> taken = new HashSet<>();
				boolean ordered = false;

				for (int i = 0; i < segments.size(); i++) {
					JsonObject segment = segments.get(i).getAsJsonObject();

					if (!segment.has("order")) {
						continue;
					}

					ordered = true;
					int at = segment.get("order").getAsInt();

					if (at < 0 || at >= segments.size() || !taken.add(at)) {
						broken.add(relative + "#" + id(record) + " 的第 " + i + " 段写的 order 是 " + at
							+ "，不在 0.." + (segments.size() - 1) + " 里或者和别的段撞了");
					}
				}

				// Every position has to be claimed, not just no two claiming one: a record that gives
				// three of its four segments an order leaves the fourth on whichever slot is left,
				// which is a coincidence rather than a decision.
				if (ordered && taken.size() != segments.size()) {
					broken.add(relative + "#" + id(record) + " 只给一部分 segments 写了 order，"
						+ "剩下的段落在哪一位是碰运气；要写就每一段都写");
				}
			}
		}

		report("segments[].order 是一个完整排列", broken.isEmpty(), String.join("\n      ", broken));
	}

	/**
	 * Reading the segments in the order they will be drawn spells the record's own {@code zh}.
	 *
	 * <p>A complete permutation is not the same thing as the right permutation. {@code order} can be a
	 * flawless 0..n-1 and still put the words in an order nobody meant: Bingo_Card.json's
	 * {@code bingo_goal_wear_lapis_armor} drew 青金石套装穿戴的 4 件部件。 while its {@code zh} read
	 * 穿戴青金石套装的 4 件部件。, and {@code bingo_goal_kill_endermen} drew 在 8 10 只末影人秒内击杀
	 * and lost its full stop on the way (2026-08-30). Both passed every check there was, because the
	 * flat {@code zh} — the one a translator reads back to see whether the sentence is right — was
	 * never compared against what the segments actually build.
	 *
	 * <p>Only records that write {@code zh} and {@code order} together are compared. An empty
	 * {@code zh} beside a filled {@code segments} array is the ordinary way to say "the segments are
	 * the translation", and a record whose Chinese follows the English order needs no {@code order} at
	 * all, so neither is a disagreement.
	 */
	private static void checkOrderedSegmentsSpellTheTranslation(Map<String, JsonObject> files) {
		List<String> broken = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (JsonObject record : recordsOf(files.get(relative))) {
				JsonObject source = resolveRef(record, files);

				if (!source.has("segments") || !source.get("segments").isJsonArray()) {
					continue;
				}

				String zh = text(source, "zh");

				if (zh.isEmpty()) {
					continue;
				}

				List<JsonObject> segments = new ArrayList<>();

				for (JsonElement element : source.getAsJsonArray("segments")) {
					segments.add(element.getAsJsonObject());
				}

				if (segments.isEmpty() || !segments.stream().allMatch(s -> s.has("order"))) {
					continue;
				}

				segments.sort((left, right) -> Integer.compare(
					left.get("order").getAsInt(), right.get("order").getAsInt()
				));

				StringBuilder drawn = new StringBuilder();

				for (JsonObject segment : segments) {
					if (truthy(segment, "omit")) {
						continue;
					}

					drawn.append(text(segment, "zh"));
				}

				if (!drawn.toString().equals(zh)) {
					broken.add(relative + '#' + id(record) + " 按 order 画出来是 [" + drawn
						+ "]，但 zh 写的是 [" + zh + ']');
				}
			}
		}

		report("按 order 排好的 segments 拼出来就是 zh", broken.isEmpty(), String.join("\n      ", broken));
	}

	/** A half-width exclamation mark followed by more text has exactly one separating space. */
	private static void checkBangSpacing(Map<String, JsonObject> files) {
		List<String> broken = new ArrayList<>();

		for (String relative : new TreeSet<>(files.keySet())) {
			for (JsonObject record : recordsOf(files.get(relative))) {
				if (record.has("ref")) {
					continue;
				}

				String rendered;

				if (record.has("segments") && record.get("segments").isJsonArray()) {
					List<JsonObject> segments = new ArrayList<>();

					for (JsonElement element : record.getAsJsonArray("segments")) {
						segments.add(element.getAsJsonObject());
					}

					if (!segments.isEmpty() && segments.stream().allMatch(segment -> segment.has("order"))) {
						segments.sort((left, right) -> Integer.compare(
							left.get("order").getAsInt(), right.get("order").getAsInt()
						));
					}

					StringBuilder joined = new StringBuilder();

					for (JsonObject segment : segments) {
						if (truthy(segment, "omit")) {
							continue;
						}

						String zh = text(segment, "zh");
						joined.append(zh.isEmpty() ? text(segment, "text") : zh);
					}

					rendered = joined.toString();
				} else {
					rendered = text(record, "zh");
				}

				if (hasUnspacedBang(rendered)) {
					broken.add(relative + '#' + id(record) + " -> [" + rendered + ']');
				}
			}
		}

		report("感叹号后的正文都有空格", broken.isEmpty(), String.join("\n      ", broken));
	}

	private static boolean hasUnspacedBang(String text) {
		String tight = "!,.，。、；;：:）)】」』”’？?…";

		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) != '!') {
				continue;
			}

			int end = i + 1;
			while (end < text.length() && text.charAt(end) == '!') {
				end++;
			}

			if (end == i + 1 && end < text.length()
				&& !Character.isWhitespace(text.charAt(end)) && tight.indexOf(text.charAt(end)) < 0) {
				return true;
			}

			i = end - 1;
		}

		return false;
	}

	/**
	 * A record's own text, with placeholders filled in, as the game would send it — and with icons
	 * folded the way {@link io.github.bingkkni.skyzh.text.Translator} folds them before looking a
	 * line up, since a record collected under one icon spelling is filed under the other.
	 */
	private static String fillPlaceholders(String template, JsonObject source) {
		Matcher tokens = Pattern.compile("%(?:(\\d+)\\$)?[sd]").matcher(template);
		StringBuilder filled = new StringBuilder();
		int position = 0;

		while (tokens.find()) {
			int index = tokens.group(1) == null ? ++position : Integer.parseInt(tokens.group(1));

			tokens.appendReplacement(filled, Matcher.quoteReplacement(sampleValue(source, index)));
		}

		tokens.appendTail(filled);

		return Glyphs.canonical(filled.toString().replace("%%", "%"));
	}

	/**
	 * Something the placeholder at this position would actually accept.
	 *
	 * <p>{@code 1} used to stand in for every placeholder, which worked only for as long as every
	 * kind of capture happened to take a digit. A tier is spelled {@code XII} and refuses one, so a
	 * record holding one looked to this check like a record nothing answers for — a failure in the
	 * checker reported as a failure in the corpus, which is the worst way for a check to be wrong.
	 * An ordinal is spelled {@code 27th} and refuses a bare digit for the same reason.
	 *
	 * <p>The record's own {@code example} comes first, for that reason taken one step further: a
	 * {@code raw} placeholder is now bound to the kind of value its example is
	 * ({@link io.github.bingkkni.skyzh.text.ValueShape}), so feeding {@code 1} to a record whose
	 * example is {@code "[❥] [❥]"} would make the record refuse its own text and report a corpus that
	 * is perfectly correct as broken. The example is what the corpus says belongs there, so it is what
	 * this check puts there.
	 */
	private static String sampleValue(JsonObject source, int index) {
		String type = "";
		String example = "";

		if (source.has("placeholders") && source.get("placeholders").isJsonArray()) {
			JsonArray declared = source.getAsJsonArray("placeholders");

			if (index >= 1 && index <= declared.size()) {
				JsonObject placeholder = declared.get(index - 1).getAsJsonObject();
				type = text(placeholder, "type");
				example = text(placeholder, "example");
			}
		}

		Capture capture = Capture.of(type);

		// Only for the kind that has no shape of its own. A NUMBER whose example reads "1,234" is
		// still matched by "1", and taking the example there would test the corpus's prose rather
		// than the engine's rule.
		if (capture == Capture.PHRASE && !example.isEmpty() && capture.accepts(example)) {
			return example;
		}

		return switch (capture) {
			case TIER -> "XII";
			case ORDINAL -> "27th";
			default -> "1";
		};
	}

	private static String templateOf(JsonObject source) {
		if (source.has("segments") && source.get("segments").isJsonArray()) {
			StringBuilder joined = new StringBuilder();

			for (JsonElement element : source.getAsJsonArray("segments")) {
				joined.append(text(element.getAsJsonObject(), "text"));
			}

			return joined.toString();
		}

		return text(source, "text");
	}

	private static boolean hasChinese(JsonObject source) {
		if (source.has("continuation") && source.get("continuation").getAsBoolean()) {
			return true;
		}

		if (source.has("segments") && source.get("segments").isJsonArray()) {
			for (JsonElement element : source.getAsJsonArray("segments")) {
				if (!text(element.getAsJsonObject(), "zh").isEmpty()) {
					return true;
				}
			}

			return false;
		}

		return !text(source, "zh").isEmpty();
	}

	private static JsonObject resolveRef(JsonObject record, Map<String, JsonObject> files) {
		if (!record.has("ref")) {
			return record;
		}

		String[] parts = record.get("ref").getAsString().split("#", 2);
		JsonObject file = parts.length == 2 ? files.get(parts[0]) : null;

		if (file != null) {
			for (JsonObject candidate : recordsOf(file)) {
				if (candidate.has("id") && candidate.get("id").getAsString().equals(parts[1])) {
					return candidate;
				}
			}
		}

		return record;
	}

	/**
	 * The record arrays of a file, kept apart and in order.
	 *
	 * <p>Unlike {@link #recordsOf}, which flattens everything into one list: "the line above" only
	 * means anything inside one array, and a lore block's order is the order it is drawn in.
	 */
	private static List<List<JsonObject>> groupsOf(JsonObject file) {
		List<List<JsonObject>> groups = new ArrayList<>();

		for (Map.Entry<String, JsonElement> member : file.entrySet()) {
			if (!member.getValue().isJsonArray()) {
				continue;
			}

			List<JsonObject> group = new ArrayList<>();

			for (JsonElement element : member.getValue().getAsJsonArray()) {
				if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
					group.add(element.getAsJsonObject());
				}
			}

			if (!group.isEmpty()) {
				groups.add(group);
			}
		}

		return groups;
	}

	private static List<JsonObject> recordsOf(JsonObject file) {
		List<JsonObject> records = new ArrayList<>();

		for (Map.Entry<String, JsonElement> member : file.entrySet()) {
			JsonElement value = member.getValue();

			if (value.isJsonArray()) {
				for (JsonElement element : value.getAsJsonArray()) {
					if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
						records.add(element.getAsJsonObject());
					}
				}
			} else if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
				records.add(value.getAsJsonObject());
			}
		}

		return records;
	}

	private static Surface surfaceOf(String relative) {
		String[] parts = relative.replace('\\', '/').split("/");
		return Surface.fromDirectory(parts.length >= 3 ? parts[parts.length - 2] : parts[0]);
	}

	private static boolean falsey(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && !object.get(key).getAsBoolean();
	}

	/**
	 * The opposite default from {@link #falsey}: the flag is off unless the record says otherwise.
	 *
	 * <p>{@code translate} is on unless a record turns it off, so asking about it wants {@code falsey}.
	 * {@code omit} is the other way round — a segment is drawn unless it says {@code "omit": true} —
	 * and reading it with {@code falsey} inverts the answer: every ordinary segment looks omitted and
	 * every omitted one looks ordinary. That is how {@code 感叹号后的正文都有空格} came to rebuild a
	 * line out of the <em>English</em> of the segment the Chinese drops, and fail a record that renders
	 * correctly in game (Bingo_Card.json#bingo_card_community_diagonal_hint_1, 2026-08-30). The engine
	 * itself has always read the flag the right way round, see {@code TranslationLoader} line 262.
	 */
	private static boolean truthy(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
	}

	private static String text(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
	}

	/** Every corpus file, keyed the way the mod keys them inside the jar. Shared with {@link LogAudit}. */
	static Map<String, JsonObject> readCorpus(Path root) throws Exception {
		Map<String, JsonObject> files = new HashMap<>();

		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.filter(Files::isRegularFile).toList()) {
				// Same separator the mod sees inside the jar, so a "ref" written with forward slashes
				// resolves whether the harness is run on Linux or Windows.
				String relative = root.relativize(path).toString().replace('\\', '/');

				if (!relative.endsWith(".json")) {
					continue;
				}

				try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
					JsonElement parsed = JsonParser.parseReader(reader);
					files.put(relative, parsed.getAsJsonObject());
				}
			}
		}

		return files;
	}

	static void installIndex(TranslationIndex index) throws Exception {
		var field = Translator.class.getDeclaredField("index");
		field.setAccessible(true);
		field.set(null, index);
	}

	private static void check(String name, String input, Surface surface, String expected) {
		checkComponent(name, Component.literal(input), surface, expected);
	}

	/** First-line tooltip names need no Font, so their matching and original-label behaviour are safe here. */
	private static void checkTooltipName(String name, String input, String expected, boolean showOriginal) {
		Component source = Component.literal(input);
		Translator.Result translated = TooltipTranslator.translateItemName(source);
		Component actual = translated.matched() && showOriginal
			? OriginalLabel.append(translated.padded(), source)
			: translated.padded();
		report(name, expected.equals(legacy(actual)), "期望 [" + expected + "] 实际 [" + legacy(actual) + "]");
	}

	/** A tab-list row, which is a label and a value rather than one sentence. */
	private static void checkRow(String name, String input, String expected) {
		String actual = legacy(Translator.translateRow(Component.literal(input), Surface.TABLIST));
		report(name, expected.equals(actual), "期望 [" + expected + "] 实际 [" + actual + "]");
	}

	/** The action bar, which goes through the widget split rather than a single lookup. */
	private static void checkWidgets(String name, String input, String expected) {
		String actual = legacy(Translator.translateWidgets(Component.literal(input), Surface.ACTION_BAR));
		report(name, expected.equals(actual), "期望 [" + expected + "] 实际 [" + actual + "]");
	}

	/**
	 * An item's enchantment line, which is several records rather than one.
	 *
	 * @param expected what the line should read as, or {@code null} to assert the line is left alone —
	 *                 the answer for prose that happens to have a comma in it, and the failure this
	 *                 whole path has to be held to
	 */
	private static void checkEnchantments(String name, String input, String expected) {
		Component actual = Translator.translateList(Component.literal(input), Surface.ITEM);

		if (expected == null) {
			report(name, actual == null, "本该原样不动，实际 [" + (actual == null ? "-" : legacy(actual)) + "]");
			return;
		}

		String drawn = actual == null ? "(原样不动)" : legacy(actual);
		report(name, expected.equals(drawn), "期望 [" + expected + "] 实际 [" + drawn + "]");
	}

	private static void checkComponent(String name, Component input, Surface surface, String expected) {
		String actual = legacy(Translator.translateLine(input, surface));
		report(name, expected.equals(actual), "期望 [" + expected + "] 实际 [" + actual + "]");
	}

	/**
	 * Asserts whether a record would report that the line it matched has more colours in it than the
	 * record can reproduce — the warning that asks a translator to split the record into
	 * {@code segments}. Crying wolf here is expensive: it sends somebody to rewrite a record that was
	 * already rendering correctly.
	 *
	 * @param plain    the line as the corpus stores it, used to find the record
	 * @param coloured the same line as the game draws it
	 */
	private static void checkColourLoss(String name, String plain, String coloured, Surface surface, boolean expected) {
		checkColourLossComponent(name, plain, Component.literal(coloured), surface, expected);
	}

	/** The same, for a line whose runs carry more than a colour — a hover, a click, an insertion. */
	private static void checkColourLossComponent(
		String name, String plain, Component coloured, Surface surface, boolean expected
	) {
		TranslationEntry entry = Translator.index().lookup(surface, plain);

		if (entry == null) {
			report(name, false, "语料里找不到 [" + plain + "]");
			return;
		}

		StyledText styled = StyledText.of(coloured);
		Matcher match = entry.match(styled.plain());
		boolean actual = match != null && entry.losesColour(styled, match);

		report(name, match != null && actual == expected,
			"匹配=" + (match != null) + " 期望颜色丢失=" + expected + " 实际=" + actual);
	}

	private static void checkLayout(
		Map<String, JsonObject> files, String file, String id, String expected
	) {
		JsonObject source = files.get(file);
		JsonObject found = null;

		if (source != null) {
			for (JsonObject record : recordsOf(source)) {
				if (id.equals(text(record, "id"))) {
					found = record;
					break;
				}
			}
		}

		String actual = found == null ? "" : text(found, "layout");
		report("居中布局标记 " + id, expected.equals(actual),
			found == null ? "未找到记录" : "期望 [" + expected + "] 实际 [" + actual + "]");
	}

	private static void checkNoMatch(String name, String input, Surface surface) {
		Translator.Result result = Translator.translate(Component.literal(input), surface);
		report(name, !result.matched(), "本不该匹配，却匹配到了 " + (result.entry() == null ? "" : result.entry().id()));
	}

	private static void report(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
			System.out.println("  [通过] " + name);
		} else {
			failed++;
			System.out.println("  [失败] " + name + " — " + detail);
		}
	}

	/** Re-encodes a component as a legacy string, so colours are visible in the console. */
	static String legacy(Component component) {
		StyledText styled = StyledText.of(component);
		StringBuilder out = new StringBuilder();
		Style previous = null;

		for (int i = 0; i < styled.length(); i++) {
			Style style = styled.styleAt(i);

			if (!style.equals(previous)) {
				out.append(codes(style));
				previous = style;
			}

			out.append(styled.plain().charAt(i));
		}

		return out.toString();
	}

	private static String codes(Style style) {
		StringBuilder out = new StringBuilder();

		if (style.getColor() != null) {
			for (ChatFormatting formatting : ChatFormatting.values()) {
				Style probe = Style.EMPTY.applyLegacyFormat(formatting);

				if (probe.getColor() != null && probe.getColor().equals(style.getColor())) {
					out.append(formatting);
					break;
				}
			}
		}

		if (style.isBold()) {
			out.append("\u00a7l");
		}

		if (style.isItalic()) {
			out.append("\u00a7o");
		}

		if (style.isUnderlined()) {
			out.append("\u00a7n");
		}

		if (style.isStrikethrough()) {
			out.append("\u00a7m");
		}

		if (style.isObfuscated()) {
			out.append("\u00a7k");
		}

		return out.toString();
	}
}
