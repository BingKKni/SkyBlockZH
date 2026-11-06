package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.platform.ClientGui;
import io.github.bingkkni.skyzh.text.TranslationPack;
import io.github.bingkkni.skyzh.text.Translator;
import io.github.bingkkni.skyzh.update.ModUpdate;
import io.github.bingkkni.skyzh.update.TranslationUpdate;
import io.github.bingkkni.skyzh.update.UpdateActions;
import io.github.bingkkni.skyzh.update.UpdateActions.Action;
import io.github.bingkkni.skyzh.update.UpdateFiles;
import io.github.bingkkni.skyzh.update.UpdateHttp;
import io.github.bingkkni.skyzh.update.UpdateMessages;
import io.github.bingkkni.skyzh.update.UpdateState;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Automatic and user-requested updates share workers, validation, installation and busy guards. */
public final class UpdateChecker {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final String API = "https://api.github.com/repos/BingKKni/SkyBlockZH/";
	private static final Pattern RELEASE_VERSION = Pattern.compile(
		"\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
	private static final ConcurrentLinkedQueue<Component> MESSAGES = new ConcurrentLinkedQueue<>();
	private static final UpdateActions ACTIONS = new UpdateActions();
	private static final AtomicBoolean STARTING = new AtomicBoolean();
	private static volatile Service service;
	private static volatile long initializeAfter;
	private static volatile UpdateState clocks;
	private static long nextTick;

	private UpdateChecker() {}

	public static boolean eligible(boolean enabled, boolean updates, boolean skyBlock, boolean initialLoadFinished) {
		return enabled && updates && skyBlock && initialLoadFinished;
	}

	static boolean enabled(boolean requested) {
		return SkyZHConfig.get().enabled && (requested || SkyZHConfig.get().updateCheck);
	}

	public static void tick(Minecraft minecraft) {
		if (!SkyZHConfig.get().enabled) { MESSAGES.clear(); return; }
		if (!eligible(true, true, HypixelServer.isSkyBlock(), !Translator.loading())
			|| minecraft.player == null || minecraft.level == null || minecraft.gui == null) return;
		for (Component message; (message = MESSAGES.poll()) != null;) ClientGui.chat(minecraft, UpdateMessages.chat(message));
		if (!enabled(false)) return;
		long now = System.currentTimeMillis();
		if (now < nextTick) return;
		nextTick = now + 1000;
		if (service == null) initialize(false, Action.CHECK);
		else service.tick();
	}

	/** Explicit checks bypass the automatic-update setting and cooldown, but share its worker. */
	public static void checkNow(Action action) {
		if (!enabled(true)) { Feedback.send("§c请先开启 Mod 功能。"); return; }
		if (!HypixelServer.isSkyBlock()) { Feedback.send("§c请进入 Hypixel 空岛生存后再检查更新。"); return; }
		if (Translator.loading()) { Feedback.send("§e翻译正在加载中，请稍后再检查更新。"); return; }
		Feedback.send(request(action) ? switch (action) {
			case MOD -> "§e正在检查 Mod 更新…";
			case TRANSLATIONS -> "§e正在检查翻译包更新…";
			default -> "§e正在检查 Mod 和翻译包更新…";
		} : "§e正在检查或安装更新，请稍后再试。");
	}

	private static boolean request(Action action) {
		Service current = service;
		return current == null ? initialize(true, action) : current.retry(action);
	}

	/** Hidden local commands contain only an issued token, never a path or a download URL. */
	public static boolean runAction(String token) {
		if (!HypixelServer.isSkyBlock() || Translator.loading()) return false;
		return ACTIONS.run(token, action -> {
			if (action == Action.REMOVE_MANUAL) return SkyZHCommand.reloadTranslations(() -> {
				TranslationPack.Loaded loaded = TranslationPack.removeManualAndLoad();
				ACTIONS.complete(token);
				return loaded;
			});
			return enabled(true) && request(action);
		});
	}

	private static boolean initialize(boolean immediate, Action requested) {
		String identity = initializationIdentity();
		long now = System.currentTimeMillis();
		if ((!immediate && (now < initializeAfter || (clocks != null && !clocks.due("initialization", identity, now))))
			|| !STARTING.compareAndSet(false, true)) return false;
		try {
			Thread.startVirtualThread(() -> {
				try {
					if (clocks == null) clocks = new UpdateState(FabricLoader.getInstance().getConfigDir().resolve("skyzh-updates.json"));
					if (!immediate && !clocks.due("initialization", identity, System.currentTimeMillis())) return;
					Service next = new Service(clocks);
					try { clocks.initialized(identity, System.currentTimeMillis()); }
					catch (IOException persistence) {
						LOGGER.warn("初始化恢复成功，但检查记录无法保存", persistence);
						message(UpdateMessages.checkFailed(ACTIONS.issue(Action.CHECK)));
					}
					initializeAfter = 0;
					if (immediate) next.retry(requested);
					service = next;
				} catch (Exception e) {
					LOGGER.warn("初始化自动更新失败", e);
					message(UpdateMessages.checkFailed(ACTIONS.issue(Action.CHECK)));
					initializeAfter = System.currentTimeMillis() + UpdateState.HOUR;
					if (clocks != null) {
						JsonObject details = new JsonObject(); details.addProperty("error", e.toString());
						try { clocks.finish("initialization", identity, System.currentTimeMillis(), UpdateState.HOUR, "failed", details); }
						catch (IOException persistence) { LOGGER.warn("初始化更新失败的等待时间无法保存", persistence); }
					}
				} finally { STARTING.set(false); }
			});
		} catch (RuntimeException e) { STARTING.set(false); throw e; }
		return true;
	}

	private static String initializationIdentity() {
		return FabricLoader.getInstance().getModContainer("skyzh").orElseThrow().getMetadata().getVersion().getFriendlyString()
			+ ":" + TranslationPack.api();
	}

	private static void message(Component text) { if (enabled(true)) MESSAGES.add(text); }

	/** Same concurrency gate for automatic deadlines and an explicit click that bypasses the deadline. */
	static boolean launch(boolean enabled, boolean due, boolean immediate, AtomicBoolean busy, Runnable work, Executor background) {
		if (!enabled || (!due && !immediate) || !busy.compareAndSet(false, true)) return false;
		try { background.execute(() -> { try { work.run(); } finally { busy.set(false); } }); }
		catch (RuntimeException e) { busy.set(false); throw e; }
		return true;
	}

	/** A combined request keeps the worker occupied until both checks finish, Mod first. */
	static void runChecks(Action action, Runnable mod, Runnable translations) {
		if (action == Action.MOD || action == Action.CHECK || action == Action.INSTALL) mod.run();
		if (action == Action.TRANSLATIONS || action == Action.CHECK) translations.run();
	}

	static boolean needsModNotice(boolean newMod, boolean newTranslation, int remoteApi, int localApi) {
		return newMod && newTranslation && remoteApi != localApi;
	}

	private static final class Service {
		private final FabricLoader loader = FabricLoader.getInstance();
		private final Path game = loader.getGameDir().toAbsolutePath().normalize();
		private final Path directory = game.resolve(TranslationPack.UPDATE_DIRECTORY);
		private final Path automatic = directory.resolve(TranslationPack.FILE_NAME);
		private final Path manual = game.resolve(TranslationPack.DIRECTORY).resolve(TranslationPack.FILE_NAME);
		private final UpdateHttp http = new UpdateHttp();
		private final UpdateState state;
		private final String installed = loader.getModContainer("skyzh").orElseThrow().getMetadata().getVersion().getFriendlyString();
		private final java.util.Properties build = buildInfo();
		private final String translationIdentity = TranslationPack.api() + ":" + installed;
		private final AtomicBoolean busy = new AtomicBoolean();
		private volatile Process installer;
		private volatile String unpublishedTranslation;

		Service(UpdateState state) throws IOException {
			this.state = state;
			Files.createDirectories(directory);
		}

		void tick() {
			Process process = installer;
			if (process != null && !process.isAlive()) {
				installer = null;
				Thread.startVirtualThread(() -> {
					finish("mod", installed, UpdateState.HOUR, "installer_stopped", null);
					message(UpdateMessages.modFailed(ACTIONS.issue(Action.MOD)));
				});
			}
			start(Action.CHECK, false);
		}

		boolean retry(Action action) {
			return start(action, true);
		}

		private boolean start(Action action, boolean immediate) {
			long now = System.currentTimeMillis();
			boolean due = immediate || state.due("mod", installed, now) || state.due("translations", translationIdentity, now);
			return launch(enabled(immediate), due, immediate, busy, () -> runChecks(action,
				() -> check("mod", installed, immediate, () -> checkMod(immediate, action == Action.INSTALL)),
				() -> check("translations", translationIdentity, immediate, () -> checkTranslations(immediate))), Thread::startVirtualThread);
		}

		private void check(String lane, String identity, boolean immediate, Checked work) {
			if (!enabled(immediate) || (!immediate && !state.due(lane, identity, System.currentTimeMillis()))) return;
			try { work.run(); }
			catch (NoMatchingDownload e) { if (immediate) message(UpdateMessages.noModDownload()); }
			catch (Exception e) {
				LOGGER.warn("SkyZH {} 更新失败", lane, e);
				JsonObject details = new JsonObject(); details.addProperty("error", e.toString());
				finish(lane, identity, UpdateState.HOUR, "failed", details);
				message(lane.equals("mod") ? UpdateMessages.modFailed(ACTIONS.issue(Action.MOD))
					: UpdateMessages.translationFailed(ACTIONS.issue(Action.TRANSLATIONS)));
			}
		}

		private void finish(String lane, String identity, long delay, String result, JsonObject details) {
			try { state.finish(lane, identity, System.currentTimeMillis(), delay, result, details); }
			catch (IOException e) {
				LOGGER.warn("更新状态无法保存到配置文件", e);
				message(UpdateMessages.checkFailed(ACTIONS.issue(Action.CHECK)));
			}
		}

		private JsonObject latestRelease() throws Exception {
			JsonObject release = http.json(URI.create(API + "releases/latest"));
			UpdateFiles.string(release, "tag_name");
			if (release.has("prerelease") && release.get("prerelease").getAsBoolean()) throw new IOException("Mod 更新入口返回预发布");
			return release;
		}

		/** The published manifest: the translation bundle, and the newest per-commit test build. */
		private JsonObject updateManifest() throws Exception {
			JsonObject envelope = http.json(URI.create(API + "contents/skyzh-updates/update.json?ref=main"));
			if (!UpdateFiles.string(envelope, "encoding").equals("base64")) throw new IOException("GitHub 清单编码不支持");
			byte[] bytes = Base64.getDecoder().decode(UpdateFiles.string(envelope, "content").replaceAll("\\s", ""));
			return TranslationPack.parse(bytes, "update.json");
		}

		private void checkTranslations(boolean requested) throws Exception {
			TranslationUpdate.Offer offer = TranslationUpdate.offer(updateManifest());
			boolean newer = TranslationUpdate.newer(offer.manifest(), TranslationUpdate.baseline(automatic));
			if (offer.api() != TranslationPack.api()) {
				finish("translations", translationIdentity, UpdateState.WEEK, "incompatible", offer.manifest());
				if (requested) { message(UpdateMessages.incompatible()); return; }
				if (newer) {
					try {
						String latest = UpdateFiles.string(latestRelease(), "tag_name");
						if (needsModNotice(isNewer(latest, installed), newer, offer.api(), TranslationPack.api()))
							message(UpdateMessages.incompatible(ACTIONS.issue(Action.INSTALL)));
					} catch (Exception e) { LOGGER.warn("暂时无法确认是否有新版 Mod，保留翻译不兼容的等待时间", e); }
				}
				return;
			}
			TranslationPack.validateManifest(offer.manifest());
			String content = UpdateFiles.string(offer.manifest(), "content_sha256");
			if (!newer && !content.equals(unpublishedTranslation)) {
				finish("translations", translationIdentity, UpdateState.HOUR, "current", offer.manifest());
				if (requested) message(UpdateMessages.current(false));
				return;
			}
			Path candidate = http.download(offer.download(), directory.resolve("translations-download.zip"), () -> enabled(requested));
			CompletableFuture<TranslationPack.Loaded> publication = new CompletableFuture<>();
			boolean accepted = Translator.reloadInBackground(() -> {
				try {
					TranslationPack.Loaded next = TranslationUpdate.install(candidate, automatic, manual, offer, () -> enabled(requested));
					unpublishedTranslation = content;
					return next;
				} catch (IOException e) { throw new java.io.UncheckedIOException(e); }
			}, publication::complete, publication::completeExceptionally);
			if (!accepted) throw new IOException("另一个翻译重载正在运行");
			TranslationPack.Loaded loaded = publication.get();
			unpublishedTranslation = null;
			finish("translations", translationIdentity, UpdateState.HOUR, "updated", offer.manifest());
			boolean overridden = loaded.source() == TranslationPack.Source.MANUAL;
			message(UpdateMessages.translationUpdated(overridden, overridden ? ACTIONS.issue(Action.REMOVE_MANUAL) : ""));
		}

		private Path installedJar() throws IOException {
			var origin = loader.getModContainer("skyzh").orElseThrow().getOrigin().getPaths();
			if (loader.isDevelopmentEnvironment() || origin.size() != 1) throw new IOException("开发或嵌套安装不能自动替换");
			Path jar = origin.getFirst().toRealPath();
			if (!jar.startsWith(game.resolve("mods").toRealPath()) || !jar.getFileName().toString().endsWith(".jar"))
				throw new IOException("SkyZH 不在当前实例的 mods 目录中");
			return jar;
		}

		/**
		 * Finds the newest build on the chosen channel and, depending on the install setting, arms the
		 * post-exit installer, keeps the download for a click, or only reports it. A click passes
		 * {@code install} and always ends armed. Manual and notify modes repeat their reminder on every
		 * check while the offer stands; a newer offer replaces the downloaded file.
		 */
		private void checkMod(boolean requested, boolean install) throws Exception {
			var pending = ModUpdate.plan(directory);
			String pendingVersion = pending.getProperty("version", "");
			if (ModUpdate.installerAlive(pending)) {
				finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", null);
				if (requested && newerBuild(pendingVersion)) message(UpdateMessages.modDownloaded(label(pendingVersion)));
				return;
			}
			SkyZHConfig.UpdateInstall mode = SkyZHConfig.get().updateInstall;
			boolean arm = install || mode == SkyZHConfig.UpdateInstall.AUTO;
			Path candidate = directory.resolve("mod-update.jar");
			if (arm && newerBuild(pendingVersion)) {
				if (Files.isRegularFile(candidate) && UpdateFiles.sha256(candidate).equals(pending.getProperty("new_sha256"))) {
					validateMod(candidate, pendingVersion);
					if (!enabled(requested)) return;
					installer = ModUpdate.arm(directory, installedJar(), pending.getProperty("new_sha256"), pendingVersion);
					finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", null);
					message(UpdateMessages.modDownloaded(label(pendingVersion)));
					return;
				}
			}
			ModUpdate.Asset asset = SkyZHConfig.get().updateChannel == SkyZHConfig.UpdateChannel.BETA ? betaAsset() : releaseAsset();
			if (asset == null) {
				if (requested) message(UpdateMessages.current(true));
				return;
			}
			String version = asset.version();
			if (mode == SkyZHConfig.UpdateInstall.NOTIFY && !install) {
				finish("mod", installed, UpdateState.HOUR, "available", releaseSummary(version));
				message(UpdateMessages.modAvailable(label(version), ACTIONS.issue(Action.INSTALL)));
				return;
			}
			if (!Files.isRegularFile(candidate) || !UpdateFiles.sha256(candidate).equals(asset.download().sha256()))
				http.download(asset.download(), candidate, () -> enabled(requested));
			validateMod(candidate, version);
			if (!enabled(requested)) return;
			if (!arm) {
				finish("mod", installed, UpdateState.HOUR, "downloaded", releaseSummary(version));
				message(UpdateMessages.modReady(label(version), ACTIONS.issue(Action.INSTALL)));
				return;
			}
			installer = ModUpdate.arm(directory, installedJar(), asset.download().sha256(), version);
			finish("mod", installed, UpdateState.HOUR, "waiting_for_exit", releaseSummary(version));
			message(UpdateMessages.modDownloaded(label(version)));
		}

		/** The newest formal release for this Minecraft, or {@code null} after recording why there is none. */
		private ModUpdate.Asset releaseAsset() throws Exception {
			JsonObject release = latestRelease();
			String latest = UpdateFiles.string(release, "tag_name");
			if (!newerBuild(latest)) {
				finish("mod", installed, UpdateState.HOUR, "current", releaseSummary(latest));
				return null;
			}
			ModUpdate.Asset asset = ModUpdate.select(release, target());
			if (asset == null) {
				finish("mod", installed, UpdateState.HOUR, "no_matching_download", releaseSummary(latest));
				throw new NoMatchingDownload();
			}
			return asset;
		}

		/** The newest test build for this Minecraft, published by Actions next to the translation bundle. */
		private ModUpdate.Asset betaAsset() throws Exception {
			JsonObject manifest = updateManifest();
			JsonObject beta = manifest.has("mod_beta") && manifest.get("mod_beta").isJsonObject() ? manifest.getAsJsonObject("mod_beta") : null;
			JsonObject asset = beta != null && beta.has("assets") && beta.getAsJsonObject("assets").has(target())
				? beta.getAsJsonObject("assets").getAsJsonObject(target()) : null;
			String version = asset == null ? "" : UpdateFiles.string(asset, "version");
			if (asset == null || !newerBuild(version)) {
				finish("mod", installed, UpdateState.HOUR, "current", releaseSummary(version));
				return null;
			}
			URI uri = URI.create(UpdateFiles.string(asset, "download_url"));
			if (!UpdateHttp.github(uri)) throw new IOException("测试版下载地址不属于项目");
			return new ModUpdate.Asset(version, new UpdateHttp.Download(uri, UpdateFiles.number(asset, "size"), UpdateFiles.string(asset, "sha256")));
		}

		private String target() {
			java.util.regex.Matcher target = TARGET.matcher(installed);
			return target.find() ? target.group(1) : "";
		}

		/** A beta build is newer by commit count; a release replaces a test build of the same version. */
		private boolean newerBuild(String version) {
			String base = BUILD_METADATA.matcher(version).replaceFirst("");
			if (isNewer(base, installed)) return true;
			if (isNewer(installed, base) || base.isEmpty() || !RELEASE_VERSION.matcher(normalizeVersion(base)).matches()) return false;
			java.util.regex.Matcher beta = BETA_BUILD.matcher(version);
			boolean installedBeta = "beta".equals(build.getProperty("channel"));
			if (!beta.find()) return installedBeta;
			return Long.parseLong(beta.group(1)) > Long.parseLong(build.getProperty("commits", "0"));
		}

		/** {@code 0.8+mc26.2.beta.501} is shown as {@code 0.8 测试版 #501}. */
		private static String label(String version) {
			java.util.regex.Matcher beta = BETA_BUILD.matcher(version);
			String base = BUILD_METADATA.matcher(version).replaceFirst("");
			return beta.find() ? base + " 测试版 #" + beta.group(1) : base;
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

	/** Already recorded with its own cooldown; only an explicit check says so in chat. */
	private static final class NoMatchingDownload extends Exception {
		NoMatchingDownload() { super(null, null, false, false); }
	}

	private static final Pattern TARGET = Pattern.compile("\\+mc(26\\.[0-9]+)");
	private static final Pattern BETA_BUILD = Pattern.compile("\\.beta\\.([0-9]{1,15})$");
	private static final Pattern BUILD_METADATA = Pattern.compile("\\+.*$");

	/** A fresh snapshot of the installed build metadata, also shown by the About category. */
	public static java.util.Properties buildInfo() {
		java.util.Properties properties = new java.util.Properties();
		try (var stream = UpdateChecker.class.getResourceAsStream("/skyzh-build.properties")) {
			if (stream != null) properties.load(stream);
		} catch (IOException e) {
			LOGGER.warn("无法读取 SkyZH 构建信息", e);
		}
		return properties;
	}

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
