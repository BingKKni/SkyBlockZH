package io.github.bingkkni.skyzh.update;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Player-approved update wording, with interaction attached only to the bracketed action. */
public final class UpdateMessages {
	private UpdateMessages() {}

	public static Component chat(Component body) {
		return text("[SkyZH] ", ChatFormatting.AQUA).append(body);
	}

	public static Component translationUpdated(boolean manual, String command) {
		MutableComponent message = text("翻译更新成功!", ChatFormatting.GREEN);
		if (manual) message.append(" ").append(text("但由于你手动安装了外置翻译包，新安装的翻译不会生效。", ChatFormatting.YELLOW))
			.append(button("[点击卸载外置翻译包]", ChatFormatting.RED, command, "点击卸载外置翻译包，此操作不可撤销!"));
		return message;
	}

	public static Component modDownloaded(String version) {
		// 用户拍板：统一写“下次重启时安装”；重启过程会先退出客户端。
		return text("已下载 SkyZH " + version + "，下次重启时安装。", ChatFormatting.GREEN);
	}

	public static Component incompatible(String command) {
		return text("发现新版本翻译包，但它与此计算机上的 SkyZH 不兼容，请更新 Mod。", ChatFormatting.YELLOW)
			.append(button("[点击这里下载]", ChatFormatting.GREEN, command, "点击这里来下载新版 SkyZH!"));
	}

	public static Component translationFailed(String command) {
		return text("更新翻译包时出错，将在1小时后重试。", ChatFormatting.YELLOW)
			.append(button("[立即重试]", ChatFormatting.GOLD, command, "点击来尝试再次安装新版翻译包!"));
	}

	public static Component modFailed(String command) {
		return text("更新 SkyZH 时出错，将在1小时后重试。", ChatFormatting.YELLOW)
			.append(button("[立即重试]", ChatFormatting.GOLD, command, "点击来尝试再次安装新版 SkyZH!"));
	}

	public static Component checkFailed(String command) {
		return text("检查更新时出错。", ChatFormatting.RED)
			.append(button("[重试]", ChatFormatting.GREEN, command, "点击来尝试再次检查更新!"));
	}

	private static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
		return text(label, color).withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(text(hover, ChatFormatting.YELLOW))));
	}

	private static MutableComponent text(String value, ChatFormatting color) { return Component.literal(value).withStyle(color); }
}
