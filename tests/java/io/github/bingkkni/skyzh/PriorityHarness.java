package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import io.github.bingkkni.skyzh.hook.PriorityText;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Source provenance, style choice, isolation and bounds; runtime gates are in ClientSettingsHarness. */
public final class PriorityHarness {
	private static int checked;
	private static Method put;
	private static Method resolve;
	private static Map<?, ?> sources;

	public static void main(String[] args) throws Exception {
		TranslationHarness.installIndex(TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0]))));
		put = PriorityText.class.getDeclaredMethod("put", Map.class, Component.class, Surface.class);
		put.setAccessible(true);
		resolve = PriorityText.class.getDeclaredMethod("resolveKnown", FormattedCharSequence.class,
			boolean.class, boolean.class, boolean.class);
		resolve.setAccessible(true);
		var field = PriorityText.class.getDeclaredField("HUD");
		field.setAccessible(true);
		sources = (Map<?, ?>) field.get(null);
		var packetsField = PriorityText.class.getDeclaredField("PACKETS");
		packetsField.setAccessible(true);
		Map<?, ?> packets = (Map<?, ?>) packetsField.get(null);
		var effective = PriorityText.class.getDeclaredMethod("effective", boolean.class);
		effective.setAccessible(true);
		var collecting = PriorityText.class.getDeclaredMethod("collecting", boolean.class);
		collecting.setAccessible(true);
		for (DisplayPriority priority : DisplayPriority.values()) {
			SkyZHConfig.get().displayPriority = priority;
			check("已验证服务器启用所选档位 " + priority, effective.invoke(null, true) == priority);
			check("未验证服务器关闭所选档位 " + priority, effective.invoke(null, false) == DisplayPriority.DEFAULT);
			SkyZHConfig.get().enabled = false;
			check("总开关优先于显示优先级 " + priority, effective.invoke(null, true) == DisplayPriority.DEFAULT);
			SkyZHConfig.get().enabled = true;
			HoldOriginal.setActive(true);
			check("原文热键优先于显示优先级 " + priority, effective.invoke(null, true) == DisplayPriority.DEFAULT);
			check("原文模式继续记录来源 " + priority,
				collecting.invoke(null, true).equals(priority != DisplayPriority.DEFAULT));
			HoldOriginal.setActive(false);
		}
		SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
		try {
			Component server = Component.literal("SKYBLOCK").withStyle(ChatFormatting.GOLD);
			put.invoke(null, sources, server, Surface.SCOREBOARD);
			Component modified = Component.literal("SKYBLOCK").withStyle(ChatFormatting.RED);
			Component high = result(modified, false);
			Component force = result(modified, true);
			check("服务器标题有译文", high != null);
			check("服务器标题补译", high.getString().equals("空岛生存"));
			var wrapped = net.minecraft.network.chat.FormattedText.of("SKYBLOCK");
			var wrappedResult = (FormattedCharSequence) resolve.invoke(null,
				net.minecraft.locale.Language.getInstance().getVisualOrder(wrapped), false, false, false);
			check("通用FormattedText保持同一译文", PriorityText.component(wrappedResult).getString().equals("空岛生存"));
			check("未接管时可保留实时显示颜色", StyledText.of(high).styleAt(0).getColor().getValue() == 0xFF5555);
			check("强制恢复服务器颜色", StyledText.of(force).styleAt(0).getColor().getValue() == 0xFFAA00);
			check("服务器组件保持英文", server.getString().equals("SKYBLOCK"));
			check("辅助 Mod 组件保持英文", modified.getString().equals("SKYBLOCK"));
			check("不猜测改写后的第三方文本", result(Component.literal("SkyHanni Custom SKYBLOCK"), false) == null);
			check("不翻译未观察到的语料文本", result(Component.literal("CLICK"), true) == null);
			put.invoke(null, packets, server, Surface.CHAT);
			check("HUD与数据包的同文跨面歧义不猜测", result(server, false) == null);
			packets.clear();
			put.invoke(null, sources, server, Surface.ITEM);
			check("不同渲染面同文歧义不猜测", result(server, false) == null);
			sources.clear();
			put.invoke(null, sources, Component.literal("SKYBLOCK\nCustom unknown"), Surface.SCOREBOARD);
			check("多行原文逐行登记", result(server, false) != null);
			for (int i = 0; i < 2200; i++) put.invoke(null, sources, Component.literal("line " + i), Surface.SCOREBOARD);
			check("来源记录有界", sources.size() == 2048);
			check("逐出后不沿用旧来源", result(server, true) == null);
			Component styled = Component.literal("A😀").withStyle(ChatFormatting.RED)
				.append(Component.literal("中文").withStyle(ChatFormatting.BLUE));
			check("字形还原保留 Unicode 和各段样式", StyledText.of(styled).equals(
				StyledText.of(PriorityText.component(styled.getVisualOrderText()))));
			check("超长未知文本不进入匹配", PriorityText.component(Component.literal("x".repeat(8193)).getVisualOrderText()) == null);
			check("默认档不修改最终文本", PriorityText.resolve(server.getVisualOrderText()) == null);
		} finally { sources.clear(); packets.clear(); }
		checkForceChatHistory();
		checkWidthReentry();
		checkSidebarCompatibility();
		checkCustomSidebarText();
		checkSidebarPrefixFilter();
		checkSidebarTakeover();
		System.out.println("显示优先级检查通过 " + checked + " 项");
	}

	/** Public API stand-ins: no dependency on, injection into, or mutation of a real helper mod. */
	public static final class ToggleProperty {
		public Boolean value = false;
		public Boolean get() { return value; }
	}
	public static final class SidebarConfig {
		public final ToggleProperty property = new ToggleProperty();
		public ToggleProperty getEnabled() { return property; }
	}
	public static final class SkyHanniSidebar {
		public static final SkyHanniSidebar INSTANCE = new SkyHanniSidebar();
		public final SidebarConfig config = new SidebarConfig();
		public SidebarConfig getConfig$at_hannibal2_skyhanni_26_2() { return config; }
	}
	public static final class CustomScoreboardConfig {
		public static final CustomScoreboardConfig INSTANCE = new CustomScoreboardConfig();
		public Boolean enabled = false;
		public Boolean getEnabled() { return enabled; }
	}
	public static class Feature { public boolean enabled; }
	public static final class NoammSidebar extends Feature {
		public static final NoammSidebar INSTANCE = new NoammSidebar();
	}

	private static void checkSidebarCompatibility() throws Exception {
		Class<?> compatibility = io.github.bingkkni.skyzh.hook.SidebarCompatibility.class;
		Method customScoreboard = compatibility.getDeclaredMethod("customScoreboard", Class.class);
		Method skyHanni = compatibility.getDeclaredMethod("skyHanni", Class.class);
		Method noamm = compatibility.getDeclaredMethod("noamm", Class.class);
		Method enabled = compatibility.getDeclaredMethod("enabled", java.util.function.BooleanSupplier.class);
		customScoreboard.setAccessible(true);
		skyHanni.setAccessible(true);
		noamm.setAccessible(true);
		enabled.setAccessible(true);
		var cs = (java.util.function.BooleanSupplier) customScoreboard.invoke(null, CustomScoreboardConfig.class);
		var sh = (java.util.function.BooleanSupplier) skyHanni.invoke(null, SkyHanniSidebar.class);
		var na = (java.util.function.BooleanSupplier) noamm.invoke(null, NoammSidebar.class);
		check("辅助计分板关闭时保留强制侧边栏", !cs.getAsBoolean() && !sh.getAsBoolean() && !na.getAsBoolean());
		CustomScoreboardConfig.INSTANCE.enabled = true;
		check("独立 CustomScoreboard 开启时让出侧边栏", cs.getAsBoolean());
		CustomScoreboardConfig.INSTANCE.enabled = false;
		check("CustomScoreboard 运行中关闭后恢复", !cs.getAsBoolean());
		CustomScoreboardConfig.INSTANCE.enabled = null;
		check("CustomScoreboard 未知状态不叠加", cs.getAsBoolean());
		SkyHanniSidebar.INSTANCE.config.property.value = true;
		check("SkyHanni 公共状态开启时让出侧边栏", sh.getAsBoolean());
		SkyHanniSidebar.INSTANCE.config.property.value = false;
		check("SkyHanni 运行中关闭后恢复", !sh.getAsBoolean());
		SkyHanniSidebar.INSTANCE.config.property.value = null;
		check("未知 SkyHanni 状态安全让出侧边栏", sh.getAsBoolean());
		NoammSidebar.INSTANCE.enabled = true;
		check("Noamm 公共继承字段开启时让出侧边栏", na.getAsBoolean());
		NoammSidebar.INSTANCE.enabled = false;
		check("Noamm 运行中关闭后恢复", !na.getAsBoolean());
		java.util.function.BooleanSupplier broken = () -> { throw new LinkageError("changed optional API"); };
		check("辅助 Mod 接口失效不崩溃也不叠加", (boolean) enabled.invoke(null, broken));
	}

	private static void checkCustomSidebarText() throws Exception {
		Method session = PriorityText.class.getDeclaredMethod("updateSession", Object.class, boolean.class, boolean.class);
		Method advance = PriorityText.class.getDeclaredMethod("advanceHud");
		Method collecting = PriorityText.class.getDeclaredMethod("collecting", boolean.class);
		Method display = PriorityText.class.getDeclaredMethod("displayEnabled", boolean.class);
		for (Method method : new Method[] {session, advance, collecting, display}) method.setAccessible(true);
		Object world = new Object();
		Component server = Component.literal("§fPurse: §61,234");
		Component drawn = Component.literal("  Purse: ").withStyle(style -> style.withColor(0x5599EE)
			.withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand("/bank")))
			.append(Component.literal("1,234  ").withStyle(ChatFormatting.GREEN));
		try {
			SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			session.invoke(null, world, true, true);
			check("默认档即使检测状态残留也不收集来源", !(boolean) collecting.invoke(null, true));
			check("默认档不启用增强入口", !PriorityText.active());
			for (DisplayPriority priority : new DisplayPriority[] {DisplayPriority.HIGH, DisplayPriority.FORCE}) {
				SkyZHConfig.get().displayPriority = priority;
				session.invoke(null, world, true, priority == DisplayPriority.HIGH);
				check("增强档收集来源 " + priority, (boolean) collecting.invoke(null, true));
				put.invoke(null, sources, server, Surface.SCOREBOARD);
				Component translated = customResult(drawn, priority);
				StyledText styled = StyledText.of(translated);
				if (priority == DisplayPriority.HIGH) {
					check("高档保留自定义缩进", translated.getString().equals("  硬币: 1,234  "));
					check("高档保留自定义配色", styled.styleAt(2).getColor().getValue() == 0x5599EE
						&& styled.styleAt(6).getColor().getValue() == 0x55FF55);
					check("高档保留自定义点击事件", styled.styleAt(2).getClickEvent().equals(drawn.getStyle().getClickEvent()));
				} else {
					check("强制档恢复服务器文本布局", translated.getString().equals("硬币: 1,234"));
					check("强制档恢复服务器颜色", styled.styleAt(0).getColor().getValue() == 0xFFFFFF
						&& styled.styleAt(4).getColor().getValue() == 0xFFAA00);
					check("强制档不带入第三方点击事件", styled.styleAt(0).getClickEvent() == null);
				}
				check("不修改服务器行或辅助 Mod 组件 " + priority,
					server.getString().equals("§fPurse: §61,234") && drawn.getString().equals("  Purse: 1,234  "));
				check("不翻译辅助 Mod 新增句子 " + priority, customResult(Component.literal("Bank: 1,234"), priority) == null);
				check("不猜测辅助 Mod 改写数值 " + priority, customResult(Component.literal("Purse: 1.2k"), priority) == null);
				put.invoke(null, sources, Component.literal("§7\ue067 §bDwarven M§qines"), Surface.SCOREBOARD);
				Component location = customResult(Component.literal("§a⏣ §2Dwarven Mines"), priority);
				check("图标规范化及隐形标记不阻止匹配 " + priority,
					io.github.bingkkni.skyzh.text.Glyphs.canonical(location.getString()).equals("⏣ 矮人矿山"));
				check("按档位选择图标颜色 " + priority, StyledText.of(location).styleAt(0).getColor().getValue()
					== (priority == DisplayPriority.HIGH ? 0x55FF55 : 0xAAAAAA));
				HoldOriginal.setActive(true);
				check("原文键即时关闭补译 " + priority, !(boolean) display.invoke(null, true));
				check("原文期间继续保留来源 " + priority, (boolean) collecting.invoke(null, true));
				HoldOriginal.setActive(false);
			}
			SkyZHConfig.get().displayPriority = DisplayPriority.HIGH;
			session.invoke(null, world, true, true);
			put.invoke(null, sources, server, Surface.SCOREBOARD);
			for (int i = 0; i < 5; i++) advance.invoke(null);
			put.invoke(null, sources, Component.literal("Purse: 1,235"), Surface.SCOREBOARD);
			check("250毫秒缓存仍显示旧值时不闪回英文", customResult(drawn, DisplayPriority.HIGH) != null);
			check("较新服务器数值同时可翻译", customResult(Component.literal("Purse: 1,235"), DisplayPriority.HIGH) != null);
			for (int i = 0; i < 16; i++) advance.invoke(null);
			check("过期服务器旧值不继续认可", customResult(drawn, DisplayPriority.HIGH) == null);
			check("未过期行不被旧值逐出影响", customResult(Component.literal("Purse: 1,235"), DisplayPriority.HIGH) != null);
			session.invoke(null, new Object(), true, true);
			check("换世界清空来源", sources.isEmpty());
			put.invoke(null, sources, server, Surface.SCOREBOARD);
			session.invoke(null, world, false, false);
			check("离开空岛清空来源且不继续收集", sources.isEmpty() && !(boolean) collecting.invoke(null, false));
			session.invoke(null, world, true, true);
			put.invoke(null, sources, server, Surface.SCOREBOARD);
			SkyZHConfig.get().enabled = false;
			session.invoke(null, world, true, false);
			check("关闭总开关清空兼容来源", sources.isEmpty() && !(boolean) display.invoke(null, true));
			SkyZHConfig.get().enabled = true;
			session.invoke(null, world, true, true);
			put.invoke(null, sources, server, Surface.SCOREBOARD);
			SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			session.invoke(null, world, true, false);
			check("回到默认档清空增强来源", sources.isEmpty() && !(boolean) collecting.invoke(null, true));
		} finally {
			SkyZHConfig.get().enabled = true;
			SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			HoldOriginal.setActive(false);
			session.invoke(null, null, false, false);
		}
	}

	private static void checkSidebarPrefixFilter() throws Exception {
		Method session = PriorityText.class.getDeclaredMethod("updateSession", Object.class, boolean.class, boolean.class);
		session.setAccessible(true);
		var probeField = PriorityText.class.getDeclaredField("PREFIX_PROBE");
		probeField.setAccessible(true);
		Object probe = probeField.get(null);
		Method matches = probe.getClass().getDeclaredMethod("matches", FormattedCharSequence.class);
		matches.setAccessible(true);
		try {
			session.invoke(null, new Object(), true, true);
			put.invoke(null, sources, Component.literal("SKYBLOCK"), Surface.SCOREBOARD);
			int[] visited = {0};
			FormattedCharSequence unrelated = sink -> {
				for (int i = 0; i < 4096; i++) {
					visited[0]++;
					if (!sink.accept(i, net.minecraft.network.chat.Style.EMPTY, 'Z')) return false;
				}
				return true;
			};
			check("增强档未知长文本立即跳过", resolve.invoke(null, unrelated, false, true, true) == null && visited[0] <= 4);
			for (String key : new String[] {"A", "AB", "ABC", "ABC DEF", "A😀B", "⏣ Dwarven Mines", "SKYBLOCK"}) {
				put.invoke(null, sources, Component.literal(key), Surface.SCOREBOARD);
				for (String input : new String[] {key, "  " + key, key + "    ", "\u2003" + key + "\u2003"})
					check("前缀筛选不丢短行、Unicode或缩进 " + input,
						(boolean) matches.invoke(probe, Component.literal(input).getVisualOrderText()));
			}
			var prefixes = PriorityText.class.getDeclaredField("SOURCE_PREFIXES");
			prefixes.setAccessible(true);
			// Even a filter with every bit set may only authorize a full lookup, never a translation.
			((java.util.BitSet) prefixes.get(null)).set(0, 65536);
			check("前缀碰撞不绕过服务器来源", customResult(Component.literal("CLICK"), DisplayPriority.HIGH) == null);
		} finally { session.invoke(null, null, false, false); }
	}

	private static Component customResult(Component input, DisplayPriority priority) throws Exception {
		var text = (FormattedCharSequence) resolve.invoke(null, input.getVisualOrderText(), true, true,
			priority == DisplayPriority.HIGH);
		return text == null ? null : PriorityText.component(text);
	}

	private static void checkSidebarTakeover() throws Exception {
		Method owns = ForceSidebar.class.getDeclaredMethod("ownsDisplay", boolean.class, java.util.function.BooleanSupplier.class);
		Method owner = ForceSidebar.class.getDeclaredMethod("isCustomRenderer", String.class);
		Method chat = io.github.bingkkni.skyzh.hook.ForceChat.class.getDeclaredMethod("ownsDisplay", boolean.class);
		for (Method method : new Method[] {owns, owner, chat}) method.setAccessible(true);
		int[] probes = {0};
		java.util.function.BooleanSupplier installed = () -> { probes[0]++; return true; };
		try {
			SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			check("默认档不检测或接管计分板", !(boolean) owns.invoke(null, true, installed) && probes[0] == 0);
			check("默认档不用独立聊天视图", !(boolean) chat.invoke(null, true));
			SkyZHConfig.get().displayPriority = DisplayPriority.HIGH;
			check("高档检测并保留玩家计分板", !(boolean) owns.invoke(null, true, installed) && probes[0] == 1);
			check("高档无自定义计分板时使用原有增强面板",
				(boolean) owns.invoke(null, true, (java.util.function.BooleanSupplier) () -> false));
			check("高档继承原强制聊天视图", (boolean) chat.invoke(null, true));
			SkyZHConfig.get().displayPriority = DisplayPriority.FORCE;
			check("强制不因辅助计分板开启而让出", (boolean) owns.invoke(null, true, installed) && probes[0] == 1);
			check("不满足HUD或服务器条件时不能接管", !(boolean) owns.invoke(null, false, installed));
			check("未验证服务器不能接管聊天", !(boolean) chat.invoke(null, false));
			HoldOriginal.setActive(true);
			check("原文键只切语言不取消增强视图", (boolean) chat.invoke(null, true));
			HoldOriginal.setActive(false);
			SkyZHConfig.get().enabled = false;
			check("总开关关闭解除增强视图", !(boolean) chat.invoke(null, true));
		} finally {
			SkyZHConfig.get().enabled = true;
			SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			HoldOriginal.setActive(false);
		}
		for (String name : new String[] {"me.owdding.customscoreboard.core.CustomScoreboardRenderer",
			"at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard",
			"com.github.noamm9.features.impl.visual.Scoreboard$hud$1"})
			check("仅匹配核实的计分板调用来源 " + name, (boolean) owner.invoke(null, name));
		for (String name : new String[] {"me.owdding.customscoreboard.core.CustomScoreboardRendererOther",
			"at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboardConfigFix",
			"at.hannibal2.skyhanni.features.gui.customtablist.AdvancedPlayerList",
			"com.github.noamm9.features.impl.visual.ScoreboardOther", "net.minecraft.client.gui.Hud"})
			check("其他功能不被计分板拦截 " + name, !(boolean) owner.invoke(null, name));
		check("帧外不拦截提交", !customDrawBlocked());
		ForceSidebar.withFrame(false, () -> check("高档不丢弃自定义绘制", !customDrawBlocked()), () -> {});
		ForceSidebar.withFrame(true, () -> {
			check("强制帧丢弃指定计分板提交", customDrawBlocked());
			check("同帧其他HUD提交不受影响", !ForceSidebar.suppressCustomDraw());
			ForceSidebar.withFrame(false, () -> check("嵌套非接管帧不拦截", !customDrawBlocked()), () -> {});
			check("嵌套返回恢复外层拦截", customDrawBlocked());
		}, () -> check("替代面板自身绘制不被拦截", !customDrawBlocked()));
		check("帧结束释放拦截", !customDrawBlocked());
		boolean[] replacement = {false};
		RuntimeException failure = new RuntimeException("draw failed");
		try {
			ForceSidebar.withFrame(true, () -> { throw failure; }, () -> replacement[0] = true);
			throw new AssertionError("expected draw failure");
		} catch (RuntimeException actual) { check("保留原绘制异常", actual == failure); }
		check("异常不泄漏拦截或补画残留面板", !customDrawBlocked() && !replacement[0]);
	}

	private static boolean customDrawBlocked() {
		return me.owdding.customscoreboard.core.CustomScoreboardRenderer.submit(ForceSidebar::suppressCustomDraw);
	}

	@SuppressWarnings("unchecked")
	private static void checkForceChatHistory() throws Exception {
		Class<?> history = io.github.bingkkni.skyzh.hook.ForceChat.class;
		var remember = history.getDeclaredMethod("rememberServer", Component.class, int.class);
		remember.setAccessible(true);
		var messagesField = history.getDeclaredField("MESSAGES");
		messagesField.setAccessible(true);
		var messages = (java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage>) messagesField.get(null);
		var anchor = history.getDeclaredMethod("anchoredScroll", java.util.List.class, java.util.List.class, int.class);
		anchor.setAccessible(true);
		try {
			Component boss = Component.literal("§c[BOSS] The Watcher§r§f: You have proven yourself. You may pass.");
			var live = boss.copy();
			remember.invoke(null, live, 10);
			live.append(" (2)");
			Component saved = messages.getFirst().content();
			check("聊天原文快照不受上游计数追加影响", !saved.getString().contains("(2)"));
			check("地牢Boss原文快照仍可命中", io.github.bingkkni.skyzh.text.Translator.translateLine(saved, Surface.CHAT)
				.getString().contains("你们已经证明了自己"));
			remember.invoke(null, boss, 11);
			check("重复服务器消息保留两次而非按字符串去重", messages.size() == 2 && messages.get(0) != messages.get(1));
			var older = messages.get(1);
			var newer = messages.get(0);
			var text = Component.literal("line").getVisualOrderText();
			var a = new net.minecraft.client.multiplayer.chat.GuiMessage.Line(older, text, true);
			var b = new net.minecraft.client.multiplayer.chat.GuiMessage.Line(newer, text, true);
			check("同文新消息不会抢走滚动锚点", anchor.invoke(null, java.util.List.of(a, a), java.util.List.of(b, a, a), 1).equals(2));
			check("缩窄后锚点在原消息范围内", anchor.invoke(null, java.util.List.of(a, a), java.util.List.of(b, a), 1).equals(1));
			var styled = Component.literal("Continue").withStyle(style -> style.withColor(0xFFAA00)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand("/test"))
				.withInsertion("insert"));
			remember.invoke(null, styled, 12);
			check("聊天快照保留点击和插入样式", StyledText.of(messages.getFirst().content()).equals(StyledText.of(styled)));
			io.github.bingkkni.skyzh.hook.ForceChat.invalidateLayout();
			check("字体重载不清原文", messages.size() == 3);
			var append = history.getDeclaredMethod("append", net.minecraft.client.multiplayer.chat.GuiMessage.class);
			append.setAccessible(true);
			{
				io.github.bingkkni.skyzh.hook.ForceChat.clear();
				var signature = new net.minecraft.network.chat.MessageSignature(new byte[256]);
				var player = new net.minecraft.client.multiplayer.chat.GuiMessage(10, styled, signature,
					net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER, null);
				append.invoke(null, player);
				// A deletion request alone no longer mutates the view. Vanilla creates the marker only when due.
				check("等待原版实际删除时保留签名原文",
					messages.getFirst().signature().equals(signature) && StyledText.of(messages.getFirst().content()).equals(StyledText.of(styled)));
				var vanillaMarker = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredMethod(
					"createDeletedMarker", net.minecraft.client.multiplayer.chat.GuiMessage.class);
				vanillaMarker.setAccessible(true);
				var marker = (net.minecraft.client.multiplayer.chat.GuiMessage) vanillaMarker.invoke(null, player);
				io.github.bingkkni.skyzh.hook.ForceChat.deleted(signature, marker);
				check("原版实际删除同步占位而非移除", messages.size() == 1 && messages.getFirst().content().equals(marker.content()));
				check("删除提示不当作服务器语料",
					messages.getFirst().source() == net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT);
			}
			for (int i = 0; i < 120; i++) remember.invoke(null, Component.literal("message " + i), i);
			check("聊天历史条数有界", messages.size() == 100);
			io.github.bingkkni.skyzh.hook.ForceChat.clear();
			for (int i = 0; i < 100; i++) remember.invoke(null, Component.literal("x".repeat(8192)), i);
			check("聊天历史总字符数有界", messages.size() == 16);
			check("超长聊天包不进入显示缓存", remember.invoke(null, Component.literal("x".repeat(8193)), 100).equals(false));
			check("拒绝超长包不清已有显示", messages.size() == 16);
			io.github.bingkkni.skyzh.hook.ForceChat.clear();
			for (int i = 0; i < 15; i++) remember.invoke(null, Component.literal("x".repeat(8192)), i);
			remember.invoke(null, Component.literal("x".repeat(8191)), 16);
			var shortSignature = new net.minecraft.network.chat.MessageSignature(new byte[256]);
			append.invoke(null, new net.minecraft.client.multiplayer.chat.GuiMessage(17, Component.literal("x"), shortSignature,
				net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER, null));
			check("删除前恰好达到字符预算", messages.stream().mapToInt(message -> message.content().getString().length()).sum() == 131072);
			io.github.bingkkni.skyzh.hook.ForceChat.deleted(shortSignature,
				new net.minecraft.client.multiplayer.chat.GuiMessage(17, Component.literal("deleted message"), null,
					net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT, null));
			check("较长删除标记仍遵守字符预算", messages.stream().mapToInt(message -> message.content().getString().length()).sum() <= 131072);
			check("容量裁剪保留最新删除提示", messages.getFirst().content().getString().equals("deleted message"));
		} finally { io.github.bingkkni.skyzh.hook.ForceChat.clear(); }
		check("清空聊天同时清独立历史与滚动", messages.isEmpty() && io.github.bingkkni.skyzh.hook.ForceChat.scroll() == 0);
	}

	private static void checkWidthReentry() throws Exception {
		var guard = PriorityText.class.getDeclaredField("resolving");
		guard.setAccessible(true);
		FormattedCharSequence protectedText = new PriorityText.ProtectedText(Component.literal("空岛生存").getVisualOrderText());
		// Model a third-party modifier wrapping the sequence before Font's injected HEAD runs.
		FormattedCharSequence wrapped = sink -> protectedText.accept(sink);
		var outputsField = PriorityText.class.getDeclaredField("OUTPUT");
		outputsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, Boolean> outputs = (Map<String, Boolean>) outputsField.get(null);
		outputs.put("空岛生存", true);
		var indexPrefix = PriorityText.class.getDeclaredMethod("indexPrefix", String.class);
		indexPrefix.setAccessible(true);
		indexPrefix.invoke(null, "空岛生存");
		SkyZHConfig.get().displayPriority = DisplayPriority.FORCE;
		check("已激活强制档确实会重新包装译文", resolveActive(wrapped, true) != null);
		for (DisplayPriority priority : DisplayPriority.values()) {
			SkyZHConfig.get().displayPriority = priority;
			try {
				int width = PriorityText.measureWidth(() -> {
					check("测量期间保护已启用 " + priority, widthGuardActive());
					check("包装丢失标记后不重入翻译 " + priority, resolveActive(wrapped, true) == null);
					int nested = PriorityText.measureWidth(() -> 24);
					check("嵌套测量保留外层保护 " + priority, widthGuardActive());
					check("嵌套测量后仍阻止重入 " + priority, resolveActive(wrapped, true) == null);
					return nested;
				});
				check("保留实际字宽 " + priority, width == 24);
				check("测量完成恢复保护状态 " + priority, !guard.getBoolean(null));
				RuntimeException failure = new RuntimeException("measurement failure");
				try {
					PriorityText.measureWidth(() -> { throw failure; });
					throw new AssertionError("measurement must throw");
				} catch (RuntimeException actual) {
					check("保留测量异常", actual == failure);
				}
				check("异常后恢复保护状态 " + priority, !guard.getBoolean(null));
			} finally {
				SkyZHConfig.get().displayPriority = DisplayPriority.DEFAULT;
			}
		}
		outputs.clear();
	}

	private static FormattedCharSequence resolveActive(FormattedCharSequence input, boolean forced) {
		try {
			var method = PriorityText.class.getDeclaredMethod("resolveActive", FormattedCharSequence.class, boolean.class);
			method.setAccessible(true);
			return (FormattedCharSequence) method.invoke(null, input, forced);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}

	private static boolean widthGuardActive() {
		try {
			var field = PriorityText.class.getDeclaredField("resolving");
			field.setAccessible(true);
			return field.getBoolean(null);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}

	private static Component result(Component input, boolean force) throws Exception {
		var text = (FormattedCharSequence) resolve.invoke(null, input.getVisualOrderText(), force, false, false);
		return text == null ? null : PriorityText.component(text);
	}

	private static void check(String name, boolean value) {
		if (!value) throw new AssertionError(name);
		checked++;
	}
}
