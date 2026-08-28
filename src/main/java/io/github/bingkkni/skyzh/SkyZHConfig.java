package io.github.bingkkni.skyzh;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The switches, in {@code config/skyzh.json}.
 *
 * <p>Mod Menu is a nicety, not a requirement, so the file is written to be edited by hand: each
 * option is emitted next to a {@code _说明} block explaining what it does, and anything unreadable
 * falls back to defaults rather than refusing to start. A translation mod that will not load because
 * its config file has a stray comma is worse than one that quietly translates with default settings.
 */
public final class SkyZHConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final Path FILE = locate();

	/**
	 * Null when there is no Fabric runtime to ask — which happens when the translation engine is
	 * exercised outside a running game. Defaults apply, and nothing is written.
	 */
	private static Path locate() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("skyzh.json");
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static SkyZHConfig instance;

	/**
	 * Bumped whenever the settings change, so anything holding rendered-and-cached text knows to
	 * throw it away. Toggling a switch in Mod Menu should change the screen on the next frame, not
	 * after the tooltip cache happens to evict the line.
	 */
	private static int generation;

	/** Whether the mod translates anything at all. Off means every surface is left in English. */
	public boolean enabled = true;

	/** Whether "SkyBlock" itself becomes 空岛生存. */
	public boolean translateSkyBlockName = true;

	/** Whether container titles keep the English alongside the Chinese, as 收藏品（Collections）. */
	public boolean showOriginal = true;

	/**
	 * Whether text the corpus has no answer for is written to disk as it is seen — off by default,
	 * and off is the only setting an ordinary player should ever want.
	 *
	 * <p>This is a tool for whoever is filling the corpus in, not a feature of the translation. It
	 * writes files to the player's own disk, and it exists because the structured sources the corpus
	 * is built from — the wiki, NEU-REPO, SkyHanni's regexes — have a hole in exactly one shape: the
	 * items inside a menu an NPC opens are in none of them, and the only place that text exists is on
	 * a screen while somebody is playing.
	 *
	 * <p>Nothing about the game changes when it is on. Capture reads packets and writes JSON; it never
	 * touches what is drawn, and turning it off mid-session stops it on the next line.
	 */
	public boolean captureUntranslated = false;

	/**
	 * Where capture writes, relative to the game directory.
	 *
	 * <p>Two directories are made under it: {@code untranslated/} for lines nothing answered for, and
	 * {@code mixed/} for lines a record answered for and still left half English. Inside each, the
	 * layout is {@code original_text/}'s own — gameplay, then surface, then name.
	 */
	public String captureDirectory = "skyzh-capture";

	/**
	 * The only server capture is allowed to run on, matched on the end of the address.
	 *
	 * <p>A guard rather than a preference. Capture produces data meant to end up in a SkyBlock corpus,
	 * and text collected on some other server would look exactly like SkyBlock text once it was in the
	 * file — there would be no way to find it again. Emptying this turns the check off, which is a
	 * reasonable thing to do behind a proxy and an unreasonable thing to do otherwise.
	 */
	public String captureServer = "hypixel.net";

	public static SkyZHConfig get() {
		if (instance == null) {
			instance = read();
		}

		return instance;
	}

	private static SkyZHConfig read() {
		SkyZHConfig config = new SkyZHConfig();

		if (FILE == null) {
			return config;
		}

		if (!Files.exists(FILE)) {
			config.save();
			return config;
		}

		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			config.enabled = bool(json, "enabled", true);
			config.translateSkyBlockName = bool(json, "translateSkyBlockName", true);
			config.showOriginal = bool(json, "showOriginal", true);
			config.captureUntranslated = bool(json, "captureUntranslated", false);
			config.captureDirectory = string(json, "captureDirectory", "skyzh-capture");
			config.captureServer = string(json, "captureServer", "hypixel.net");
		} catch (Exception e) {
			LOGGER.warn("读取 {} 失败，本次使用默认设置：{}", FILE, e.toString());
		}

		return config;
	}

	private static boolean bool(JsonObject json, String key, boolean fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	/** @see #generation */
	public static int generation() {
		return generation;
	}

	public void save() {
		generation++;

		if (FILE == null) {
			return;
		}

		JsonObject help = new JsonObject();
		help.addProperty("enabled", "是否启用 Mod 功能。关闭将不翻译任何文本。");
		help.addProperty("translateSkyBlockName", "是否翻译 SkyBlock 玩法名。翻译后的文本为「空岛生存」。");
		help.addProperty("showOriginal", "启用翻译对比：在容器标题和物品名上额外标识英文，如「收藏品（Collections）」「秘银镐（Mithril Pickaxe）」。集市和拍卖行是按英文名搜索的，关掉之后就搜不到自己手里的物品了。");
		help.addProperty("captureUntranslated", "【给翻译者用，普通玩家请保持关闭】把游戏里还没翻译、以及翻译了但仍中英混杂的文本写到硬盘上，供补全语料用。只采集服务器发来的原文，不会采集其他 Mod 的文本，也不会改变游戏里显示的任何内容。打开它时，如果装了 hypixel-mod-api，会向服务器订阅一次位置事件（用来判断采到的文本属于哪个玩法）——这是本 Mod 唯一一处往外发包的地方，关掉就不发。");
		help.addProperty("captureDirectory", "采集输出目录，相对于游戏目录。里面按 untranslated/ 与 mixed/ 分两堆，各自再按玩法/来源/名字分目录，和 original_text/ 的结构一致。");
		help.addProperty("captureServer", "只在这个服务器上采集（按域名后缀匹配）。留空表示不检查服务器地址，只靠计分板判断是不是在 SkyBlock —— 用代理连服的时候才需要留空。");

		JsonObject json = new JsonObject();
		json.add("_说明", help);
		json.addProperty("enabled", this.enabled);
		json.addProperty("translateSkyBlockName", this.translateSkyBlockName);
		json.addProperty("showOriginal", this.showOriginal);
		json.addProperty("captureUntranslated", this.captureUntranslated);
		json.addProperty("captureDirectory", this.captureDirectory);
		json.addProperty("captureServer", this.captureServer);

		try {
			Files.createDirectories(FILE.getParent());

			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("写入 {} 失败，本次修改不会被记住：{}", FILE, e.toString());
		}
	}
}
