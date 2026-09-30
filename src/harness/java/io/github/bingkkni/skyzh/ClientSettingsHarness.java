package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.bingkkni.skyzh.capture.CaptureContext;
import io.github.bingkkni.skyzh.hook.NameTag;
import io.github.bingkkni.skyzh.hook.SidebarText;
import io.github.bingkkni.skyzh.text.ContainerTitle;
import io.github.bingkkni.skyzh.text.ChatDisplayCache;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import io.github.bingkkni.skyzh.platform.InputHarness;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Config migration, vanilla key registration and the fail-closed client boundary, without a window. */
public final class ClientSettingsHarness {
	private static int passed;
	private static int failed;

	private ClientSettingsHarness() {
	}

	public static void main(String[] args) throws Exception {
		config();
		updates();
		keys();
		originalToggle();
		chatCache();
		servers();

		System.out.printf("通过 %d / 失败 %d%n", passed, failed);
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static void config() {
		SkyZHConfig defaults = SkyZHConfig.fromJson(new JsonObject());
		check("更新检查默认开", defaults.updateCheck, true);
		check("帮助改进翻译默认开", defaults.helpImproveTranslation, true);
		check("首次引导默认未显示", defaults.welcomeShown, false);
		check("采集提示默认开", defaults.captureNotifications, true);
		check("启动清空默认关", defaults.autoClearCapture, false);
		check("采集总开关仍默认关", defaults.captureUntranslated, false);

		SkyZHConfig old = SkyZHConfig.fromJson(JsonParser.parseString("""
			{"enabled":false,"showOriginal":false,"captureUntranslated":true,"captureDirectory":"my-captures","captureServer":"example.org"}
			""").getAsJsonObject());
		check("旧配置保留总开关", old.enabled, false);
		check("旧配置缺少更新检查时默认开", old.updateCheck, true);
		check("旧配置缺少帮助改进翻译时默认开", old.helpImproveTranslation, true);
		check("旧配置缺少引导状态时显示引导", old.welcomeShown, false);
		check("旧对比开关不影响新提示默认值", old.originalTips, true);
		check("保存移除旧对比字段", old.toJson().has("showOriginal"), false);
		check("保存移除旧 IP 限制字段", old.toJson().has("captureServer"), false);
		old.originalTips = false;
		check("原文提示关闭可保存再读取", SkyZHConfig.fromJson(old.toJson()).originalTips, false);
		check("旧配置保留采集开关", old.captureUntranslated, true);
		check("旧配置保留目录", old.captureDirectory, "my-captures");
		check("旧配置缺少提示键时默认开", old.captureNotifications, true);
		check("旧配置缺少清空键时不删除数据", old.autoClearCapture, false);

		old.captureNotifications = false;
		old.autoClearCapture = true;
		old.updateCheck = false;
		old.helpImproveTranslation = false;
		old.welcomeShown = true;
		SkyZHConfig restored = SkyZHConfig.fromJson(old.toJson());
		check("提示关闭可保存再读取", restored.captureNotifications, false);
		check("自动清空可保存再读取", restored.autoClearCapture, true);
		check("更新检查关闭可保存再读取", restored.updateCheck, false);
		check("帮助改进翻译状态可保存再读取", restored.helpImproveTranslation, false);
		check("首次引导状态可保存再读取", restored.welcomeShown, true);
		check("保存不修改采集目录", restored.captureDirectory, "my-captures");
		check("文件含提示说明", old.toJson().getAsJsonObject("_说明").has("captureNotifications"), true);
		check("文件含自动清空说明", old.toJson().getAsJsonObject("_说明").has("autoClearCapture"), true);
		check("文件含更新检查说明", old.toJson().getAsJsonObject("_说明").has("updateCheck"), true);
		check("文件含首次引导说明", old.toJson().getAsJsonObject("_说明").has("welcomeShown"), true);
	}

	private static void updates() {
		check("更高发布版本会提示", UpdateChecker.isNewer("0.5.1", "0.5"), true);
		check("等价的补零版本不重复提示", UpdateChecker.isNewer("0.5.0", "0.5"), false);
		check("发布版本高于同号预发布", UpdateChecker.isNewer("0.5", "0.5-beta.1"), true);
		check("带 v 前缀的发布标签可识别", UpdateChecker.isNewer("v0.6", "0.5+mc26.1"), true);
		check("无效发布标签不提示", UpdateChecker.isNewer("latest", "0.5"), false);
		for (String target : List.of("26.1", "26.2", "26.3")) {
			check("0.5 可发现正式 0.6 - " + target, UpdateChecker.isNewer("0.6", "0.5+mc" + target), true);
			check("0.6 不提示同版本 - " + target, UpdateChecker.isNewer("v0.6", "0.6+mc" + target), false);
			check("0.6 不提示旧发布 - " + target, UpdateChecker.isNewer("0.5", "0.6+mc" + target), false);
		}
		var uri = java.net.URI.create("https://github.com/BingKKni/SkyBlockZH/releases/tag/0.6");
		UpdateChecker.announce("0.5+mc26.2", "0.6", uri);
		check("主界面不丢弃待显示更新", UpdateChecker.takeNotice(true, false), null);
		check("进入世界后交付原版本信息", UpdateChecker.takeNotice(true, true), new UpdateChecker.Notice("0.5+mc26.2", "0.6", uri));
		check("断线重进不重复提示", UpdateChecker.takeNotice(true, true), null);
		UpdateChecker.announce("0.5", "0.6", uri);
		check("关闭设置撤销待发更新", UpdateChecker.takeNotice(false, true), null);
		check("重新开启不补发已取消提示", UpdateChecker.takeNotice(true, true), null);
	}

	private static void keys() {
		KeyMapping existing = new KeyMapping("key.skyzh.harnessExisting", InputConstants.KEY_X, KeyMapping.Category.MISC);
		KeyMapping[] original = {existing};
		KeyMapping[] registered = HoldOriginal.register(original);
		KeyMapping binding = registered[1];
		check("追加一个原版键位", registered.length, 2);
		check("其他原版/Mod 键位保留", registered[0] == existing, true);
		check("不修改原数组", original.length, 1);
		check("重复 load 不重复注册", HoldOriginal.register(registered) == registered, true);
		check("默认键位为 X", binding.saveString(), "key.keyboard.x");
		check("键位有可本地化名称", binding.getName(), "key.skyzh.holdOriginal");
		check("键位有独立分类", binding.getCategory().id().toString(), "skyzh:main");

		binding.setKey(InputConstants.getKey("key.keyboard.r"));
		KeyMapping.resetMapping();
		check("原版改键后保存新键", binding.saveString(), "key.keyboard.r");
		check("改键后 X 不再匹配", binding.matches(new KeyEvent(InputConstants.KEY_X, 0, 0)), false);
		check("改键后 R 匹配", binding.matches(new KeyEvent(InputConstants.KEY_R, 0, 0)), true);
		check("原版 options 字符串可读回", InputConstants.getKey(binding.saveString()).getValue(), InputConstants.KEY_R);

		binding.setKey(InputConstants.getKey("key.mouse.4"));
		check("可以绑定鼠标侧键", binding.saveString(), "key.mouse.4");
		check("鼠标侧键使用当前版本编号", InputConstants.getKey(binding.saveString()).getValue(), InputConstants.MOUSE_BUTTON_4);
		InputHarness.check(binding);

		binding.setKey(InputConstants.UNKNOWN);
		KeyMapping.resetMapping();
		check("原版 NONE 是未绑定", binding.isUnbound(), true);
		check("解绑后不提示 X", HoldOriginal.keyName(), null);
		check("NONE 不会退回 X", binding.matches(new KeyEvent(InputConstants.KEY_X, 0, 0)), false);
		check("NONE 可持久化", InputConstants.getKey(binding.saveString()), InputConstants.UNKNOWN);

		binding.setKey(binding.getDefaultKey());
		KeyMapping.resetMapping();
		check("原版重置恢复 X", binding.isDefault(), true);
		KeyMapping.set(InputConstants.getKey("key.keyboard.x"), true);
		check("键位在原版事件表内", binding.isDown(), true);
		check("同键的其他映射不被覆盖", existing.isDown(), true);
		KeyMapping.releaseAll();
	}

	private static void originalToggle() throws Exception {
		int generation = SkyZHConfig.generation();
		HoldOriginal.setActive(false);
		HoldOriginal.press(true, true, InputConstants.PRESS);
		check("按一次切换原文", HoldOriginal.active(), true);
		HoldOriginal.press(true, true, InputConstants.REPEAT);
		HoldOriginal.press(true, true, InputConstants.RELEASE);
		check("长按重复及松开不改变模式", HoldOriginal.active(), true);
		HoldOriginal.updateMouse(true, false, false);
		HoldOriginal.press(true, false, InputConstants.PRESS);
		check("打开聊天输入或失焦保留原文且输入不触发", HoldOriginal.active(), true);
		HoldOriginal.press(true, true, InputConstants.PRESS);
		check("再按恢复译文", HoldOriginal.active(), false);
		check("切换不清空译文缓存", SkyZHConfig.generation(), generation);
		HoldOriginal.updateMouse(true, true, true);
		HoldOriginal.updateMouse(true, true, true);
		check("鼠标长按只切换一次", HoldOriginal.active(), true);
		HoldOriginal.updateMouse(true, true, false);
		HoldOriginal.updateMouse(true, true, true);
		check("鼠标再次按下恢复", HoldOriginal.active(), false);
		HoldOriginal.updateMouse(true, false, false);
		HoldOriginal.updateMouse(true, false, true);
		HoldOriginal.updateMouse(true, true, true);
		check("在输入框按下鼠标后离开不误触", HoldOriginal.active(), false);

		check("失焦时鼠标状态未知不触发", HoldOriginal.updateMouse(true, false, null), false);
		check("焦点恢复时仍按住不制造新按下", HoldOriginal.updateMouse(true, true, true), false);
		check("继续按住仍不触发", HoldOriginal.updateMouse(true, true, true), false);
		check("焦点切换保留显示模式", HoldOriginal.active(), false);
		check("焦点恢复后实际松开不切换", HoldOriginal.updateMouse(true, true, false), false);
		check("实际松开后再次按下正常切换", HoldOriginal.updateMouse(true, true, true), true);
		check("恢复后能正常显示原文", HoldOriginal.active(), true);
		check("仅鼠标焦点丢失也不视作松开", HoldOriginal.updateMouse(true, true, null), false);
		check("鼠标回到窗口时持续按住不切换", HoldOriginal.updateMouse(true, true, true), false);
		check("鼠标焦点切换保留原文", HoldOriginal.active(), true);
		check("未知鼠标状态不妨碍离开空岛清除模式", HoldOriginal.updateMouse(false, false, null), false);
		check("失焦时总开关边界仍生效", HoldOriginal.active(), false);
		check("恢复后已松开可重新观察按下", HoldOriginal.updateMouse(true, true, false), false);
		check("恢复后新按下仍能切换", HoldOriginal.updateMouse(true, true, true), true);

		HoldOriginal.setActive(true);
		HoldOriginal.updateMouse(false, true, false);
		check("离开空岛、关闭总开关或解绑清除临时原文状态", HoldOriginal.active(), false);
		check("非空岛不触发声音消息", HoldOriginal.press(false, true, InputConstants.PRESS), false);
		check("非空岛不响应", HoldOriginal.active(), false);
		check("重复按键不触发声音消息", HoldOriginal.press(true, true, InputConstants.REPEAT), false);
		check("输入框不触发声音消息", HoldOriginal.press(true, false, InputConstants.PRESS), false);
		check("实际切换才触发一次声音消息", HoldOriginal.press(true, true, InputConstants.PRESS), true);
		check("自动恢复不播放提示", HoldOriginal.updateMouse(false, true, false), false);
		check("关闭提示全文", HoldOriginal.toggleMessage(true).getString(), "§b[SkyZH] §c已关闭翻译!");
		check("开启提示全文", HoldOriginal.toggleMessage(false).getString(), "§b[SkyZH] §a已开启翻译!");
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var sound = HoldOriginal.toggleSound(1, 2, 3);
		check("按键音效 ID", sound.getIdentifier().toString(), "minecraft:ui.button.click");
		check("按键音效分类为方块", sound.getSource(), net.minecraft.sounds.SoundSource.BLOCKS);
		// getVolume/getPitch also multiply resolved audio-asset samples, absent in this headless test.
		var volume = net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("volume");
		var pitch = net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("pitch");
		volume.setAccessible(true);
		pitch.setAccessible(true);
		check("按键音量参数", volume.getFloat(sound), 100.0F);
		check("按键音高参数", pitch.getFloat(sound), 2.0F);
	}

	private static void chatCache() {
		ChatDisplayCache cache = new ChatDisplayCache();
		Object font = new Object(), corpus = new Object();
		check("翻译开启触发历史刷新", cache.needsRefresh(true, true, corpus), true);
		check("相同模式不逐帧重排", cache.needsRefresh(true, true, corpus), false);
		check("关闭总开关或原文模式立即刷新历史", cache.needsRefresh(false, true, corpus), true);
		check("原文期间无关设置不重排", cache.needsRefresh(false, false, corpus), false);
		check("恢复翻译刷新历史", cache.needsRefresh(true, false, corpus), true);
		check("玩法名选项改变刷新历史", cache.needsRefresh(true, true, corpus), true);
		int[] splits = {0};
		java.util.function.Supplier<List<FormattedCharSequence>> split = () -> {
			splits[0]++;
			return List.of(FormattedCharSequence.forward("cached", Style.EMPTY));
		};
		GuiMessage message = new GuiMessage(1, Component.literal("[NPC] Trevor: Hi there!"), null, null, null);
		for (int i = 0; i < 100; i++) {
			cache.get(message, font, 320, true, true, corpus, split);
			cache.get(message, font, 320, false, true, corpus, split);
		}
		check("往返切换100次每种语言只折行一次", splits[0], 2);
		check("聊天历史仍是原文", message.content().getString(), "[NPC] Trevor: Hi there!");
		cache.get(message, font, 160, true, true, corpus, split);
		check("聊天宽度变化失效", splits[0], 3);
		cache.clear();
		cache.get(message, font, 160, true, true, corpus, split);
		check("资源重载或历史清空后不复用旧字形", splits[0], 4);
		cache.get(message, font, 160, true, false, corpus, split);
		check("玩法名开关改变重算译文", splits[0], 5);
		Object reloaded = new Object();
		cache.get(message, font, 160, true, false, reloaded, split);
		check("语料重载失效", splits[0], 6);
		message.content().getSiblings().add(Component.literal(" changed by another mod"));
		cache.get(message, font, 160, true, false, reloaded, split);
		check("其他Mod原地改聊天组件后不复用旧译文", splits[0], 7);
		for (int i = 0; i < 300; i++) {
			cache.get(new GuiMessage(i, Component.literal("line " + i), null, null, null),
				font, 160, true, false, reloaded, split);
		}
		cache.get(message, font, 160, true, false, reloaded, split);
		check("缓存容量有界并淘汰旧消息", splits[0], 308);
	}

	private static void servers() throws Exception {
		check("只认 Hypixel 官方 hello", HypixelServer.isHypixelHello("hypixel:hello"), true);
		for (String identifier : List.of("", "minecraft:brand", "hyevent:location", "other:hello", "hypixel:ping")) {
			check("其他载荷不能冒充 Hypixel " + identifier, HypixelServer.isHypixelHello(identifier), false);
		}

		for (String brand : List.of("Hypixel BungeeCord", "hypixel", " HYPIXEL Proxy ")) {
			check("接受 Hypixel 服务器品牌 " + brand, HypixelServer.isHypixelBrand(brand), true);
		}
		for (String brand : java.util.Arrays.asList(null, "", "vanilla", "fabric", "Paper", "BungeeCord", "Velocity")) {
			check("其他品牌不能冒充 Hypixel " + brand, HypixelServer.isHypixelBrand(brand), false);
		}

		check("接受 Hypixel SkyBlock 侧边栏 objective SBScoreboard", HypixelServer.isSkyBlockObjective("SBScoreboard"), true);
		for (String name : java.util.Arrays.asList(null, "", "SKYBLOCK", "§6§lSKYBLOCK", "sbscoreboard", "SBScoreboard ", "health", "sidebar")) {
			check("拒绝其他 objective 名 " + name, HypixelServer.isSkyBlockObjective(name), false);
		}
		check("空 objective 不是 SkyBlock", HypixelServer.isSkyBlockObjective((net.minecraft.world.scores.Objective) null), false);

		check("主界面没有 Hypixel 身份", HypixelServer.isHypixel(), false);
		check("主界面不在 SkyBlock", HypixelServer.isSkyBlock(), false);
		check("没有连接时禁止翻译", HypixelServer.canTranslate(), false);

		Component title = Component.literal("      Select Process");
		ContainerTitle.Rendered rendered = ContainerTitle.of(null, title, 150);
		check("未验证连接的标题原对象返回", rendered.text() == title, true);
		check("未验证连接不重算标题居中", rendered.centered(), false);
		List<Component> lore = List.of(Component.literal("Tusk Fossil"), Component.literal("Health: +100"));
		check("未验证连接的物品与 Lore 不进入缓存/折行", TooltipTranslator.translate(null, lore) == lore, true);
		Component tag = Component.literal("CLICK");
		check("未验证连接的浮空字原样返回", NameTag.translate(tag, true) == tag, true);
		check("非盔甲架名牌原样返回", NameTag.translate(tag, false) == tag, true);
		Component mob = Component.literal("§cGlacite Mutt §a100§c❤");
		check("未验证连接的活体怪物名也原样返回", NameTag.translate(mob, false, true) == mob, true);
		check("玩家形状不进入怪物通名路径", NameTag.translate(mob, false, false) == mob, true);
		check("未验证连接的侧边栏不翻译", SidebarText.row(null, Component.literal("SKYBLOCK")).getString(), "SKYBLOCK");

		// A stale SKYBLOCK capture flag must not bypass the live hello + sidebar boundary.
		var skyBlock = CaptureContext.class.getDeclaredField("onSkyBlock");
		skyBlock.setAccessible(true);
		SkyZHConfig config = SkyZHConfig.get();
		boolean previousCapture = config.captureUntranslated;
		try {
			skyBlock.setBoolean(null, true);
			config.captureUntranslated = true;
			check("旧采集状态不能绕过当前连接验证", CaptureContext.active(), false);
		} finally {
			config.captureUntranslated = previousCapture;
			CaptureContext.reset();
		}
	}

	private static void check(String name, Object actual, Object expected) {
		if (java.util.Objects.equals(actual, expected)) {
			passed++;
			System.out.println("  [通过] " + name);
		} else {
			failed++;
			System.out.printf("  [失败] %s — 期望 [%s] 实际 [%s]%n", name, expected, actual);
		}
	}
}
