package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.compat.HypixelApi;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.Translator;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyZH — a Chinese translation layer for Hypixel SkyBlock.
 *
 * <p>The mod does one thing on startup: read the corpus. Everything after that happens in render
 * hooks, one per surface, each of which translates the text on its way to the screen and leaves the
 * object it came from alone. No game state is written — a client running this mod is, as far as
 * Hypixel and as far as every other mod is concerned, a client running in English.
 *
 * <p>One thing does read packets, and only when a switch that is off by default is turned on: the
 * capture in {@code capture/}, which writes the text no record answers for to disk so the corpus can
 * be filled in. It reads and never writes, on the packet handler rather than the screen, and is
 * documented at {@code capture/TextCapture}. That same switch, and nothing else in the mod, sends
 * one thing to the server: a subscription to Hypixel's own location event, which says which island
 * captured text belongs to — see {@code compat/HypixelApi} for why it is tied to that switch.
 *
 * <p>That last part is a requirement, not a nicety. SkyHanni and SkyBlocker work by matching English
 * text; a translation mod that rewrote the text they read would silently break them, which for most
 * SkyBlock players would be a far worse trade than reading English in the first place.
 */
public final class SkyZH implements ClientModInitializer {
	public static final String MOD_ID = "skyzh";
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

	@Override
	public void onInitializeClient() {
		SkyZHConfig config = SkyZHConfig.get();
		Translator.reload();

		TranslationIndex index = Translator.index();
		LOGGER.info(
			"SkyZH 就绪：聊天 {} 条 / 界面标题 {} 条 / 物品 {} 条 / 动作栏 {} 条 / BossBar {} 条 / 计分板 {} 条 / "
				+ "Tab 列表 {} 条 / 头顶浮空字 {} 条 / 其他 {} 条。翻译总开关：{}。",
			index.size(Surface.CHAT), index.size(Surface.GUI_TITLE), index.size(Surface.ITEM),
			index.size(Surface.ACTION_BAR), index.size(Surface.BOSS_BAR), index.size(Surface.SCOREBOARD),
			index.size(Surface.TABLIST), index.size(Surface.HOLOGRAM), index.size(Surface.MISC),
			config.enabled ? "开" : "关"
		);

		if (config.captureUntranslated) {
			// Subscribed here rather than when the first line is captured, because the subscription has
			// to be in place before the player joins for Hypixel to send the location of the island
			// they land on. Turning the switch on mid-session works too — see HypixelApi#install.
			HypixelApi.install();

			// Said out loud on every start, because it writes files and because a switch somebody
			// turned on to answer one question is a switch they will forget is on.
			LOGGER.info(
				"SkyZH 未翻译文本采集：开。将把未翻译 / 中英混杂的服务器原文写入游戏目录下的 {}/。"
					+ "这是给补语料用的开发者功能，普通游玩时请在 Mod Menu 里关掉。",
				config.captureDirectory
			);
		}
	}
}
