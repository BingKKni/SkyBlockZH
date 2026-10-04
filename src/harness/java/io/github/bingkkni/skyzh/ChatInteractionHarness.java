package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationEntry;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Build-time data gate, not a runtime fallback. Synthetic actions are never sent to a server. */
public final class ChatInteractionHarness {
	private static final Pattern BRACKETS = Pattern.compile("\\[([^\\[\\]]+)\\]");
	private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]");
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sd]");
	// These are message/speaker tags, not choices. Unknown bracketed words are checked by default.
	private static final Set<String> TAGS = Set.of("NPC", "BOSS", "GUARD", "SECURITY", "CROWD",
		"STATUE", "SKULL", "PET", "YOU", "Bazaar", "Sacks", "Harp", "SkyBlock", "WATCHDOG ANNOUNCEMENT", "Important",
		"Berserk", "Mage", "Healer", "Archer", "Tank");
	private record Button(int start, int end, int ordinal) {}
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		Map<String, JsonObject> files = TranslationHarness.readCorpus(Path.of(args[0]));
		TranslationIndex index = TranslationLoader.compile(files);
		TranslationHarness.installIndex(index);
		Map<String, JsonObject> records = new HashMap<>();
		files.forEach((path, file) -> TranslationHarness.recordsOf(file).forEach(record ->
			records.put(path + "#" + record.get("id").getAsString(), record)));
		int chatRecords = 0, buttons = 0, colourSamples = 0;
		for (TranslationEntry entry : index.entries(Surface.CHAT)) {
			chatRecords++;
			String address = entry.sourceFile() + "#" + entry.id();
			JsonObject record = records.get(address);
			Set<String> refs = new HashSet<>();
			while (record != null && record.has("ref") && refs.add(record.get("ref").getAsString())) {
				record = records.get(record.get("ref").getAsString());
			}
			require(address + " 可解析原始记录", record != null);
			if (record == null) continue;
			boolean hasButtons = !buttons(entry.template()).isEmpty();
			// Audit all matchable recorded chat colours, not just button rows. Some old raw samples
			// have prose placeholder examples which cannot match; never invent a live sample for them.
			if (record.has("raw")) {
				var raw = CaptureReplay.decode(TranslationHarness.fillPlaceholders(record.get("raw").getAsString(), record));
				var located = Translator.locate(raw, Surface.CHAT);
				if (hasButtons) require(address + " raw 可匹配", located.matched());
				if (located.matched()) {
					colourSamples++;
					require(address + " raw 保留颜色", !located.entry().losesColour(located.core(), located.match()));
				}
			}
			if (!hasButtons) continue;
			buttons++;
			require(address + " 括号/正文/分隔符独立分段", formatProblems(record).isEmpty());
			for (String problem : formatProblems(record)) System.err.println("    " + problem);
			String sample = TranslationHarness.fillPlaceholders(entry.template(), record);
			StyledText plain = StyledText.of(Component.literal(sample));
			var match = entry.match(plain.canonical());
			require(address + " 样例可匹配", match != null);
			if (match == null) continue;
			for (int mode = 0; mode < 3; mode++) {
				Component input = sample(sample, mode);
				StyledText before = StyledText.of(input);
				Component direct = entry.render(before, entry.match(before.canonical()), index.terms());
				require(address + " 模式 " + mode + " 记录事件/颜色", interactions(input, direct, mode));
				Component block = Translator.translateChatBlock(input, 320, c -> StyledText.of(c).length() * 6);
				require(address + " 模式 " + mode + " 实际聊天路径", interactions(input, block, mode));
				require(address + " 不改原始组件", before.equals(StyledText.of(input)));
			}
		}
		negativeControls();
		blockAndQuiz();
		commandMentions();
		followPrompt();
		frostyPurchase();
		phoneWrapper();
		colours();
		System.out.printf("扫描 CHAT 可用记录 %d 条，按钮记录 %d 条，已匹配颜色样本 %d 条；通过 %d / 失败 %d%n",
			chatRecords, buttons, colourSamples, passed, failed);
		if (failed > 0) System.exit(1);
	}

	/** Require both sides of each bracket to be explicit boundaries, even when colours are equal. */
	private static List<String> formatProblems(JsonObject record) {
		String text = record.get("text").getAsString();
		Set<Integer> boundaries = new HashSet<>();
		boundaries.add(0);
		int at = 0;
		if (record.has("segments")) {
			for (var value : record.getAsJsonArray("segments")) {
				at += value.getAsJsonObject().get("text").getAsString().length();
				boundaries.add(at);
			}
		} else boundaries.add(text.length());
		List<String> problems = new ArrayList<>();
		for (Button button : buttons(text)) {
			for (int edge : new int[] {button.start(), button.start() + 1, button.end() - 1, button.end()}) {
				if (!boundaries.contains(edge)) problems.add("缺少分段边界 " + edge + "：" + text.substring(button.start(), button.end()));
			}
		}
		return problems;
	}

	private static List<Button> brackets(String text) {
		List<Button> result = new ArrayList<>();
		var matcher = BRACKETS.matcher(text);
		while (matcher.find()) result.add(new Button(matcher.start(), matcher.end(), result.size()));
		return result;
	}

	private static List<Button> buttons(String text) {
		// Tal Ker quotes a meme, not a game prompt. Do not exempt [x] in any other sentence.
		if (text.equals("Doubt [x]")) return List.of();
		return brackets(text).stream().filter(b -> {
			String label = text.substring(b.start() + 1, b.end() - 1);
			return ENGLISH.matcher(PLACEHOLDER.matcher(label).replaceAll("")).find() && !TAGS.contains(label)
				&& !label.startsWith("Lvl ") && !label.startsWith("Lv") && !label.startsWith("#");
		}).toList();
	}

	/** 0: no events; 1: only the words; 2: brackets too. Adjacent choices always differ. */
	private static Component sample(String text, int mode) {
		List<Button> buttons = buttons(text);
		MutableComponent result = Component.empty();
		for (int i = 0; i < text.length(); i++) {
			Style style = Style.EMPTY.withColor(ChatFormatting.YELLOW);
			for (Button button : buttons) {
				if (i < button.start() || i >= button.end()) continue;
				style = Style.EMPTY.withColor(button.ordinal() % 2 == 0 ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE).withBold(true);
				if (mode == 2 || mode == 1 && i > button.start() && i < button.end() - 1) {
					style = style.withClickEvent(new ClickEvent.RunCommand("/skyzh-test-" + button.ordinal()))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("choice " + button.ordinal())))
						.withInsertion("choice-" + button.ordinal());
				}
			}
			// Styles on parents exercise vanilla inheritance, not just literal-node styles.
			result.append(Component.empty().setStyle(style).append(Component.literal(text.substring(i, i + 1))));
		}
		return result;
	}

	private static boolean interactions(Component input, Component output, int mode) {
		StyledText before = StyledText.of(input), after = StyledText.of(output);
		List<Button> allBefore = brackets(before.plain()), allAfter = brackets(after.plain());
		if (allBefore.size() != allAfter.size()) return false;
		Set<Integer> actionPositions = new HashSet<>();
		for (Button button : buttons(before.plain())) {
			Button translated = allAfter.get(button.ordinal());
			if (translated.end() - translated.start() <= 2) return false;
			for (int i = translated.start(); i < translated.end(); i++) {
				boolean edge = i == translated.start() || i == translated.end() - 1;
				Style expected = before.styleAt(edge ? (i == translated.start() ? button.start() : button.end() - 1) : button.start() + 1);
				if (!expected.equals(after.styleAt(i))) return false;
				if (mode == 2 || mode == 1 && !edge) actionPositions.add(i);
			}
		}
		for (int i = 0; i < after.length(); i++) {
			Style style = after.styleAt(i);
			if (!actionPositions.contains(i) && (style.getClickEvent() != null || style.getHoverEvent() != null || style.getInsertion() != null)) return false;
		}
		return true;
	}

	private static void negativeControls() {
		for (String segments : List.of("", ",\"segments\":[{\"text\":\"[YES]\",\"zh\":\"[是]\"}]",
			",\"segments\":[{\"text\":\"[\",\"zh\":\"[\"},{\"text\":\"YES] \",\"zh\":\"是] \"}]")) {
			JsonObject bad = JsonParser.parseString("{\"id\":\"bad\",\"text\":\"[YES] \",\"zh\":\"[是] \"" + segments + "}").getAsJsonObject();
			require("故意损坏的分段必须被拒绝", !formatProblems(bad).isEmpty());
		}
		JsonObject shortButtons = JsonParser.parseString("{\"id\":\"short\",\"text\":\"[Y] [N]\",\"zh\":\"[是] [否]\"}").getAsJsonObject();
		require("单字母按钮不能绕过格式检查", buttons("[Y] [N]").size() == 2 && !formatProblems(shortButtons).isEmpty());
		require("纯数值/名称占位符标签不是字面按钮", buttons("Level %1$s ➡ [%2$s]").isEmpty());
		require("非梗文本的单字母 x 仍需检查", buttons("Choose: [x]").size() == 1 && buttons("Doubt [x]").isEmpty());
		Component input = sample("[YES] [NO]", 1);
		TranslationEntry bad = TranslationEntry.compile(new TranslationEntry.Definition("bad", "fixture",
			List.of(new TranslationEntry.Segment("[YES] [NO]", "[是] [否]")), Map.of(), TranslationEntry.Options.DEFAULT));
		require("事件测试能抓住段首括号吞掉点击", !interactions(input,
			bad.render(StyledText.of(input), bad.match("[YES] [NO]"), Translator.index().terms()), 1));
		require("事件测试能抓住两个按钮串用指令", !interactions(sample("[YES] [NO]", 2),
			Component.literal("[是] [否]").setStyle(StyledText.of(sample("[YES] [NO]", 2)).styleAt(0)), 2));
	}

	private static void blockAndQuiz() {
		String choices = "Select an option: [YES] [NO]";
		Component input = sample("[NPC] Test: " + choices + "\n  [GIVE ABIPHONE]\n", 1);
		Component output = Translator.translateChatBlock(input, 120, c -> StyledText.of(c).length() * 6);
		require("NPC 前缀、多行消息和窄聊天框仍保留按钮事件", interactions(input, output, 1));
		for (String marker : List.of("ⓐ", "ⓑ", "ⓒ")) {
			for (String answer : List.of("Year 518", "Year 523", "10 Fairy Souls", "12 Fairy Souls", "The Wither Lords")) {
				Style action = Style.EMPTY.withColor(ChatFormatting.GREEN)
					.withClickEvent(new ClickEvent.RunCommand("/quiz-test-" + marker))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("answer")));
				Component quiz = Component.empty().append(Component.literal("    " + marker + " ").withStyle(ChatFormatting.GOLD))
					.append(Component.empty().setStyle(action).append(answer));
				StyledText drawn = StyledText.of(Translator.translateChatBlock(quiz, 320, c -> StyledText.of(c).length() * 6));
				int label = drawn.plain().indexOf(marker) + 2;
				boolean valid = label > 1;
				for (int i = 0; i < drawn.length(); i++) {
					valid &= i < label ? drawn.styleAt(i).getClickEvent() == null : action.equals(drawn.styleAt(i));
				}
				require("Oruo 答案独立于序号的事件和颜色 " + marker + answer, valid);
			}
		}
		boolean enabled = SkyZHConfig.get().enabled;
		try {
			HoldOriginal.setActive(true);
			require("原文模式不改按钮组件", Translator.translateChatBlock(input, 320, c -> 6) == input);
			HoldOriginal.setActive(false);
			SkyZHConfig.get().enabled = false;
			require("总开关关闭不改按钮组件", Translator.translateChatBlock(input, 320, c -> 6) == input);
		} finally {
			HoldOriginal.setActive(false);
			SkyZHConfig.get().enabled = enabled;
		}
	}

	/** Synthetic events prove preservation if the server makes a command mention interactive. */
	private static void commandMentions() {
		Map<String, String> mentions = Map.of(
			"/bait", "You can also use /bait as a shortcut if you aren't feeling like paying me a visit!",
			"/scg", "Don't tell mum this, but I just use /scg to open it."
		);
		for (var mention : mentions.entrySet()) {
			String command = mention.getKey(), text = mention.getValue();
			int start = text.indexOf(command), end = start + command.length();
			Style action = Style.EMPTY.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.RunCommand("/skyzh-test-shortcut"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("synthetic shortcut")))
				.withInsertion("synthetic-shortcut");
			Component input = Component.empty()
				.append(Component.literal(text.substring(0, start)).withStyle(ChatFormatting.WHITE))
				.append(Component.empty().setStyle(action).append(command))
				.append(Component.literal(text.substring(end)).withStyle(ChatFormatting.WHITE));
			StyledText before = StyledText.of(input);
			StyledText drawn = StyledText.of(Translator.translateChatBlock(input, 120, c -> StyledText.of(c).length() * 6));
			int translated = drawn.plain().indexOf(command);
			boolean valid = translated >= 0 && !drawn.plain().equals(text);
			for (int i = 0; i < drawn.length(); i++) {
				Style style = drawn.styleAt(i);
				valid &= i >= translated && i < translated + command.length() ? action.equals(style)
					: style.getClickEvent() == null && style.getHoverEvent() == null && style.getInsertion() == null;
			}
			require(command + " 无括号指令提及保留事件且不扩散到空格和正文", valid);
			require(command + " 不修改原始组件", before.equals(StyledText.of(input)));
		}
	}

	private static void followPrompt() {
		Style follow = Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/skyzh-test-follow"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("synthetic follow")))
			.withInsertion("follow-only");
		Component input = Component.empty()
			.append(Component.literal("» ").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
			.append(Component.literal("Steve ").withStyle(ChatFormatting.GREEN))
			.append(Component.literal("is traveling to ").withStyle(ChatFormatting.YELLOW))
			.append(Component.literal("Dungeon Hub ").withStyle(ChatFormatting.GREEN))
			.append(Component.empty().setStyle(follow).append("FOLLOW"));
		StyledText before = StyledText.of(input);
		StyledText after = StyledText.of(Translator.translateChatBlock(input, 320, c -> StyledText.of(c).length() * 6));
		int start = after.plain().indexOf("跟随");
		boolean ok = start >= 0;
		for (int i = 0; i < after.length(); i++) {
			Style style = after.styleAt(i);
			ok &= i >= start && i < start + 2 ? follow.equals(style)
				: style.getClickEvent() == null && style.getHoverEvent() == null && style.getInsertion() == null;
		}
		require("无括号FOLLOW仅跟随正文可点击", ok);
		require("FOLLOW不改服务器组件", before.equals(StyledText.of(input)));
	}

	/** Wiki colours, synthetic actions: no real purchase command is inferred or sent. */
	private static void frostyPurchase() {
		Style action = Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/skyzh-test-frosty"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("synthetic snow cannon")))
			.withInsertion("synthetic-frosty");
		for (int mode = 0; mode < 3; mode++) {
			Style label = Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true);
			if (mode == 1) label = label.withClickEvent(action.getClickEvent())
				.withHoverEvent(action.getHoverEvent()).withInsertion(action.getInsertion());
			Component input = Component.empty().setStyle(mode == 2 ? action : Style.EMPTY)
				.append(Component.empty().setStyle(label).append("BUY SNOW CANNON"))
				.append(Component.literal(" (Click)").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)));
			StyledText before = StyledText.of(input);
			StyledText drawn = StyledText.of(Translator.translateChatBlock(input, 320, c -> StyledText.of(c).length() * 6));
			boolean valid = drawn.plain().equals("购买雪炮 (点击)");
			for (int i = 0; i < drawn.length(); i++) {
				Style expected = before.styleAt(i < "购买雪炮".length() ? 0 : "BUY SNOW CANNON".length());
				valid &= expected.equals(drawn.styleAt(i));
			}
			require("Frosty 无括号购买提示保留独立/继承事件、颜色和粗体 模式 " + mode, valid);
			require("Frosty 不修改服务器购买组件 模式 " + mode, before.equals(StyledText.of(input)));
		}
	}

	private static void phoneWrapper() {
		Style phone = Style.EMPTY.withColor(ChatFormatting.AQUA)
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("synthetic call marker")));
		Style body = Style.EMPTY.withColor(ChatFormatting.WHITE)
			.withClickEvent(new ClickEvent.RunCommand("/skyzh-test-dialogue"));
		Component input = Component.empty().append(Component.literal("[NPC] Dean: "))
			.append(Component.empty().setStyle(phone).append("✆ "))
			.append(Component.empty().setStyle(body).append("Oh, you are willing to help?"));
		StyledText before = StyledText.of(input);
		StyledText after = StyledText.of(Translator.translateChatBlock(input, 320, c -> StyledText.of(c).length() * 6));
		int marker = after.plain().indexOf("✆ ");
		boolean ok = marker >= 0 && !before.plain().equals(after.plain());
		for (int i = marker; ok && i < after.length(); i++) {
			ok &= (i < marker + 2 ? phone : body).equals(after.styleAt(i));
		}
		require("复用面对面对白时电话标记与正文事件不互串", ok);
		require("电话包装不改原始组件", before.equals(StyledText.of(input)));
	}

	private static void colours() {
		// Wiki-derived strings here are styling fixtures, not byte-exact server captures or ingame verification.
		colour("Queen Nyx 展示按钮", "§b§l[CLICK TO SHOW]", "§b§l[点击展示]");
		colour("Rulenor 付款按钮", "§6[PAY 10,000 Coins]", "§6[支付 10,000 硬币]");
		colour("Bonzo 碎片不染成金色", "§6You used Syphon on §5Bonzo Shard§6!", "§6你吸收了 §5Bonzo 碎片§6!");
		colour("守卫双选项", "§eSelect an option: §c§l[I'm here to cause trouble, of course]§a§l  [I have a letter of recommendation]",
			"§e请选择: §c§l[我当然是来找麻烦的]§a§l  [我有一封推荐信]");
		colour("Aranya 来电", "§a✆ RING... RING...  §2§l[PICK UP]", "§a✆ 铃……铃……  §2§l[接听]");
		colour("好友提示正文不染成玩家名颜色", "§aYou are now friends with §b[MVP+] Example", "§a你与 §b[MVP+] Example§a 已成为好友");
		colour("好友三个按钮及分隔符", "§a§l[ACCEPT]§8 - §c§l[DENY]§8 - §7§l[BLOCK]", "§a§l[接受]§8 - §c§l[拒绝]§8 - §7§l[屏蔽]");
		colour("Romero 末尾按钮", "§fIf you lost the item, we can fix that! Although, you will have to cover my expenses, considering the sentimental value... §e§l[CLICK]",
			"§f东西弄丢了也能补! 不过看在它承载的心意上,成本得由你承担……§e§l[点击]");
	}

	private static void colour(String name, String en, String zh) {
		require(name + " 独立颜色期望", StyledText.of(Translator.translateLine(Component.literal(en), Surface.CHAT)).equals(StyledText.of(Component.literal(zh))));
	}

	private static void require(String name, boolean ok) {
		if (ok) passed++;
		else { failed++; System.err.println("[失败] " + name); }
	}
}
