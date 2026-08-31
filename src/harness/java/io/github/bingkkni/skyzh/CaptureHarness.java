package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.Areas;
import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.capture.CaptureContext;
import io.github.bingkkni.skyzh.capture.CaptureStore;
import io.github.bingkkni.skyzh.capture.CaptureSurface;
import io.github.bingkkni.skyzh.capture.CaptureWriter;
import io.github.bingkkni.skyzh.capture.CapturedLine;
import io.github.bingkkni.skyzh.capture.ChatShape;
import io.github.bingkkni.skyzh.capture.Classifier;
import io.github.bingkkni.skyzh.capture.LegacyText;
import io.github.bingkkni.skyzh.capture.Unplaced;
import io.github.bingkkni.skyzh.text.LineShape;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TermTable;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import net.minecraft.network.chat.Component;

/**
 * Runs the runtime capture over text a real session produced, with no game around it.
 *
 * <p>Capture is the part of this project with the most room to be quietly wrong. It decides what is
 * worth writing down, what two lines are the same sentence, and which characters of a sentence are
 * values — and every one of those decisions shows up as a file somebody reads a week later, by which
 * time nobody remembers what was on screen. So the decisions are exercised here against strings taken
 * verbatim from a client log, where being wrong fails a build instead.
 *
 * <pre>
 *   ./gradlew checkCapture
 * </pre>
 */
public final class CaptureHarness {
	private static int passed;
	private static int failed;

	/** A gemstone message as Hypixel sends it, colour codes and icon included. */
	private static final String PRISTINE = "§d§lPRISTINE! §r§fYou found §r⸕ Flawed %s Gemstone §r§8x%s§r§f!";

	public static void main(String[] args) throws Exception {
		Path corpus = Path.of(args.length > 0 ? args[0] : "original_text");
		Path areas = corpus.getParent().resolve("src/main/resources/assets/skyzh/capture/areas.json");
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(corpus));
		TranslationHarness.installIndex(index);

		colours();
		templates();
		chatShapes();
		verdicts();
		names();
		layout();
		clearing();
		writeRetries();
		announcements();
		zones(areas);
		unplaced();
		widgets();
		shared();
		finishPendingWrites();

		System.out.println();
		System.out.printf("通过 %d / 失败 %d%n", passed, failed);

		if (failed > 0) {
			System.exit(1);
		}
	}

	// ---- colours are recorded exactly, because that is the whole point of capturing at all ----

	private static void colours() {
		StyledText line = styled("§7Damage: §c+65");
		LegacyText.Encoded encoded = LegacyText.encode(line);

		check("原样还原颜色码", encoded.raw(), "§7Damage: §c+65");
		check("按颜色分段", encoded.runs().size(), 2);
		check("第一段颜色", encoded.runs().getFirst().codes(), "§7");
		check("第二段文本", encoded.runs().get(1).text(), "+65");
		check("颜色码没有丢失信息", encoded.lossy(), false);

		// Component-supplied styles, not legacy codes: SkyBlock sends both and they must come out the
		// same, or half the corpus would be collected in a spelling the other half cannot match.
		StyledText nested = StyledText.of(
			Component.literal("Damage: ").withStyle(net.minecraft.ChatFormatting.GRAY)
				.append(Component.literal("+65").withStyle(net.minecraft.ChatFormatting.RED))
		);

		check("组件式着色也还原成 §码", LegacyText.encode(nested).raw(), "§7Damage: §c+65");

		check("粗体带颜色", LegacyText.encode(styled("§9§lWind Compass")).raw(), "§9§lWind Compass");

		// The failure nobody can see: a record written from the visible text never matches a line the
		// server drew with its icon font. Capture spells those out so the difference is on the page.
		check("私用区字符被转义出来", LegacyText.escape("❤ Health"), "❤ Health");
		check("私用区图标转义", LegacyText.escape("\uE010 Health"), "\\uE010 Health");
		check("不间断空格转义", LegacyText.escape("Wind\u00A0Compass"), "Wind\\u00A0Compass");
		check("普通文本不被转义", LegacyText.hasInvisible("§9Wind Compass"), false);
	}

	// ---- one sentence, many numbers ----

	private static void templates() {
		// Every one of these is a line out of logs/2026-08-20-1.log, in the counts it appeared in.
		CapturedLine line = capture(PRISTINE.formatted("Topaz", "23"));

		check("同一句话只是数字不同 → 归并", merge(line, PRISTINE.formatted("Topaz", "24")), true);
		check("宝石名不同 → 同一条模板", merge(line, PRISTINE.formatted("Aquamarine", "22")), true);
		merge(line, PRISTINE.formatted("Ruby", "19"));
		merge(line, PRISTINE.formatted("Citrine", "38"));

		CapturedLine.Rendered rendered = line.render();

		check("归并出的模板", rendered.text(), "PRISTINE! You found ⸕ Flawed %1$s Gemstone x%2$s!");
		check("模板保留颜色码", rendered.encoded().raw(),
			"§d§lPRISTINE! §fYou found §r⸕ Flawed %1$s Gemstone §8x%2$s§f!");
		check("占位符数量", rendered.placeholders().size(), 2);
		check("宝石名位置类型", rendered.placeholders().getFirst().type(), "raw");
		check("数量位置类型", rendered.placeholders().get(1).type(), "number");
		check("观测到的宝石名", rendered.placeholders().getFirst().observed().size(), 4);
		check("观测到的数量", String.join(",", rendered.placeholders().get(1).observed()), "23,24,22,19,38");
		check("出现次数", line.count(), 5);

		// The digit rule. x23 and x24 share "x2" as characters; only the "x" is the sentence.
		CapturedLine two = capture("You found x23!");
		merge(two, "You found x24!");
		check("共同前缀不吃掉数字", two.render().text(), "You found x%1$s!");

		// A hole that opens a new colour. SkyBlock draws the value in a colour of its own far more
		// often than not, so the token has to close the run in front of it and start one of its own —
		// getting this wrong swallowed the value's colour into the label's and lost it entirely.
		CapturedLine coloured = capture("§7Damage: §c+65");
		merge(coloured, "§7Damage: §c+70");
		CapturedLine.Rendered coloured_out = coloured.render();
		check("占位符另起颜色时不吞掉那段颜色", coloured_out.encoded().raw(), "§7Damage: §c+%1$s");
		check("占位符自己成段", coloured_out.encoded().runs().size(), 2);
		check("占位符那一段的文本就是 token", coloured_out.encoded().runs().get(1).text(), "+%1$s");

		CapturedLine timed = capture("Ends in: 12m");
		merge(timed, "Ends in: 30m");
		check("共同后缀里的单位留在句子里", timed.render().text(), "Ends in: %1$sm");

		// Refusals: different sentences must not be smeared into one template.
		CapturedLine other = capture("You found a Goblin Egg!");
		check("句子不同 → 不归并", merge(other, "The wind has changed direction!"), false);
		check("词数不同 → 不归并", merge(other, "You found a Blue Goblin Egg!"), false);
		check("标点不同 → 不归并", merge(other, "You found a Goblin Egg?"), false);

		CapturedLine two_words = capture("Alpha Beta");
		check("整句都在变 → 不归并（会变成万能模板）", merge(two_words, "Gamma Delta"), false);

		numbersAndClocks();
	}

	/**
	 * The two merges that used to be refused, and the refusals that must survive relaxing them.
	 *
	 * <p>Both refusals came from counting whole unvarying words and requiring at least one. That
	 * misses two things a real session is full of: the sentence's only word can be <em>inside</em>
	 * the varying one, and a position that has only ever held digits was never one of the sentence's
	 * words to begin with. Between them they cost one session a hundred and forty sidebar records and
	 * a record per stat row in every menu.
	 */
	private static void numbersAndClocks() {
		// The sidebar clock, out of untranslated/Mining/ScoreBoard/Sidebar.json: a record per ten
		// in-game minutes, and the "pm" every one of them shares is the whole of the sentence.
		CapturedLine clock = capture("§e §73:30pm §e☀");
		check("时钟只差数字 → 归并", merge(clock, "§e §73:40pm §e☀"), true);
		merge(clock, "§e §712:00pm §e☀");
		check("时钟归并出的模板", clock.render().text(), " %1$spm ☀");

		CapturedLine night = capture("§7 §72:50am");
		check("没有图标的时钟也归并", merge(night, "§7 §73:00am"), true);
		check("句子只剩单位也算有词", night.render().text(), " %1$sam");

		// Same label, two numbers. Two varying positions against one literal word used to be refused
		// as a grammar; numbers are not words, so this is one stat row, not two.
		CapturedLine stat = capture("§7Damage: §c+187 §8(+661.3)");
		check("同一个标签只是数字不同 → 归并", merge(stat, "§7Damage: §c+500 §8(+1,895)"), true);
		check("标签和正负号都留在句子里", stat.render().text(), "Damage: +%1$s (+%2$s)");

		// And the refusals. A different label is a different record — the corpus requires one per
		// label, or "%s: %s" shadows the whole sidebar.
		check("标签不同 → 不归并", merge(stat, "§7Health: §c+110 §8(+416.9)"), false);

		CapturedLine gem = capture("§aOnyx");
		check("整行就是个名字 → 不归并", merge(gem, "§aAmber"), false);

		CapturedLine starred = capture("§6Terminator ✪✪✪✪✪");
		check("物品名后面的星星不足以撑起模板", merge(starred, "§6Hyperion ✪✪✪✪✪"), false);

		CapturedLine bare = capture("§7 2:50 ");
		check("归并后一个词都不剩 → 不归并", merge(bare, "§7 3:00 "), false);
	}

	// ---- whose sentence is this ----

	private static void chatShapes() {
		check("NPC 台词认得出名字", ChatShape.npcName("[NPC] Fragilis: Hello there!"), "Fragilis");
		check("NPC 名字带空格", ChatShape.npcName("[NPC] Keeper of the Crystal: Hi."), "Keeper of the Crystal");
		check("NPC 不算玩家发言", ChatShape.isPlayerChat("[NPC] Fragilis: Hello there!"), false);
		// [SECURITY] Sloth wears a bracketed tag and a one-word name, which is exactly a player's
		// shape. Read as chat it was dropped on the floor: never captured, so never reported missing,
		// and never translated. It has to be recognised as a speaker tag on both sides.
		check("Sloth 的安全提示不算玩家发言",
			ChatShape.isPlayerChat("[SECURITY] Sloth: Downloading suspicious mods is risky!"), false);
		check("Sloth 认得出名字",
			ChatShape.npcName("[SECURITY] Sloth: Downloading suspicious mods is risky!"), "Sloth");

		check("带等级和称号的玩家发言", ChatShape.isPlayerChat("[123] [MVP+] Someone: hi"), true);
		check("裸名玩家发言", ChatShape.isPlayerChat("Someone: hi"), true);
		check("公会频道", ChatShape.isPlayerChat("Guild > Someone [Officer]: hi"), true);
		check("组队频道", ChatShape.isPlayerChat("Party > Someone: hi"), true);
		check("私聊", ChatShape.isPlayerChat("To Someone: hi"), true);

		check("服务器广播不算玩家发言", ChatShape.isPlayerChat("GONE WITH THE WIND STARTED!"), false);
		check("带冒号的系统消息不算玩家发言",
			ChatShape.isPlayerChat("Commission Complete: Mithril Miner! Rewards:"), false);
		check("拾取提示不算玩家发言", ChatShape.isPlayerChat("  Wishing Compass x3"), false);

		// Hypixel draws the guild's icon between the level and the rank, with no brackets round it.
		// Three strangers' sentences were written into a capture file because of that one symbol.
		check("等级+公会图标+称号的玩家发言", ChatShape.isPlayerChat("[195] ⸕ [MVP+] potfire: just you"), true);
		check("另一种公会图标", ChatShape.isPlayerChat("[365] ᛝ [MVP+] _Lady_Luck: a bit"), true);
		// And the symbol may not open a line, or the server's own decorated messages go with them.
		check("符号开头的系统消息仍不算玩家发言", ChatShape.isPlayerChat("» Reward: 3 coins"), false);

		check("locraw 的 JSON 不是给人看的",
			ChatShape.isMachineReadable("{\"server\":\"mini26CB\",\"gametype\":\"SKYBLOCK\"}"), true);
		check("普通消息不是机读的", ChatShape.isMachineReadable("You earned 140 GEXP!"), false);
	}

	// ---- which pile, and why ----

	private static void verdicts() {
		checkVerdict("没有记录应答 → 未翻译", CaptureSurface.SCOREBOARD, "§7Some Line Nobody Wrote Down",
			Classifier.Bucket.UNTRANSLATED);
		checkNothing("已经译好的行不采集", CaptureSurface.GUI_ITEM, "§8This item can be reforged!");
		checkNothing("纯数字没有可翻译的东西", CaptureSurface.SCOREBOARD, "§a1,234");
		checkNothing("风向罗盘箭头行没有词", CaptureSurface.SCOREBOARD, "§9⋖ §7≈ §9⋗");
		checkNothing("Tab 列表里的玩家名不采集", CaptureSurface.TABLIST, "§7[123] §b[MVP§c+§b] §aSomeone");
		// Hypixel hangs a guild icon, an AFK mark and a party symbol off the end of a tab-list row.
		// Without them here, half of one session's tab capture was a list of strangers' usernames.
		checkNothing("名字后面挂着公会图标也不采集", CaptureSurface.TABLIST, "§8[§2184§8] §binkkni §b§lᛝ");
		checkNothing("挂两个符号也不采集", CaptureSurface.TABLIST, "§8[§4489§8] §bCrayolaShokz §b§lᛝ§7♲");
		checkNothing("Tab 里已翻译的小节标题不采集", CaptureSurface.TABLIST, "§9§lCommissions:");
		checkVerdict("Tab 里没记录的行照常采集", CaptureSurface.TABLIST, "§9§lQuiver: §f14 arrows",
			Classifier.Bucket.UNTRANSLATED);
		// The tab list peels a value off after the colon before looking a label up, so a row whose
		// label is translated but whose value is not must not come back as untranslated.
		checkNothing("Tab 行的数值部分不算未翻译", CaptureSurface.TABLIST, "§9Mining Speed: §a1,234");

		// The diagnostic this whole feature was built to make routine: the corpus does cover this
		// sentence, and one invisible character is all that keeps the two apart.
		Classifier.Verdict miss = Classifier.of(CaptureSurface.SCOREBOARD, styled("§9Wind\u00A0Compass"));
		check("差一个不可见字符时报出近似记录", miss != null && miss.nearMiss() != null, true);

		if (miss != null && miss.nearMiss() != null) {
			check("近似记录指名道姓", miss.nearMiss().id(), "mining_sb_wind_compass_header");
			check("近似记录写出实际文本", miss.nearMiss().actual(), "Wind\\u00A0Compass");
		}

		Classifier.Verdict clean = Classifier.of(CaptureSurface.SCOREBOARD, styled("§9Wind Compass"));
		check("能匹配上的行根本不进采集", clean, null);

		// The third pile. Every word came out Chinese and the line is still wrong on screen, because
		// the server changes colour partway through a run the corpus recorded as one. Nothing else
		// notices this — the record looks finished, and the log can only say which record, never where
		// the colour changed. The capture file can, and writes the segments array to prove it.
		checkVerdict("译文全中文、颜色被压平 → 颜色失真那一堆", CaptureSurface.GUI_ITEM,
			"§8This item can be §creforged!", Classifier.Bucket.COLOUR);
		checkNothing("同一句话整行一个颜色时什么都不采",
			CaptureSurface.GUI_ITEM, "§8This item can be reforged!");

		Classifier.Verdict flattened =
			Classifier.of(CaptureSurface.GUI_ITEM, styled("§8This item can be §creforged!"));
		check("颜色失真那一堆指名是哪条记录",
			flattened != null && !flattened.recordId().isEmpty(), true);

		// Values a record deliberately keeps in English are not "mixed" and must not be reported —
		// otherwise the pile fills with things nobody is ever going to change. Both of these were
		// false positives the first time the capture was run over a real log, and both were fixed in
		// the data: the placeholder's declared type decides whether the term table is consulted at
		// all, and these two say "leave it alone".
		checkNothing("宝石种类名保留英文,不算混杂", CaptureSurface.CHAT_MESSAGE,
			"§d§lPRISTINE! §r§fYou found §r§a\uE01C Flawed Topaz Gemstone §r§8x23§r§f!");
		checkNothing("档案名保留英文,不算混杂", CaptureSurface.CHAT_MESSAGE,
			"§aYou are playing on profile: §ePapaya§b (Co-op)");

		mixed();
	}

	/** The second pile: a record answered and the line still came out half English. */
	private static void mixed() {
		TermTable scopedTerms = TermTable.from(JsonParser.parseString("""
			{
			  "applies_to_types": ["location_name", "raw"],
			  "terms": [
			    {"en": "Farm", "zh": "农场", "types": ["location_name"]},
			    {"en": "DONE", "zh": "已完成"}
			  ]
			}
			""").getAsJsonObject());
		check("限定类型的地点词用于地点", scopedTerms.translate("location_name", "Farm"), "农场");
		check("限定类型的地点词不污染普通值", scopedTerms.translate("raw", "Farm"), null);
		check("未限定类型的词仍用于所有已启用类型", scopedTerms.translate("raw", "DONE"), "已完成");

		TranslationEntry half = TranslationEntry.compile(
			"half", "test.json", List.of("Grants ", "Mithril Powder"), List.of("额外获得 ", ""),
			false, "", Map.of(), Map.of()
		);

		StyledText line = styled("§7Grants §2Mithril Powder");
		Matcher match = half.match(line.canonical());
		check("半句未译能匹配", match != null, true);

		if (match != null) {
			TranslationEntry.Mixed mixed = half.mixed(line, match, Translator.index().terms());
			check("报出还留着英文的那一段", String.join("|", mixed.words()), "Mithril Powder");
		}

		// A placeholder whose value the term table is asked about and has no Chinese for — the
		// "Royal Mines钛" case, which is a missing line in Terms.json rather than a missing record.
		TranslationEntry commission = TranslationEntry.compile(
			"commission", "test.json", List.of("%s Mithril"), List.of("%s钛"),
			false, "", Map.of(1, "location_name"), Map.of()
		);

		StyledText known = styled("Royal Mines Mithril");
		Matcher inTable = commission.match(known.canonical());
		check("词表里有的地名不算混杂",
			inTable != null && !commission.mixed(known, inTable, Translator.index().terms()).any(), true);

		StyledText unknown = styled("Nowhere Land Mithril");
		Matcher outside = commission.match(unknown.canonical());
		check("词表里没有的地名报成缺词条",
			outside != null
				&& commission.mixed(unknown, outside, Translator.index().terms()).values().contains("Nowhere Land"),
			true);
	}

	// ---- where a capture ends up on disk ----

	private static void names() {
		check("菜单名变文件名（颜色码整个剥掉）",
			Classifier.fileName("§aHeart of the Mountain"), "Heart_of_the_Mountain");
		check("斜杠等非法字符被换掉", Classifier.fileName("Sack of Sacks / Storage"), "Sack_of_Sacks_Storage");
		check("空名字落进未知桶", Classifier.fileName("   "), "_Unknown_Name");
		check("菜单名里的 §q 不会变成文件名的一部分",
			Classifier.fileName("§aHeart of the M§qountain"), "Heart_of_the_Mountain");
		check("id 来自句子本身", Classifier.id("PRISTINE! You found ⸕ Flawed Topaz Gemstone x23!"),
			"pristine_you_found_flawed_topaz_gemstone_x23");

		JsonObject record = CaptureWriter.record(capture("§7Damage: §c+65"));
		check("写出的记录带 raw", record.get("raw").getAsString(), "§7Damage: §c+65");
		check("写出的记录带干净原文", record.get("text").getAsString(), "Damage: +65");
		check("写出的记录 zh 留空", record.get("zh").getAsString(), "");
		check("变色的行带 segments", record.has("segments"), true);
		check("采集元数据集中在 _capture 下", record.has("_capture"), true);

		// The colour pile's whole point is that the file carries the split the record is missing.
		CapturedLine flattened = new CapturedLine(
			CaptureSurface.GUI_ITEM, styled("§8This item can be §creforged!"), "",
			Classifier.of(CaptureSurface.GUI_ITEM, styled("§8This item can be §creforged!")),
			"Dwarven Mines", System.currentTimeMillis()
		);
		JsonObject colour = CaptureWriter.record(flattened);
		check("颜色失真的记录带着按实际颜色切好的 segments",
			colour.getAsJsonArray("segments").size(), 2);
		check("颜色失真的记录说清楚要去改哪条",
			colour.getAsJsonObject("_capture").has("colour_fix"), true);
		check("采集记录写下当时人在哪个岛",
			colour.getAsJsonObject("_capture").getAsJsonArray("seen_in").get(0).getAsString(),
			"Dwarven Mines");
	}

	// ---- and the file that comes out the other end ----

	/**
	 * The whole pipeline, from a line off the wire to a file on disk.
	 *
	 * <p>What is being checked is the layout: {@code original_text/}'s own folders, one level under a
	 * pile. A capture that classified everything perfectly and then filed it somewhere nobody looks
	 * would be no more use than not capturing it.
	 */
	private static void layout() throws Exception {
		Path root = Files.createTempDirectory("skyzh-capture-harness");
		CaptureStore.root(root);

		long now = System.currentTimeMillis();
		accept(root, CaptureSurface.NPC_MESSAGE, "Mining", "Fragilis", "§fHello there, miner!", now);
		accept(root, CaptureSurface.GUI_ITEM, "Mining", "Commissions", "§7A line nobody wrote down yet", now);
		accept(root, CaptureSurface.SCOREBOARD, "_Unknown_Gameplay", "Sidebar", "§7Somewhere Unclassified", now);
		CaptureStore.flush();

		Path npc = root.resolve("untranslated/Mining/NPC_Message/Fragilis.json");
		check("NPC 台词按玩法/来源/名字落盘", Files.exists(npc), true);
		check("界面物品按菜单落盘",
			Files.exists(root.resolve("untranslated/Mining/GUI_Item/Commissions.json")), true);
		check("玩法认不出来时落进未知玩法",
			Files.exists(root.resolve("untranslated/_Unknown_Gameplay/ScoreBoard/Sidebar.json")), true);

		JsonObject file = JsonParser.parseString(Files.readString(npc, StandardCharsets.UTF_8)).getAsJsonObject();
		check("文件头写明玩法", file.get("gameplay").getAsString(), "Mining");
		check("文件头写明来源", file.get("surface").getAsString(), "NPC_Message");
		check("文件头写明名字", file.get("name").getAsString(), "Fragilis");
		check("记录数组叫 lines（和语料一致）", file.getAsJsonArray("lines").size(), 1);
		check("采集文件开头有搬运须知", file.has("_capture_note"), true);

		delete(root);
	}

	private static void accept(Path root, CaptureSurface surface, String gameplay, String name, String legacy, long now) {
		CaptureStore.accept(new CaptureStore.Sighting(
			surface, surface + " " + name + " " + legacy, styled(legacy), gameplay, "", name, "", now
		));
	}

	private static void delete(Path root) throws Exception {
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Exception ignored) {
					// A leftover temp directory is not worth failing a check over.
				}
			});
		}
	}

	private static void clearing() throws Exception {
		Path root = Files.createTempDirectory("skyzh-clear-harness");
		Path untranslated = root.resolve("untranslated/Mining/ChatMessage/One.json");
		Path mixed = root.resolve("mixed/Mining/GUI_Item/Two.json");
		Path colour = root.resolve("colour/Mining/GUI_Item/Three.json");
		Path keep = root.resolve("untranslated/notes.txt");
		Path outside = root.resolve("elsewhere/four.json");

		for (Path file : List.of(untranslated, mixed, colour, keep, outside)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, "{}", StandardCharsets.UTF_8);
		}

		CaptureWriter.Meta pending = new CaptureWriter.Meta(
			Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.CHAT_MESSAGE, "Server_Messages"
		);
		CaptureAnnouncer.record(pending, pending.path(root), "Dwarven Mines", 1_000_000L);

		check("clear 只计算三个分类里的 JSON", CaptureStore.clear(root), 3);
		check("clear 同时丢弃指向已删文件的待发提示",
			CaptureAnnouncer.due(1_010_000L).size(), 0);
		check("未翻译分类的 JSON 被删", Files.exists(untranslated), false);
		check("混杂分类的 JSON 被删", Files.exists(mixed), false);
		check("颜色分类的 JSON 被删", Files.exists(colour), false);
		check("分类里的未知文件保留", Files.exists(keep), true);
		check("三个分类以外的 JSON 保留", Files.exists(outside), true);
		check("未知文件让所在目录保留", Files.isDirectory(keep.getParent()), true);

		Path broken = Files.createTempDirectory("skyzh-clear-broken-harness");
		Files.writeString(broken.resolve("mixed"), "not a directory", StandardCharsets.UTF_8);
		Path later = broken.resolve("colour/Mining/GUI_Item/Later.json");
		Files.createDirectories(later.getParent());
		Files.writeString(later, "{}", StandardCharsets.UTF_8);

		boolean failedClear = false;

		try {
			CaptureStore.clear(broken);
		} catch (java.io.IOException expected) {
			failedClear = true;
		}

		check("分类路径不是目录时 clear 报错", failedClear, true);
		check("一个分类报错后仍尝试后面的分类", Files.exists(later), false);
		check("报错的未知文件不被误删", Files.exists(broken.resolve("mixed")), true);

		Path epochRoot = Files.createTempDirectory("skyzh-clear-epoch-harness");
		CaptureStore.root(epochRoot);
		java.lang.reflect.Field epochField = CaptureStore.class.getDeclaredField("epoch");
		epochField.setAccessible(true);
		long staleEpoch = epochField.getLong(null);
		CaptureStore.clear(epochRoot);

		CaptureStore.Sighting stale = new CaptureStore.Sighting(
			CaptureSurface.CHAT_MESSAGE, "stale-clear-key",
			styled("§7A stale clear harness line nobody translated"), "Mining", "Dwarven Mines",
			"Server_Messages", "", System.currentTimeMillis()
		);
		java.lang.reflect.Method accept = CaptureStore.class.getDeclaredMethod(
			"accept", CaptureStore.Sighting.class, long.class
		);
		accept.setAccessible(true);
		accept.invoke(null, stale, staleEpoch);
		CaptureStore.flush();

		Path staleFile = epochRoot.resolve("untranslated/Mining/ChatMessage/Server_Messages.json");
		check("clear 前已经出队的旧文本不能复活", Files.exists(staleFile), false);

		CaptureStore.accept(stale);
		CaptureStore.flush();
		check("clear 后的新文本仍能采集", Files.exists(staleFile), true);
		CaptureStore.clear(epochRoot);

		delete(root);
		delete(broken);
		delete(epochRoot);
	}

	private static void writeRetries() throws Exception {
		Path blocked = Files.createTempFile("skyzh-write-blocked-harness", ".tmp");
		Path recovered = Files.createTempDirectory("skyzh-write-retry-harness");
		CaptureStore.root(blocked);

		CaptureStore.accept(new CaptureStore.Sighting(
			CaptureSurface.CHAT_MESSAGE, "retry-write-key",
			styled("§7A retry write harness line nobody translated"), "Mining", "Dwarven Mines",
			"Server_Messages", "", System.currentTimeMillis()
		));
		CaptureStore.flush();

		CaptureStore.root(recovered);
		CaptureStore.flush();
		Path file = recovered.resolve("untranslated/Mining/ChatMessage/Server_Messages.json");
		check("一次写入失败后 dirty 记录会在下次 flush 重试", Files.exists(file), true);

		CaptureStore.clear(recovered);
		Files.deleteIfExists(blocked);
		delete(recovered);
	}

	// ---- what the player is told, and when ----

	/**
	 * The chat announcement, batched.
	 *
	 * <p>The batching is the part worth testing rather than the wording. A SkyBlock menu produces
	 * dozens of captures inside one packet and a chat box shows ten lines, so getting this wrong does
	 * not look like a bug — it looks like the chat scrolling away.
	 */
	private static void announcements() {
		Path root = Path.of("/tmp/skyzh-capture-harness");
		long now = 1_000_000L;

		// The layout check above ran real captures through the store, which announced them. Take those
		// out first, or this check would be counting them too.
		CaptureAnnouncer.due(System.currentTimeMillis() + 60_000);

		announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.GUI_ITEM, "Commissions", 41, now);
		announce(root, Classifier.Bucket.MIXED, "Mining", CaptureSurface.GUI_ITEM, "Commissions", 2, now);

		check("还在往里加的时候不发", CaptureAnnouncer.due(now + 500).size(), 0);

		List<Component> messages = CaptureAnnouncer.due(now + 1500);
		check("安静 1 秒后发出来", messages.size(), 1);
		check("一个菜单只发一条,不是 43 条",
			messages.getFirst().getString(),
			"[SkyZH] 采集 43 条 · Mining / GUI_Item / Commissions\n         未翻译 41、中英混杂 2  [打开未翻译]  [打开中英混杂]");
		check("发完就清空", CaptureAnnouncer.due(now + 9000).size(), 0);

		// One chat line is a batch of one: it goes quiet the moment it arrives, so nothing is delayed
		// by the machinery that exists for menus.
		announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.NPC_MESSAGE, "Fragilis", 1, now);
		List<Component> single = CaptureAnnouncer.due(now + 1000);
		check("单条聊天照样是一条消息", single.size(), 1);
		check("只有一堆时链接不分名字",
			single.getFirst().getString(),
			"[SkyZH] 采集 1 条 · Mining / NPC_Message / Fragilis\n         未翻译 1  [打开文件]");

		// Different names are different files, so they are different messages — the point of the line
		// is to say which file to go and look at.
		announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.GUI_TITLE, "Commissions", 1, now);
		announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.SCOREBOARD, "Sidebar", 1, now);
		check("不同文件分开发", CaptureAnnouncer.due(now + 1500).size(), 2);

		// A session that never stops capturing would otherwise never be told anything.
		for (long t = now; t <= now + 6000; t += 500) {
			announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.ACTION_BAR, "Action_Bar", 1, t);

			if (t < now + 5000) {
				check("持续采集时先不发（t=" + (t - now) + "）", CaptureAnnouncer.due(t).size(), 0);
			}
		}

		check("持续采集满 5 秒也会发一条", CaptureAnnouncer.due(now + 5000).size(), 1);

		check("链接指向那条采集自己的文件", clickedFile(single.getFirst()),
			root.resolve("untranslated/Mining/NPC_Message/Fragilis.json").toString());

		// Minecraft logs client-generated chat too, and auditLog reads that log. The prefix is what
		// keeps this mod's own announcements from coming back as untranslated SkyBlock text.
		check("提示消息带得住的前缀", single.getFirst().getString().startsWith(Feedback.PREFIX), true);

		// The message links to a file written on a slower timer, so the write has to happen first or
		// clicking the link the moment it appears opens nothing.
		boolean[] flushed = { false };
		announce(root, Classifier.Bucket.UNTRANSLATED, "Mining", CaptureSurface.BOSS_BAR, "Boss_Bar", 1, now);
		CaptureAnnouncer.due(now);
		CaptureAnnouncer.tick(() -> flushed[0] = true);
		check("发消息之前先落盘", flushed[0], true);

		boolean[] again = { false };
		CaptureAnnouncer.tick(() -> again[0] = true);
		check("没东西可发时不白落盘", again[0], false);
	}

	/** The path behind the first clickable piece of a message, or an empty string if there is none. */
	private static String clickedFile(Component message) {
		StringBuilder path = new StringBuilder();

		message.visit((style, text) -> {
			if (path.isEmpty() && style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.OpenFile open) {
				path.append(open.file().getPath());
			}

			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);

		return path.toString();
	}

	private static void announce(
		Path root, Classifier.Bucket bucket, String gameplay, CaptureSurface surface, String name, int times, long now
	) {
		CaptureWriter.Meta meta = new CaptureWriter.Meta(bucket, gameplay, surface, name);

		for (int i = 0; i < times; i++) {
			CaptureAnnouncer.record(meta, meta.path(root), "", now);
		}
	}

	// ---- where the player was standing ----

	/**
	 * The sidebar's location row, which decides the gameplay folder everything is filed under.
	 *
	 * <p>Every one of these strings is a raw sidebar row, colour codes and all, because that is what
	 * the row actually is. Reading it as though it were clean text is what put a whole session's
	 * capture into the unknown-gameplay folder: {@code Component#getString} flattens the component
	 * tree but leaves Hypixel's {@code §} codes inside the literals, so the place name came out as
	 * {@code "§7Dwarven Mines"} and matched nothing.
	 */
	private static void zones(Path areasFile) {
		check("矿区所在地行(带颜色码)", CaptureContext.zone("§7⏣ §7Dwarven Mines"), "Dwarven Mines");
		check("没有颜色码也认", CaptureContext.zone("⏣ Crystal Hollows"), "Crystal Hollows");
		check("服务器字体的 ⏣ 也认", CaptureContext.zone("§7\uE067 §7Dwarven Mines"), "Dwarven Mines");
		check("裂隙用自己的标记", CaptureContext.zone("§7ф §7Wizard Tower"), "Wizard Tower");
		check("地牢层数不算地名的一部分", CaptureContext.zone("§7⏣ §7The Catacombs §7(F7)"), "The Catacombs");
		check("别的行不是所在地行", CaptureContext.zone("§ePurse: §61,234"), "");

		// 计分板每一行必须是互不相同的字符串,Hypixel 就往行里塞一个 § 加一个字母(位置每行不同,
		// 字母也不是 Minecraft 认得的颜色码)。渲染器把「§ 加任意字符」整对吃掉,所以屏幕上看不见。
		// 只剥"认得的颜色码"的话,地名就成了 Dwarven M§qines —— 那一整局的文本都会因此落进未知玩法。
		check("§q 劈开的地名照样读得出", CaptureContext.zone("§7⏣ §7Dwarven M§qines"), "Dwarven Mines");
		check("行尾的 §q 不算地名的一部分", CaptureContext.zone("§7⏣ §7The Lift§q"), "The Lift");
		check("整行只有 §q 的行仍然不是所在地行", CaptureContext.zone("§qNone§q"), "");

		// 刚进服的第一块侧边栏上,所在地那一行写的是 ⏣ None ——服务器还没定下来。
		// 它长得和别的地名一模一样,含义却是"我还不知道";当成地名就等于把一整段进服过程
		// (整背包、欢迎语、第一份 Tab 列表)全归到一个叫 None 的地方去。
		check("服务器说 None 就等于没说", CaptureContext.zone("§7⏣ §7None§q"), "");
		check("Tab 列表说 None 同理", CaptureContext.tabArea("§rArea: §aNone"), "");
		check("真有个地方叫 None 之外的名字就照常读", CaptureContext.zone("§7⏣ §7Nowhere"), "Nowhere");

		// Tab 列表是同一件事的第二个读法:侧边栏那一行还没拼好的时候(每次传送都有这么一两秒)靠它兜底。
		check("Tab 列表上的岛屿名", CaptureContext.tabArea("§rArea: §aDwarven Mines§r"), "Dwarven Mines");
		check("Tab 列表的地牢层数不算地名", CaptureContext.tabArea("§rDungeon: §aCatacombs (F7)§r"), "Catacombs");
		check("Tab 列表上前后的空格不算名字", CaptureContext.tabArea("  §rArea: §aHub  "), "Hub");
		check("Tab 列表上别的行不是所在地行", CaptureContext.tabArea("§rPlayers: §a42"), "");
		check("Tab 列表上的 §q 同样不算内容", CaptureContext.tabArea("§rArea: §aDwarven M§qines"), "Dwarven Mines");
		check("侧边栏标题认得出 SkyBlock", CaptureContext.isSkyBlockTitle("§6§lSKYBLOCK"), true);
		check("逐字符变色的标题照样认得出",
			CaptureContext.isSkyBlockTitle("§bS§fK§bY§fB§bL§fO§bC§fK"), true);
		check("合作档案的标题也认", CaptureContext.isSkyBlockTitle("§6§lSKYBLOCK §fCO-OP"), true);
		check("别的服务器的计分板不认", CaptureContext.isSkyBlockTitle("§a§lBEDWARS"), false);

		// And the table the name is then looked up in, so a rename in the JSON fails here rather than
		// silently filing a session's worth of Mining text as unknown.
		Areas table = Areas.from(areasFile);
		check("矮人矿井 → Mining", table.gameplay("Dwarven Mines"), "Mining");
		check("水晶残核 → Mining", table.gameplay("Crystal Hollows"), "Mining");
		check("极冰矿井 → Mining", table.gameplay("Glacite Mineshafts"), "Mining");
		check("大小写不同也查得到", table.gameplay("dwarven mines"), "Mining");
		check("电梯间(实测采到的)也在表里", table.gameplay("The Lift"), "Mining");

		// Tab 列表读到的是岛屿名,和侧边栏的小地名不是一套写法,两套都得在表里查得到。
		check("Tab 列表的岛屿名也在表里 → Farming", table.gameplay("Garden"), "Farming");
		check("Tab 列表的地牢名也在表里 → Dungeons", table.gameplay("Catacombs"), "Dungeons");

		// Hypixel Mod API 给的是岛屿 id,和地名走同一张表:mode 不随文案改动,是三个读法里最稳的一个。
		check("Mod API 的岛屿 id → Mining", table.gameplay("mining_3"), "Mining");
		check("水晶残核的 id → Mining", table.gameplay("crystal_hollows"), "Mining");
		check("私人岛的 id → Hub_General", table.gameplay("dynamic"), "Hub_General");
		check("地牢的 id → Dungeons", table.gameplay("dungeon"), "Dungeons");

		// knows() 是"表里有没有"的问法,和 gameplay() 的区别是它不抱怨——三个读法轮流试的时候,
		// 用 gameplay() 去试会把没被采用的那两个读法也一并喊成"缺词条"。
		check("表里有就认", table.knows("Dwarven Mines"), true);
		check("岛屿 id 也认", table.knows("mining_3"), true);
		check("表里没有就不认", table.knows("Somewhere Nobody Listed"), false);
		check("空的不认", table.knows(""), false);
		// A place named after its owner is a different string for every player, so the table holds
		// what kind of place it is and the possessive is taken apart at lookup — otherwise a session
		// spent in the museum lands in the unknown folder under a name nobody could have listed.
		check("某人的博物馆 → Hub_General", table.gameplay("inkkni's Museum"), "Hub_General");
		check("某人的私人岛 → Hub_General", table.gameplay("SomePlayer's Island"), "Hub_General");
		check("所有格也算认得", table.knows("inkkni's Museum"), true);
		check("表里写全了的所有格不拆开", table.gameplay("Goblin Queen's Den"), "Mining");
		check("拆开之后还是不认识的照旧未知", table.gameplay("inkkni's Treehouse"), "_Unknown_Gameplay");

		// The three areas a 2026-08-21 session found unlisted, all of them corners of the Hub or of
		// the mines rather than places of their own.
		check("大厅的森林 → Hub_General", table.gameplay("Forest"), "Hub_General");
		check("大厅的战斗聚落 → Hub_General", table.gameplay("Combat Settlement"), "Hub_General");
		check("大厅农场 → Hub_General", table.gameplay("Farm"), "Hub_General");
		check("大厅选举室 → Hub_General", table.gameplay("Election Room"), "Hub_General");
		check("大厅交易中心 → Hub_General", table.gameplay("Trade Center"), "Hub_General");
		check("极冰大湖 → Mining", table.gameplay("Great Glacite Lake"), "Mining");

		check("没归类的区域落进未知玩法", table.gameplay("Somewhere Nobody Listed"), "_Unknown_Gameplay");
		check("空区域名落进未知玩法", table.gameplay(""), "_Unknown_Gameplay");

		// The bug this whole section exists for: a name that still has its colour code on it must not
		// quietly look like an unclassified area.
		check("带颜色码的地名查不到(说明剥码那步没做)", table.gameplay("§7Dwarven Mines"), "_Unknown_Gameplay");

		// The menu title has the same problem in a quieter place: it becomes a file name and the note
		// saying which menu an item came from, and a §9 on the front of either is noise in the output.
		CaptureContext.openScreen(7, Component.literal("§9Commissions"));
		check("菜单标题剥掉颜色码", CaptureContext.menu(7, 0), "Commissions");
		check("不是当前容器就没有菜单名", CaptureContext.menu(8, 0), "");
		check("背包(容器 0)没有菜单名", CaptureContext.menu(0, 0), "");

		// A chest menu's contents packet carries the player's own 36 slots after the menu's own, and
		// filing those under the menu is what made one session write the same pickaxe into sixty
		// files. 54 + 36: the last row of the menu is slot 53, the first backpack slot is 54.
		CaptureContext.contents(7, 90);
		check("菜单自己的格子算菜单", CaptureContext.menu(7, 53), "Commissions");
		check("尾巴上的背包格子不算菜单", CaptureContext.menu(7, 54), "");
		check("快捷栏也不算菜单", CaptureContext.menu(7, 89), "");
		CaptureContext.reset();
	}

	// ---- text that arrived before the sidebar said where the player was ----

	/**
	 * The window on every warp where the text is here and the area is not.
	 *
	 * <p>This is the bug the folder full of {@code _Unknown_Gameplay} was: not a table missing an
	 * entry and not a name read wrongly, but a question asked half a second too early. The sidebar is
	 * torn down and rebuilt on the other side of a warp while the server is already sending the
	 * island's welcome messages, so the loudest moment of a session is exactly the moment the area is
	 * unknown.
	 *
	 * <p>What is checked here is that waiting does not cost anything: nothing is dropped, nothing is
	 * filed twice, and the order lines reach the store in is the order they were seen — the store
	 * keeps the colours of the first sighting of a piece of text, so a held line arriving after a
	 * later one would record the wrong frame of an animated row.
	 */
	private static void unplaced() {
		Unplaced held = new Unplaced(3, 8_000L);
		long t = 1_000_000L;

		check("侧边栏还没说地点时先扣住不发", held.offer(sighting("一", t), null, t).size(), 0);
		check("扣住的行还在等", held.size(), 1);

		held.offer(sighting("二", t + 100), null, t + 100);
		List<CaptureStore.Sighting> released = held.offer(sighting("三", t + 200), "Mining", t + 200);

		check("地点一出来就把扣住的一起放行", released.size(), 3);
		check("放行顺序是看到的顺序", released.get(0).key(), "一");
		check("扣住的行补上了当时的玩法", released.get(0).gameplay(), "Mining");
		check("后来的行也归同一个玩法", released.get(2).gameplay(), "Mining");
		check("放完就不再扣着", held.size(), 0);

		// The sidebar never answering is a real outcome — a lobby, a limbo, a broken parse — and the
		// line is still worth keeping. It just has to be filed truthfully.
		Unplaced patient = new Unplaced(64, 8_000L);
		patient.offer(sighting("等不到", t), null, t);
		check("没到时限之前不放弃", patient.tick(null, t + 7_999L).size(), 0);

		List<CaptureStore.Sighting> expired = patient.tick(null, t + 8_000L);
		check("等够了就按未知玩法落盘", expired.size(), 1);
		check("等不到的行标成未知玩法", expired.getFirst().gameplay(), "_Unknown_Gameplay");

		// A queue that grows without limit is a memory leak wearing a feature's clothes, so the
		// ceiling has to give way — but by filing the oldest line, not by losing it.
		Unplaced full = new Unplaced(2, 8_000L);
		full.offer(sighting("旧", t), null, t);
		full.offer(sighting("中", t + 1), null, t + 1);
		List<CaptureStore.Sighting> pushed = full.offer(sighting("新", t + 2), null, t + 2);

		check("扣住的行数超上限就把最旧的一条落盘", pushed.size(), 1);
		check("被挤出去的是最旧的那条", pushed.getFirst().key(), "旧");
		check("挤出去的那条算未知玩法", pushed.getFirst().gameplay(), "_Unknown_Gameplay");
		check("上限之内的还扣着", full.size(), 2);

		// A disconnect is the one moment where waiting longer cannot help.
		check("断线时把扣住的按当时的玩法落盘", full.drain("Mining").size(), 2);
		check("断线后不再扣着任何东西", full.size(), 0);
	}

	// ---- the action bar is a HUD, not a sentence ----

	/**
	 * Taking the action bar apart into the widgets SkyBlock built it from.
	 *
	 * <p>The line here is verbatim out of {@code untranslated/Mining/ActionBar/Action_Bar.json},
	 * where it and eight hundred near-copies of it were one record each: every one of those numbers
	 * changes as the player walks, so whole lines never repeat and never match anything.
	 */
	private static void widgets() {
		String bar = "2,610/2,235\uE010     \uE067 The Lift     469/469\uE003 400\uE017     104/104\uE028     ";
		List<LineShape.Range> parts = LineShape.widgets(bar);

		check("动作栏按宽间隔切成部件", parts.size(), 4);
		check("第一个部件是血量", bar.substring(parts.getFirst().start(), parts.getFirst().end()),
			"2,610/2,235\uE010");
		check("所在地是自己一个部件", bar.substring(parts.get(1).start(), parts.get(1).end()), "\uE067 The Lift");
		check("单个空格不切开", bar.substring(parts.get(2).start(), parts.get(2).end()), "469/469\uE003 400\uE017");
		check("行尾的空白不算一个部件", bar.substring(parts.get(3).start(), parts.get(3).end()), "104/104\uE028");

		// A message that arrives on its own is one thing, and has to stay one thing or every record
		// the corpus already has for this surface stops matching.
		check("独立的一句话仍是一个部件", LineShape.widgets("Chisel Charges Remaining: 3").size(), 1);
		check("居中留白不产生空部件", LineShape.widgets("   PICK IT UP!   ").size(), 1);
		check("空行没有部件", LineShape.widgets("     ").size(), 0);
	}

	// ---- one line, sixty menus ----

	/**
	 * A menu line that turns up in another menu is one record, not two.
	 *
	 * <p>Every SkyBlock menu carries the same navigation buttons and the same boilerplate, and the
	 * player's own backpack rides along in every container packet. Filed per menu, one session wrote
	 * five thousand records that were copies of a record in the folder next door.
	 */
	private static void shared() throws Exception {
		Path root = Files.createTempDirectory("skyzh-capture-shared");
		CaptureStore.root(root);

		long now = System.currentTimeMillis();
		item(root, "Mining", "Commissions", "§aA Shared Line Nobody Wrote Down", "Commissions Lore", now);
		item(root, "Mining", "Bank", "§aA Shared Line Nobody Wrote Down", "Bank Lore", now + 1);
		CaptureStore.flush();

		check("第一个菜单留下记录",
			Files.exists(root.resolve("untranslated/Mining/GUI_Item/Commissions.json")), true);
		check("第二个菜单不再抄一份",
			Files.exists(root.resolve("untranslated/Mining/GUI_Item/Bank.json")), false);

		JsonObject line = find(
			root.resolve("untranslated/Mining/GUI_Item/Commissions.json"), "a_shared_line_nobody_wrote_down");

		check("记下它还在哪里出现过", line.getAsJsonArray("also_seen").get(0).getAsString(), "Bank Lore");
		check("次数把两次都算上", line.get("count").getAsInt(), 2);

		// Not across gameplays: filing a Foraging line into a Mining folder is contamination nobody
		// would spot afterwards.
		item(root, "Foraging", "Bank", "§aA Shared Line Nobody Wrote Down", "Bank Lore", now + 2);
		CaptureStore.flush();
		check("换了玩法就各归各的",
			Files.exists(root.resolve("untranslated/Foraging/GUI_Item/Bank.json")), true);

		CaptureStore.clear(root);
		delete(root);
	}

	/** A finish barrier writes sightings that are still waiting in the worker queue. */
	private static void finishPendingWrites() throws Exception {
		Path root = Files.createTempDirectory("skyzh-capture-finish");
		CaptureStore.start(root);
		CaptureStore.offer(new CaptureStore.Sighting(
			CaptureSurface.CHAT_MESSAGE, "finish-pending-key",
			styled("§7A queued finish harness line nobody translated"), "Mining", "Dwarven Mines",
			"Server_Messages", "", System.currentTimeMillis()
		));

		CaptureStore.finishPending();
		Path file = root.resolve("untranslated/Mining/ChatMessage/Server_Messages.json");
		check("关闭采集前会等队列里的文本真正落盘", Files.exists(file), true);

		CaptureStore.clear(root);
		delete(root);
	}

	/** One record's {@code _capture} block, by id — the file may hold records other checks left. */
	private static JsonObject find(Path file, String id) throws Exception {
		JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

		for (var element : json.getAsJsonArray("lines")) {
			JsonObject record = element.getAsJsonObject();

			if (record.get("id").getAsString().equals(id)) {
				return record.getAsJsonObject("_capture");
			}
		}

		throw new IllegalStateException("采集文件里没有 id 为 " + id + " 的记录：" + file);
	}

	private static void item(Path root, String gameplay, String menu, String legacy, String where, long now) {
		CaptureStore.accept(new CaptureStore.Sighting(
			CaptureSurface.GUI_ITEM, gameplay + menu + legacy, styled(legacy), gameplay, "", menu, where, now
		));
	}

	// ---- plumbing ----

	/** A line as the game thread would hand it over, keyed by something readable in a failure message. */
	private static CaptureStore.Sighting sighting(String key, long when) {
		return new CaptureStore.Sighting(
			CaptureSurface.CHAT_MESSAGE, key, styled("§7" + key), null, "", "Server_Messages", "", when
		);
	}

	private static StyledText styled(String legacy) {
		return StyledText.of(Component.literal(legacy));
	}

	private static CapturedLine capture(String legacy) {
		return new CapturedLine(
			CaptureSurface.CHAT_MESSAGE, styled(legacy), "", null, "", System.currentTimeMillis()
		);
	}

	private static boolean merge(CapturedLine line, String legacy) {
		return line.merge(styled(legacy), "", "", System.currentTimeMillis());
	}

	private static void checkVerdict(String name, CaptureSurface surface, String legacy, Classifier.Bucket expected) {
		Classifier.Verdict verdict = Classifier.of(surface, styled(legacy));
		check(name, verdict == null ? null : verdict.bucket(), expected);
	}

	private static void checkNothing(String name, CaptureSurface surface, String legacy) {
		check(name, Classifier.of(surface, styled(legacy)), null);
	}

	private static void check(String name, Object actual, Object expected) {
		if (java.util.Objects.equals(actual, expected)) {
			passed++;
			System.out.printf("  [通过] %s%n", name);
		} else {
			failed++;
			System.out.printf("  [失败] %s — 期望 [%s] 实际 [%s]%n", name, expected, actual);
		}
	}

	private CaptureHarness() {
	}
}
