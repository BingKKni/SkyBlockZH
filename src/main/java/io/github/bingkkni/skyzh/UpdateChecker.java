package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.platform.ClientGui;
import io.github.bingkkni.skyzh.text.TranslationPack;
import io.github.bingkkni.skyzh.text.Translator;
import io.github.bingkkni.skyzh.update.ModUpdate;
import io.github.bingkkni.skyzh.update.TranslationUpdate;
import io.github.bingkkni.skyzh.update.UpdateFiles;
import io.github.bingkkni.skyzh.update.UpdateHttp;
import io.github.bingkkni.skyzh.update.UpdateState;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Both update lanes run only in verified SkyBlock, off the client thread, with independent persisted clocks. */
public final class UpdateChecker {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String API = "https://api.github.com/repos/BingKKni/SkyBlockZH/";
	private static final Pattern RELEASE_VERSION = Pattern.compile(
		"\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
	private static final ConcurrentLinkedQueue<String> MESSAGES = new ConcurrentLinkedQueue<>();
	private static final AtomicBoolean STARTING = new AtomicBoolean();
	private static volatile Service service;
	private static volatile long initializeAfter;
	private static volatile UpdateState clocks;
	private static long nextTick;

	private UpdateChecker() {}

	public static boolean eligible(boolean enabled, boolean updates, boolean skyBlock, boolean initialLoadFinished) {
		return enabled && updates && skyBlock && initialLoadFinished;
	}

	private static boolean enabled() { return SkyZHConfig.get().enabled && SkyZHConfig.get().updateCheck; }

	public static void tick(Minecraft minecraft) {
		if (!enabled()) { MESSAGES.clear(); return; }
		if (!eligible(true, true, HypixelServer.isSkyBlock(), !Translator.loading())
			|| minecraft.player == null || minecraft.level == null || minecraft.gui == null) return;
		for (String message; (message = MESSAGES.poll()) != null;) ClientGui.chat(minecraft, Component.literal("§b[SkyZH] " + message));
		long now = System.currentTimeMillis();
		if (now < nextTick) return;
		nextTick = now + 1000;
		if (service == null) {
			String identity = initializationIdentity();
			if (now < initializeAfter || (clocks != null && !clocks.due("initialization", identity, now))
				|| !STARTING.compareAndSet(false, true)) return;
			Thread.startVirtualThread(() -> {
				try {
					if (clocks == null) clocks = new UpdateState(FabricLoader.getInstance().getConfigDir().resolve("skyzh-updates.json"));
					if (!clocks.due("initialization", identity, System.currentTimeMillis())) return;
					service = new Service(clocks);
				} catch (Exception e) {
					LOGGER.warn("初始化自动更新失败", e);
					message("§e暂时无法准备更新文件，当前版本仍可使用，1 小时后再试。");
					initializeAfter = System.currentTimeMillis() + UpdateState.HOUR;
					if (clocks != null) {
						JsonObject details = new JsonObject(); details.addProperty("error", e.toString());
						try { clocks.finish("initialization", identity, System.currentTimeMillis(), UpdateState.HOUR, "failed", details); }
						catch (IOException persistence) { LOGGER.warn("初始化更新失败的等待时间无法保存", persistence); }
					}
				} finally { STARTING.set(false); }
			});
			return;
		}
		service.tick(now);
	}

	private static String initializationIdentity() {
		return FabricLoader.getInstance().getModContainer("skyzh").orElseThrow().getMetadata().getVersion().getFriendlyString()
			+ ":" + TranslationPack.api();
	}

	private static void message(String text) { if (enabled()) MESSAGES.add(text); }

	private static final class Service {
		private final FabricLoader loader = FabricLoader.getInstance();
		private final Path game = loader.getGameDir().toAbsolutePath().normalize();
		private final Path directory = game.resolve(TranslationPack.UPDATE_DIRECTORY);
		private final Path automatic = directory.resolve(TranslationPack.FILE_NAME);
		private final Path manual = game.resolve(TranslationPack.DIRECTORY).resolve(TranslationPack.FILE_NAME);
		private final UpdateHttp http = new UpdateHttp();
		private final UpdateState state;
		private final String installed = loader.getModContainer("skyzh").orElseThrow().getMetadata().getVersion().getFriendlyString();
		private final String translationIdentity = TranslationPack.api() + ":" + installed;
		private final AtomicBoolean modBusy = new AtomicBoolean(), translationsBusy = new AtomicBoolean();
		private volatile Process installer;

		Service(UpdateState state) throws IOException {
			this.state = state;
			Files.createDirectories(directory);
		}

		void tick(long now) {
			Process process = installer;
			if (process != null && !process.isAlive()) {
				installer = null;
				Thread.startVirtualThread(() -> {
					finish("mod", installed, UpdateState.HOUR, "installer_stopped", null);
					message("§eMod 更新安装程序已停止，当前版本仍可使用，1 小时后再试。");
				});
			}
			start("mod", installed, now, modBusy, this::checkMod);
			start("translations", translationIdentity, now, translationsBusy, this::checkTranslations);
		}

		private void start(String lane, String identity, long now, AtomicBoolean busy, Checked work) {
			if (!state.due(lane, identity, now) || !busy.compareAndSet(false, true)) return;
			Thread.startVirtualThread(() -> {
				try { if (enabled()) work.run(); }
				catch (Exception e) {
					LOGGER.warn("SkyZH {} 更新失败", lane, e);
					JsonObject details = new JsonObject(); details.addProperty("error", e.toString());
					finish(lane, identity, UpdateState.HOUR, "failed", details);
					message(lane.equals("mod")
						? "§eMod 更新未能完成，当前版本仍可使用，1 小时后再试。详情已写入游戏日志。"
						: "§e翻译更新未能完成，已保留当前翻译，1 小时后再试。详情已写入游戏日志。");
				} finally { busy.set(false); }
			});
		}

		private void finish(String lane, String identity, long delay, String result, JsonObject details) {
			try { state.finish(lane, identity, System.currentTimeMillis(), delay, result, details); }
			catch (IOException e) {
				LOGGER.warn("更新状态无法保存到配置文件", e);
				message("§e更新检查记录暂时无法保存，重启游戏后可能会重新检查。");
			}
		}

		private void checkTranslations() throws Exception {
			// Contents API supplies the small JSON; ZIP bytes remain Release assets, outside Git history.
			JsonObject envelope = http.json(URI.create(API + "contents/skyzh-updates/update.json?ref=main"));
			if (!UpdateFiles.string(envelope, "encoding").equals("base64")) throw new IOException("GitHub 清单编码不支持");
			byte[] bytes = Base64.getDecoder().decode(UpdateFiles.string(envelope, "content").replaceAll("\\s", ""));
			TranslationUpdate.Offer offer = TranslationUpdate.offer(TranslationPack.parse(bytes, "update.json"));
			if (offer.api() != TranslationPack.api()) {
				finish("translations", translationIdentity, UpdateState.WEEK, "incompatible", offer.manifest());
				message(offer.api() > TranslationPack.api()
					? "§e新翻译需要更新版本的 SkyZH，请先更新 Mod。7 天后会再次检查翻译更新。"
					: "§e网上的翻译暂时还不适用于当前 SkyZH，已保留当前翻译，7 天后再试。");
				return;
			}
			TranslationPack.validateManifest(offer.manifest());
			if (!TranslationUpdate.newer(offer.manifest(), TranslationUpdate.baseline(automatic))) {
				finish("translations", translationIdentity, UpdateState.HOUR, "current", offer.manifest()); return;
			}
			Path candidate = http.download(offer.download(), directory.resolve("translations-download.zip"), UpdateChecker::enabled);
			CompletableFuture<TranslationPack.Loaded> publication = new CompletableFuture<>();
			boolean accepted = Translator.reloadInBackground(() -> {
				try { return TranslationUpdate.install(candidate, automatic, manual, offer, UpdateChecker::enabled); }
				catch (IOException e) { throw new java.io.UncheckedIOException(e); }
			}, publication::complete, publication::completeExceptionally);
			if (!accepted) throw new IOException("另一个翻译重载正在运行");
			TranslationPack.Loaded loaded = publication.get();
			finish("translations", translationIdentity, UpdateState.HOUR, "updated", offer.manifest());
			message(loaded.source() == TranslationPack.Source.MANUAL
				? "§a自动翻译包已更新！当前仍优先使用你手动安装的翻译包。"
				: "§a翻译已更新并立即生效，无需重启游戏！");
		}

		private Path installedJar() throws IOException {
			var origin = loader.getModContainer("skyzh").orElseThrow().getOrigin().getPaths();
			if (loader.isDevelopmentEnvironment() || origin.size() != 1) throw new IOException("开发或嵌套安装不能自动替换");
			Path jar = origin.getFirst().toRealPath();
			if (!jar.startsWith(game.resolve("mods").toRealPath()) || !jar.getFileName().toString().endsWith(".jar"))
				throw new IOException("SkyZH 不在当前实例的 mods 目录中");
			return jar;
		}

		private void checkMod() throws Exception {
			var pending = ModUpdate.plan(directory);
			if (ModUpdate.installerAlive(pending)) {
				finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", null); return;
			}
			String pendingVersion = pending.getProperty("version", "");
			if (isNewer(pendingVersion, installed)) {
				Path candidate = directory.resolve("mod-update.jar");
				if (Files.isRegularFile(candidate) && UpdateFiles.sha256(candidate).equals(pending.getProperty("new_sha256"))) {
					validateMod(candidate, pendingVersion);
					if (!enabled()) return;
					installer = ModUpdate.arm(directory, installedJar(), pending.getProperty("new_sha256"), pendingVersion);
					finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", null);
					message("§aSkyZH " + pendingVersion + " 已准备好！退出游戏后会自动安装，下次启动生效。");
					return;
				}
			}
			JsonObject release = http.json(URI.create(API + "releases/latest"));
			String latest = UpdateFiles.string(release, "tag_name");
			if (release.has("prerelease") && release.get("prerelease").getAsBoolean()) throw new IOException("Mod 更新入口返回预发布");
			if (!isNewer(latest, installed)) { finish("mod", installed, UpdateState.HOUR, "current", releaseSummary(latest)); return; }
			String target = installed.contains("+mc") ? installed.substring(installed.indexOf("+mc") + 3) : "";
			ModUpdate.Asset asset = ModUpdate.select(release, target);
			if (asset == null) {
				finish("mod", installed, UpdateState.HOUR, "no_matching_download", releaseSummary(latest));
				message("§e发现 SkyZH " + latest + "，但还没有适合当前 Minecraft 的下载文件，1 小时后再检查。"); return;
			}
			Path candidate = http.download(asset.download(), directory.resolve("mod-update.jar"), UpdateChecker::enabled);
			validateMod(candidate, latest);
			if (!enabled()) return;
			installer = ModUpdate.arm(directory, installedJar(), asset.download().sha256(), latest);
			finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", releaseSummary(latest));
			message("§aSkyZH " + latest + " 已下载！退出游戏后会自动安装，下次启动生效。");
		}

		private void validateMod(Path candidate, String version) throws Exception {
			ModUpdate.validate(candidate, version,
				loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString(),
				loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString());
		}

		private JsonObject releaseSummary(String version) {
			JsonObject json = new JsonObject(); json.addProperty("version", version); return json;
		}
	}

	@FunctionalInterface private interface Checked { void run() throws Exception; }

	/** Fabric version ordering ignores build metadata and handles prereleases correctly. */
	static boolean isNewer(String available, String installed) {
		try {
			String candidate = normalizeVersion(available), current = normalizeVersion(installed);
			return RELEASE_VERSION.matcher(candidate).matches() && RELEASE_VERSION.matcher(current).matches()
				&& Version.parse(candidate).compareTo(Version.parse(current)) > 0;
		} catch (Exception ignored) { return false; }
	}

	private static String normalizeVersion(String version) {
		return version == null ? "" : version.trim().replaceFirst("^[vV]", "");
	}
}
