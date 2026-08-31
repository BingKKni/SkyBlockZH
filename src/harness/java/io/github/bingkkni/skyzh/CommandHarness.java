package io.github.bingkkni.skyzh;

import java.util.List;

/** Checks the local command grammar and the exact player-facing menu without launching Minecraft. */
public final class CommandHarness {
	private static int passed;
	private static int failed;

	private CommandHarness() {
	}

	public static void main(String[] args) {
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
		check("对比开关反馈", Feedback.line(SkyZHCommand.changed("翻译对比", true)),
			"§b[SkyZH] §a翻译对比功能已打开!");
		check("采集开关反馈", Feedback.line(SkyZHCommand.changed("采集未翻译文本", false)),
			"§b[SkyZH] §c采集未翻译文本功能已关闭!");
		check("开关失败反馈", Feedback.line(SkyZHCommand.changeFailed("翻译", "disk full")),
			"§b[SkyZH] §c很抱歉，开关翻译功能时出现错误惹... 原因: disk full");
		check("清空成功反馈", Feedback.line(SkyZHCommand.clearSucceeded()),
			"§b[SkyZH] §a已清空所有捕捉到的未翻译文本!");

		check("关闭采集时的短别名菜单", SkyZHCommand.helpLines("skyzh", false), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyzh  §f列出帮助菜单",
			"§6/skyzh switch power/compare/capture  §f切换总功能/翻译对比功能/采集功能为开/关",
			"§e======================================"
		));

		check("打开采集时的长别名菜单", SkyZHCommand.helpLines("skyblockzh", true), List.of(
			"§e=============== §b[SkyZH] §e===============",
			"§6/skyblockzh  §f列出帮助菜单",
			"§6/skyblockzh switch power/compare/capture  §f切换总功能/翻译对比功能/采集功能为开/关",
			"§6/skyblockzh clear  §f清空捕捉到的文本",
			"§e======================================"
		));

		check("on 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), " on"), false);
		check("off 不出现在帮助里", contains(SkyZHCommand.helpLines("skyzh", true), " off"), false);
		check("关闭采集时不显示 clear", contains(SkyZHCommand.helpLines("skyzh", false), " clear"), false);

		System.out.println();
		System.out.printf("通过 %d / 失败 %d%n", passed, failed);

		if (failed > 0) {
			System.exit(1);
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
