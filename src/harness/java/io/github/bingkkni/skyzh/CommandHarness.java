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
		check("开关失败反馈", Feedback.line(SkyZHCommand.changeFailed("翻译", "disk full")),
			"§b[SkyZH] §c很抱歉，开关翻译功能时出现错误惹... 原因: disk full");
		check("清空成功反馈", Feedback.line(SkyZHCommand.clearSucceeded()),
			"§b[SkyZH] §a已清空所有捕捉到的未翻译文本!");

		check("关闭采集时的短别名菜单", SkyZHCommand.helpLines("skyzh", false), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyzh  §f列出帮助菜单",
			"§6/skyzh switch power/tip/capture [on/off]  §f切换总功能/显示原文提示/采集功能为开/关",
			"§e======================================"
		));

		check("打开采集时的长别名菜单", SkyZHCommand.helpLines("skyblockzh", true), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyblockzh  §f列出帮助菜单",
			"§6/skyblockzh switch power/tip/capture [on/off]  §f切换总功能/显示原文提示/采集功能为开/关",
			"§6/skyblockzh clear  §f清空捕捉到的文本",
			"§e======================================"
		));

		check("on 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), " on"), false);
		check("off 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), " off"), false);
		check("关闭采集时不显示 clear", contains(SkyZHCommand.helpLines("skyzh", false), " clear"), false);

		check("显式关闭提示", SkyZHCommand.parse("skyzh switch tip OFF").state(), "off");
		check("长别名启用提示", SkyZHCommand.parse("skyblockzh switch tip on").argument(), "tip");
		check("显式 off 不会反复切换", SkyZHCommand.switchValue(false, "off"), false);
		check("显式 on 不会反复切换", SkyZHCommand.switchValue(true, "on"), true);
		check("省略状态仍可切换", SkyZHCommand.switchValue(true, ""), false);
		check("多余参数不能被忽略", SkyZHCommand.parse("skyzh switch tip off extra").state(), "off extra");
		check("旧 compare 不再提供", SkyZHCommand.SWITCHES.contains("compare"), false);
		check("首次提示立即允许", OriginalTips.claimInterval(100L), true);
		check("五分钟内不重复提示", OriginalTips.claimInterval(300_000_000_099L), false);
		check("满五分钟可再次提示", OriginalTips.claimInterval(300_000_000_100L), true);
		check("提示绑定当前键名", OriginalTips.loreHint("R").getString(), "§b[SkyZH] §6按住 R 键显示原文");
		var disable = OriginalTips.chatHint("R").getSiblings().getFirst();
		check("禁用提示点击指令", disable.getStyle().getClickEvent(),
			new net.minecraft.network.chat.ClickEvent.RunCommand("/skyzh switch tip off"));
		check("只有禁用部分可点击", OriginalTips.chatHint("R").getStyle().getClickEvent(), null);
		check("禁用按钮为红色", disable.getStyle().getColor().getValue(), 0xFF5555);
		checkClickedCommand();
		System.out.println();
		System.out.printf("通过 %d / 失败 %d%n", passed, failed);

		if (failed > 0) {
			System.exit(1);
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
		var mixin = new io.github.bingkkni.skyzh.mixin.ClientPacketListenerCommandMixin() {};
		SkyZHConfig config = SkyZHConfig.get();
		boolean previous = config.originalTips;
		try {
			config.originalTips = true;
			var off = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyzh switch tip off", null, off);
			check("点击禁用确实关闭配置", config.originalTips, false);
			check("点击本地命令不发送服务器", off.isCancelled(), true);
			var on = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "skyblockzh switch tip on", null, on);
			check("点击长别名确实启用配置", config.originalTips, true);
			check("点击长别名不发送服务器", on.isCancelled(), true);
			var other = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("sendUnattendedCommand", true);
			method.invoke(mixin, "warp hub", null, other);
			check("点击其他指令不拦截", other.isCancelled(), false);
		} finally {
			config.originalTips = previous;
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
