package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.platform.ClientGui;
import io.github.bingkkni.skyzh.text.ItemNames;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.Translator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Original-key hints observe incoming server chat, never chat history or tooltip rendering. */
public final class OriginalTips {
	private static final long INTERVAL_NANOS = 300_000_000_000L;
	private static long lastTip;
	private static boolean shown;

	private OriginalTips() {
	}

	public static boolean eligibleName(Component original, Translator.Result translated) {
		return translated.matched()
			&& ItemNames.canonical(StyledText.of(original).plain()) != null
			&& !StyledText.of(translated.padded()).plain().trim().equals(StyledText.of(original).plain().trim());
	}

	public static boolean containsTranslatedItem(Component message) {
		StyledText source = StyledText.of(message);
		String plain = source.plain();
		int start = 0;
		while (start < plain.length()) {
			int newline = plain.indexOf('\n', start);
			int end = newline < 0 ? plain.length() : newline;
			Translator.Located located = Translator.locate(source.sub(start, end), Surface.CHAT);
			if (located.matched() && located.entry().hasTranslatedItemReference(
				located.core(), located.match(), Translator.index().terms())) {
				return true;
			}
			start = end + 1;
		}
		return false;
	}

	public static Component loreHint(String key) {
		return Component.literal("§b[SkyZH] §6按住 " + key + " 键显示原文");
	}

	static Component chatHint(String key) {
		return Component.literal("§b[SkyZH] §e提示: 按住 " + key
			+ " 键可以显示该物品的原文，方便你在集市或拍卖行里寻找这个物品! ")
			.append(Component.literal("[禁用提示]").withStyle(style -> style
				.withColor(ChatFormatting.RED)
				.withClickEvent(new ClickEvent.RunCommand("/skyzh switch tip off"))));
	}

	private static boolean intervalReady(long now) {
		return !shown || now - lastTip >= INTERVAL_NANOS;
	}

	static boolean claimInterval(long now) {
		if (!intervalReady(now)) {
			return false;
		}
		shown = true;
		lastTip = now;
		return true;
	}

	public static void serverChat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || !minecraft.isSameThread()) {
			return;
		}
		SkyZHConfig config = SkyZHConfig.get();
		String key = HoldOriginal.keyName();
		long now = System.nanoTime();
		if (!HypixelServer.canTranslate() || !config.enabled || !config.originalTips
			|| key == null || HoldOriginal.active() || !intervalReady(now) || !containsTranslatedItem(message)) {
			return;
		}
		if (claimInterval(now)) {
			ClientGui.chat(minecraft, chatHint(key));
		}
	}
}
