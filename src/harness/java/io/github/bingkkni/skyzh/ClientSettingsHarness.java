package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.bingkkni.skyzh.capture.CaptureContext;
import io.github.bingkkni.skyzh.hook.NameTag;
import io.github.bingkkni.skyzh.hook.SidebarText;
import io.github.bingkkni.skyzh.text.ContainerTitle;
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
		keys();
		servers();

		System.out.printf("通过 %d / 失败 %d%n", passed, failed);
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static void config() {
		SkyZHConfig defaults = SkyZHConfig.fromJson(new JsonObject());
		check("采集提示默认开", defaults.captureNotifications, true);
		check("启动清空默认关", defaults.autoClearCapture, false);
		check("采集总开关仍默认关", defaults.captureUntranslated, false);

		SkyZHConfig old = SkyZHConfig.fromJson(JsonParser.parseString("""
			{"enabled":false,"showOriginal":false,"captureUntranslated":true,"captureDirectory":"my-captures"}
			""").getAsJsonObject());
		check("旧配置保留总开关", old.enabled, false);
		check("旧配置保留对比开关", old.showOriginal, false);
		check("旧配置保留采集开关", old.captureUntranslated, true);
		check("旧配置保留目录", old.captureDirectory, "my-captures");
		check("旧配置缺少提示键时默认开", old.captureNotifications, true);
		check("旧配置缺少清空键时不删除数据", old.autoClearCapture, false);

		old.captureNotifications = false;
		old.autoClearCapture = true;
		SkyZHConfig restored = SkyZHConfig.fromJson(old.toJson());
		check("提示关闭可保存再读取", restored.captureNotifications, false);
		check("自动清空可保存再读取", restored.autoClearCapture, true);
		check("保存不修改采集目录", restored.captureDirectory, "my-captures");
		check("文件含提示说明", old.toJson().getAsJsonObject("_说明").has("captureNotifications"), true);
		check("文件含自动清空说明", old.toJson().getAsJsonObject("_说明").has("autoClearCapture"), true);
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

		binding.setKey(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_R));
		KeyMapping.resetMapping();
		check("原版改键后保存新键", binding.saveString(), "key.keyboard.r");
		check("改键后 X 不再匹配", binding.matches(new KeyEvent(InputConstants.KEY_X, 0, 0)), false);
		check("改键后 R 匹配", binding.matches(new KeyEvent(InputConstants.KEY_R, 0, 0)), true);
		check("原版 options 字符串可读回", InputConstants.getKey(binding.saveString()).getValue(), InputConstants.KEY_R);

		binding.setKey(InputConstants.Type.MOUSE.getOrCreate(3));
		check("可以绑定鼠标侧键", binding.saveString(), "key.mouse.4");
		binding.setKey(InputConstants.Type.SCANCODE.getOrCreate(123));
		check("可以绑定无符号键的扫描码", binding.matches(new KeyEvent(-1, 123, 0)), true);

		binding.setKey(InputConstants.UNKNOWN);
		KeyMapping.resetMapping();
		check("原版 NONE 是未绑定", binding.isUnbound(), true);
		check("NONE 不会退回 X", binding.matches(new KeyEvent(InputConstants.KEY_X, 0, 0)), false);
		check("NONE 可持久化", InputConstants.getKey(binding.saveString()), InputConstants.UNKNOWN);

		binding.setKey(binding.getDefaultKey());
		KeyMapping.resetMapping();
		check("原版重置恢复 X", binding.isDefault(), true);
		KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_X), true);
		check("键位在原版事件表内", binding.isDown(), true);
		check("同键的其他映射不被覆盖", existing.isDown(), true);
		KeyMapping.releaseAll();
	}

	private static void servers() throws Exception {
		for (String address : List.of("hypixel.net", "mc.hypixel.net", "alpha.hypixel.net", " MC.HYPIXEL.NET:25565 ",
			"mc.hypixel.net.", "mc.hypixel.net.:25565")) {
			check("允许 Hypixel 地址 " + address, HypixelServer.matchesAddress(address, "hypixel.net"), true);
		}
		for (String address : List.of("", "localhost", "127.0.0.1:25565", "[::1]:25565", "nothypixel.net",
			"hypixel.net.example.org", "mc.hypixel.net.example.org", "hypixel.net:abc", "hypixel.net:65536")) {
			check("拒绝非 Hypixel 地址 " + address, HypixelServer.matchesAddress(address, "hypixel.net"), false);
		}
		check("空地址拒绝", HypixelServer.matchesAddress(null, "hypixel.net"), false);
		check("主界面/无客户端连接拒绝", HypixelServer.isConnected(), false);
		check("没有连接时禁止翻译", HypixelServer.canTranslate(), false);

		Component title = Component.literal("      Select Process");
		ContainerTitle.Rendered rendered = ContainerTitle.of(null, title, 150);
		check("非 Hypixel 标题原对象返回", rendered.text() == title, true);
		check("非 Hypixel 不重算标题居中", rendered.centered(), false);
		List<Component> lore = List.of(Component.literal("Tusk Fossil"), Component.literal("Health: +100"));
		check("非 Hypixel 物品与 Lore 不进入缓存/折行", TooltipTranslator.translate(null, lore) == lore, true);
		Component tag = Component.literal("CLICK");
		check("非 Hypixel 浮空字原样返回", NameTag.translate(tag, false) == tag, true);
		check("非 Hypixel 玩家名原样返回", NameTag.translate(tag, true) == tag, true);
		check("非 Hypixel 侧边栏不翻译", SidebarText.row(null, Component.literal("SKYBLOCK")).getString(), "SKYBLOCK");

		// A stale SKYBLOCK title and an empty/custom captureServer must not bypass the live boundary.
		var skyBlock = CaptureContext.class.getDeclaredField("onSkyBlock");
		skyBlock.setAccessible(true);
		SkyZHConfig config = SkyZHConfig.get();
		boolean previousCapture = config.captureUntranslated;
		String previousServer = config.captureServer;
		try {
			skyBlock.setBoolean(null, true);
			config.captureUntranslated = true;
			config.captureServer = "";
			check("旧采集状态 + 空地址开关仍不能绕过 Hypixel", CaptureContext.active(), false);
			config.captureServer = "example.org";
			check("自定义其他服务器仍不能采集", CaptureContext.active(), false);
		} finally {
			config.captureUntranslated = previousCapture;
			config.captureServer = previousServer;
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
