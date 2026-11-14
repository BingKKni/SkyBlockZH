package io.github.bingkkni.skyzh;

import java.util.List;

/** Checks the local command grammar and the exact player-facing menu without launching Minecraft. */
public final class CommandHarness {
	private static int passed;
	private static int failed;

	private CommandHarness() {
	}

	public static void main(String[] args) throws Exception {
		SkyZHCommand.Parsed shortAlias = SkyZHCommand.parse("  SkYzH   switch   POWER  ");
		check("命令名不分大小写", shortAlias == null ? null : shortAlias.alias(), "skyzh");
		check("子命令不分大小写", shortAlias == null ? null : shortAlias.sub(), "switch");
		check("参数不分大小写", shortAlias == null ? null : shortAlias.argument(), "power");

		SkyZHCommand.Parsed longAlias = SkyZHCommand.parse("skyblockzh off");
		check("长别名可解析", longAlias == null ? null : longAlias.alias(), "skyblockzh");
		check("隐藏的 off 可解析", longAlias == null ? null : longAlias.sub(), "off");
		SkyZHCommand.Parsed updateCheck = SkyZHCommand.parse("skyzh updatecheck ON");
		check("更新检查子命令可解析", updateCheck == null ? null : updateCheck.sub(), "updatecheck");
		check("更新检查状态不分大小写", updateCheck == null ? null : updateCheck.argument(), "on");
		check("翻译重载已提供补全", SkyZHCommand.PLAIN.contains("reload"), true);
		check("更新检查补全包含两类更新和开关", SkyZHCommand.UPDATE_CHECK_ARGUMENTS, List.of("mod", "translate", "on", "off"));
		check("仅保留两个正式别名", SkyZHCommand.ALIASES, List.of("skyzh", "skyblockzh"));
		check("不注册拼写错误的长别名", SkyZHCommand.parse("skyblozkzh"), null);
		check("不注册截断的拼写错误别名", SkyZHCommand.parse("skyblozk"), null);
		for (String alias : SkyZHCommand.ALIASES) {
			for (String argument : List.of("", "mod", "translate", "on", "off")) {
				check(alias + " updatecheck " + argument + " 可解析",
					SkyZHCommand.parse(alias + " updatecheck " + argument.toUpperCase(java.util.Locale.ROOT)).argument(), argument);
			}
			check(alias + " updatecheck 多余参数保留供拒绝", SkyZHCommand.parse(alias + " updatecheck mod extra").state(), "extra");
			check(alias + " reload 可解析", SkyZHCommand.parse(alias + " reload").sub(), "reload");
			check(alias + " reload 多余参数保留供拒绝", SkyZHCommand.parse(alias + " reload extra").argument(), "extra");
		}
		check("空输入不归本 Mod", SkyZHCommand.parse("   "), null);
		check("null 输入不崩溃", SkyZHCommand.parse(null), null);
		check("别的命令不拦截", SkyZHCommand.parse("warp hub"), null);

		check("总开关打开反馈", Feedback.line(SkyZHCommand.changed("翻译", true)),
			"§b[SkyZH] §a翻译功能已打开!");
		check("总开关关闭反馈", Feedback.line(SkyZHCommand.changed("翻译", false)),
			"§b[SkyZH] §c翻译功能已关闭!");
		check("提示开关反馈", Feedback.line(SkyZHCommand.changed("显示原文提示", true)),
			"§b[SkyZH] §a显示原文提示功能已打开!");
		check("采集开关反馈", Feedback.line(SkyZHCommand.changed("采集未翻译文本", false)),
			"§b[SkyZH] §c采集未翻译文本功能已关闭!");
		check("更新开关明确包含自动下载", Feedback.line(SkyZHCommand.changed("自动更新", true)),
			"§b[SkyZH] §a自动更新功能已打开!");
		check("开关失败反馈", Feedback.line(SkyZHCommand.changeFailed("翻译", "disk full")),
			"§b[SkyZH] §c很抱歉，开关翻译功能时出现错误惹... 原因: disk full");
		check("清空成功反馈", Feedback.line(SkyZHCommand.clearSucceeded()),
			"§b[SkyZH] §a已清空所有捕捉到的未翻译文本!");

		check("重载失败新文案", SkyZHCommand.reloadFailed(), "§c翻译重载失败。详情已写入游戏日志。");
		check("关闭采集时的短别名菜单", SkyZHCommand.helpLines("skyzh", false), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyzh  §f打开设置",
			"§6/skyzh switch power/tip/capture [on/off]  §f切换总功能/显示原文提示/采集功能为开/关",
			"§6/skyzh updatecheck [mod/translate] §f检查更新",
			"§6/skyzh updatecheck on/off  §f开启/关闭自动更新",
			"§6/skyzh reload  §f重新加载翻译包",
			"§e======================================"
		));

		check("打开采集时的长别名菜单", SkyZHCommand.helpLines("skyblockzh", true), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyblockzh  §f打开设置",
			"§6/skyblockzh switch power/tip/capture [on/off]  §f切换总功能/显示原文提示/采集功能为开/关",
			"§6/skyblockzh updatecheck [mod/translate] §f检查更新",
			"§6/skyblockzh updatecheck on/off  §f开启/关闭自动更新",
			"§6/skyblockzh reload  §f重新加载翻译包",
			"§6/skyblockzh clear  §f清空捕捉到的文本",
			"§e======================================"
		));

		check("隐藏总开关 on 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), "/skyzh on"), false);
		check("隐藏总开关 off 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), "/skyzh off"), false);
		check("关闭采集时不显示 clear", contains(SkyZHCommand.helpLines("skyzh", false), " clear"), false);

		check("显式关闭提示", SkyZHCommand.parse("skyzh switch tip OFF").state(), "off");
		check("长别名启用提示", SkyZHCommand.parse("skyblockzh switch tip on").argument(), "tip");
		check("显式 off 不会反复切换", SkyZHCommand.switchValue(false, "off"), false);
		check("显式 on 不会反复切换", SkyZHCommand.switchValue(true, "on"), true);
		check("省略状态仍可切换", SkyZHCommand.switchValue(true, ""), false);
		check("多余参数不能被忽略", SkyZHCommand.parse("skyzh switch tip off extra").state(), "off extra");
		check("更新检查开关已提供", SkyZHCommand.SWITCHES.contains("updatecheck"), true);
		check("旧 compare 不再提供", SkyZHCommand.SWITCHES.contains("compare"), false);
		checkProxyCommands();
		checkQueuedSettingsScreen();
		check("首次提示立即允许", OriginalTips.claimInterval(100L), true);
		check("五分钟内不重复提示", OriginalTips.claimInterval(300_000_000_099L), false);
		check("满五分钟可再次提示", OriginalTips.claimInterval(300_000_000_100L), true);
		check("提示绑定当前键名", OriginalTips.loreHint("R", false).getString(), "§b[SkyZH] §6按下 R 键显示原文");
		check("原文模式提示恢复翻译", OriginalTips.loreHint("鼠标按键 4", true).getString(), "§b[SkyZH] §6按下 鼠标按键 4 键显示翻译");
		var disable = OriginalTips.chatHint("R").getSiblings().getLast();
		check("禁用提示点击指令", disable.getStyle().getClickEvent(),
			new net.minecraft.network.chat.ClickEvent.RunCommand("/skyzh switch tip off"));
		check("只有禁用部分可点击", OriginalTips.chatHint("R").getStyle().getClickEvent(), null);
		check("禁用按钮为红色", disable.getStyle().getColor().getValue(), 0xFF5555);
		checkHintScope();
		checkClickedCommand();
		System.out.println();
		System.out.printf("通过 %d / 失败 %d%n", passed, failed);

		if (failed > 0) {
			System.exit(1);
		}
	}

	private static void checkProxyCommands() {
		var config = SkyZHConfig.get();
		var previous = config.updateProxy;
		try {
			check("代理不会出现在主帮助", contains(SkyZHCommand.helpLines("skyzh", true), " proxy"), false);
			check("未配置代理状态", SkyZHCommand.proxyStatus(io.github.bingkkni.skyzh.update.UpdateProxy.NONE),
				"§e当前尚未设置代理。设置代理地址后，SkyZH 在检查更新时将优先使用代理网络。");
			for (String alias : SkyZHCommand.ALIASES) {
				SkyZHCommand.run(alias + " proxy clear");
				SkyZHCommand.run(alias + " proxy auth ACCOUNT MixedCaseAccount");
				SkyZHCommand.run(alias + " proxy auth password SecretAbC!123");
				check("代理账号保留大小写 " + alias, config.updateProxy.account(), "MixedCaseAccount");
				check("代理密码保留大小写 " + alias, config.updateProxy.password(), "SecretAbC!123");
				for (String protocol : List.of("http", "https", "socks5")) {
					SkyZHCommand.run(alias + " proxy url " + protocol + " 127.0.0.1:8888");
					check("代理地址正确 " + alias + protocol, config.updateProxy.url(), protocol + "://127.0.0.1:8888");
					check("验证状态不泄漏账号密码", SkyZHCommand.proxyStatus(config.updateProxy),
						"§a当前已设置代理地址: §e" + protocol + "://127.0.0.1:8888§a，§e包含验证§a。");
				}
				var valid = config.updateProxy;
				for (String invalid : List.of("url", "url http", "url other 127.0.0.1:80", "url http 34283242", "url http http://127.0.0.1:80",
					"url http host:0", "url http host:65536", "url http user:pass@host:80", "url http host:80/path", "url http host:80?x=1",
					"url http host:80#fragment", "url http host:80 extra", "auth", "auth account", "auth other SECRET", "clear extra")) {
					SkyZHCommand.run(alias + " proxy " + invalid);
					check("错误代理参数不修改配置 " + invalid, config.updateProxy, valid);
				}
				SkyZHCommand.run(alias + " proxy clear");
				check("清除地址与全部验证信息", config.updateProxy, io.github.bingkkni.skyzh.update.UpdateProxy.NONE);
			}
			SkyZHCommand.run("skyzh proxy url http Proxy.Example.org:8080");
			check("域名代理可配置", config.updateProxy.url(), "http://Proxy.Example.org:8080");
			check("无验证状态", SkyZHCommand.proxyStatus(config.updateProxy),
				"§a当前已设置代理地址: §ehttp://Proxy.Example.org:8080§a，§e无验证§a。");
			SkyZHCommand.run("skyzh proxy url socks5 [::1]:1080");
			check("IPv6 代理可配置", config.updateProxy.url(), "socks5://[::1]:1080");
		} finally { config.updateProxy = previous; }
	}

	private static void checkHintScope() {
		for (String key : List.of("X", "R", "鼠标按键 4")) {
			var hint = OriginalTips.chatHint(key);
			for (var component : List.of(hint, io.github.bingkkni.skyzh.text.Translator.translateChatBlock(
				hint, 320, c -> io.github.bingkkni.skyzh.text.StyledText.of(c).length() * 6))) {
				var text = io.github.bingkkni.skyzh.text.StyledText.of(component);
				int button = text.plain().indexOf("[禁用提示]");
				boolean valid = button >= 0 && text.plain().endsWith("[禁用提示]");
				for (int i = 0; i < text.length(); i++) {
					var style = text.styleAt(i);
					if (i < button) {
						valid &= style.getClickEvent() == null && style.getHoverEvent() == null;
					} else {
						valid &= new net.minecraft.network.chat.ClickEvent.RunCommand("/skyzh switch tip off").equals(style.getClickEvent());
						valid &= style.getColor() != null && style.getColor().getValue() == 0xFF5555;
						valid &= style.getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText hover
							&& "§e点击永久禁用这个提示!".equals(TranslationHarness.legacy(hover.value()));
					}
				}
				check("提示整句仅红色按钮有点击和黄色悬浮说明 " + key, valid, true);
			}
		}
	}

	private static void checkQueuedSettingsScreen() throws Exception {
		Class<?> screen = io.github.bingkkni.skyzh.gui.SkyZHConfigScreen.class;
		var clear = screen.getDeclaredMethod("clearCommandScreenRequest");
		var queued = screen.getDeclaredMethod("commandScreenRequested");
		clear.setAccessible(true);
		queued.setAccessible(true);

		try {
			clear.invoke(null);
			SkyZHCommand.run("skyzh");
			check("短别名在下一 tick 打开设置", queued.invoke(null), true);
			clear.invoke(null);
			SkyZHCommand.run("skyblockzh");
			check("长别名在下一 tick 打开设置", queued.invoke(null), true);
		} finally {
			clear.invoke(null);
		}
	}

	private static void checkClickedCommand() throws Exception {
		var file = SkyZHConfig.class.getDeclaredField("FILE");
		file.setAccessible(true);
		if (file.get(null) != null) {
			throw new IllegalStateException("Command harness must not write a real config");
		}
		var type = io.github.bingkkni.skyzh.mixin.ClientPacketListenerCommandMixin.class;
		var method = type.getDeclaredMethod("skyzh$runOwnClickedCommand", String.class,
			net.minecraft.client.gui.screens.Screen.class,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
		method.setAccessible(true);
		var typed = type.getDeclaredMethod("skyzh$runOwnCommand", String.class,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
		typed.setAccessible(true);
		var mixin = new io.github.bingkkni.skyzh.mixin.ClientPacketListenerCommandMixin() {};
		SkyZHConfig config = SkyZHConfig.get();
		boolean previous = config.originalTips;
		boolean previousUpdateCheck = config.updateCheck;
		boolean previousEnabled = config.enabled;
		var ready = io.github.bingkkni.skyzh.text.Translator.class.getDeclaredField("ready");
		var reloading = io.github.bingkkni.skyzh.text.Translator.class.getDeclaredField("RELOADING");
		ready.setAccessible(true);
		reloading.setAccessible(true);
		var loading = (java.util.concurrent.atomic.AtomicBoolean) reloading.get(null);
		boolean previousReady = ready.getBoolean(null), previousLoading = loading.get();
		try {
			config.originalTips = true;
			config.updateCheck = true;
			config.enabled = true;
			var off = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyzh switch tip off", null, off);
			check("点击禁用确实关闭配置", config.originalTips, false);
			check("点击本地命令不发送服务器", off.isCancelled(), true);
			var on = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyblockzh switch tip on", null, on);
			check("点击长别名确实启用配置", config.originalTips, true);
			check("点击长别名不发送服务器", on.isCancelled(), true);
			var updateOff = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyzh updatecheck off", null, updateOff);
			check("点击更新检查子命令确实关闭配置", config.updateCheck, false);
			check("点击更新检查子命令不发送服务器", updateOff.isCancelled(), true);
			var invalidOff = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyzh off unexpected", null, invalidOff);
			check("带多余参数的关闭命令不修改配置", config.enabled, true);
			check("无效本地命令仍不发送服务器", invalidOff.isCancelled(), true);
			// Hold the initial-load barrier so routing checks never access the network or real files.
			// UpdateInteractionHarness exercises admission with injected request/feedback callbacks.
			ready.setBoolean(null, false);
			loading.set(true);
			for (String alias : SkyZHCommand.ALIASES) {
				SkyZHCommand.run(alias + " updatecheck on");
				check("显式 on 仍开启自动更新 " + alias, config.updateCheck, true);
				SkyZHCommand.run(alias + " updatecheck off");
				check("显式 off 仍关闭自动更新 " + alias, config.updateCheck, false);
				for (String argument : List.of("", "mod", "translate", "mod extra", "translate extra", "invalid")) {
					String command = (alias + " updatecheck " + argument).trim();
					var clicked = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
					method.invoke(mixin, command, null, clicked);
					check("主动检查和无效参数均不切换自动更新 " + command, config.updateCheck, false);
					check("点击主动检查始终留在客户端 " + command, clicked.isCancelled(), true);
					var sent = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendCommand", true);
					typed.invoke(mixin, command, sent);
					check("输入主动检查始终留在客户端 " + command, sent.isCancelled(), true);
				}
				config.updateCheck = true;
				SkyZHCommand.run(alias + " updatecheck");
				check("省略参数不再关闭自动更新 " + alias, config.updateCheck, true);
				SkyZHCommand.run(alias + " updatecheck off extra");
				check("带多余参数不关闭自动更新 " + alias, config.updateCheck, true);
				var forged = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
				method.invoke(mixin, alias + " updateaction forged", null, forged);
				check("更新按钮指令始终留在客户端 " + alias, forged.isCancelled(), true);
				var extra = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
				method.invoke(mixin, alias + " updateaction forged extra", null, extra);
				check("无效按钮参数也不发送服务器 " + alias, extra.isCancelled(), true);
			}
			var other = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "warp hub", null, other);
			check("点击其他指令不拦截", other.isCancelled(), false);
		} finally {
			config.originalTips = previous;
			config.updateCheck = previousUpdateCheck;
			config.enabled = previousEnabled;
			ready.setBoolean(null, previousReady);
			loading.set(previousLoading);
		}
	}

	private static boolean contains(List<String> lines, String part) {
		return lines.stream().anyMatch(line -> line.contains(part));
	}

	private static void check(String name, Object actual, Object expected) {
		if (java.util.Objects.equals(actual, expected)) {
			passed++;
			System.out.println("  ✓ " + name);
			return;
		}

		failed++;
		System.out.printf("  ✗ %s%n      期望: %s%n      实际: %s%n", name, expected, actual);
	}
}
