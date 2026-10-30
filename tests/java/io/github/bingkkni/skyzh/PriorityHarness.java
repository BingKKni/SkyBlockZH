package io.github.bingkkni.skyzh;

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
		resolve = PriorityText.class.getDeclaredMethod("resolveKnown", FormattedCharSequence.class, boolean.class);
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
				net.minecraft.locale.Language.getInstance().getVisualOrder(wrapped), false);
			check("通用FormattedText保持同一译文", PriorityText.component(wrappedResult).getString().equals("空岛生存"));
			check("高档保留实时显示颜色", StyledText.of(high).styleAt(0).getColor().getValue() == 0xFF5555);
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
		checkWidthReentry();
		System.out.println("显示优先级检查通过 " + checked + " 项");
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
		check("已激活强制档确实会重新包装译文", resolveActive(wrapped, true) != null);
		for (DisplayPriority priority : new DisplayPriority[] {DisplayPriority.HIGH, DisplayPriority.FORCE}) {
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
		var text = (FormattedCharSequence) resolve.invoke(null, input.getVisualOrderText(), force);
		return text == null ? null : PriorityText.component(text);
	}

	private static void check(String name, boolean value) {
		if (!value) throw new AssertionError(name);
		checked++;
	}
}
