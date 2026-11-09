package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.bingkkni.skyzh.text.TranslationPack;
import io.github.bingkkni.skyzh.update.ModUpdate;
import io.github.bingkkni.skyzh.update.TranslationUpdate;
import io.github.bingkkni.skyzh.update.UpdateFiles;
import io.github.bingkkni.skyzh.update.UpdateHttp;
import io.github.bingkkni.skyzh.update.UpdateState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Loopback transfers, real bundle installation and a real child-JVM exit/install cycle. No GitHub writes. */
public final class UpdateHarness {
	private static int passed;
	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "update-check-").toAbsolutePath();
		try {
			clocks(root);
			transfers(root);
			passed += io.github.bingkkni.skyzh.update.ProxyHarness.run(root);
			initialFailure();
			translations(root);
			passed += UpdateInteractionHarness.run(root);
			mods(root);
			installer(root.resolve("install"), InstalledChange.NONE, false);
			installer(root.resolve("changed-install"), InstalledChange.REPLACE, false);
			installer(root.resolve("damaged-download"), InstalledChange.NONE, true);
			installer(root.resolve("removed-install"), InstalledChange.REMOVE, false);
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
			}
		}
		System.out.println("自动更新检查通过: " + passed + " 项");
	}

	private static void clocks(Path root) throws Exception {
		Path file = root.resolve("updates.json");
		long now = 1_800_000_000_000L;
		UpdateState state = new UpdateState(file);
		check("首次检查立即允许", state.due("translations", "4:0.8", now));
		state.finish("translations", "4:0.8", now, UpdateState.WEEK, "incompatible", new JsonObject());
		check("API 不兼容一周内不检查翻译", !state.due("translations", "4:0.8", now + UpdateState.HOUR));
		check("翻译冷却不影响 Mod", state.due("mod", "0.8", now));
		state.finish("mod", "0.8", now, UpdateState.HOUR, "failed", null);
		state = new UpdateState(file);
		check("重启保留一小时重试间隔", !state.due("mod", "0.8", now + 1000));
		check("一小时后重试且无计数上限", state.due("mod", "0.8", now + UpdateState.HOUR));
		check("七天后重试", state.due("translations", "4:0.8", now + UpdateState.WEEK));
		check("本地 API 变化清除旧冷却", state.due("translations", "5:0.8", now + 1000));
		check("本地 Mod 升级清除旧冷却", state.due("translations", "4:0.9", now + 1000));
		check("时钟大幅回拨不会无限等待", state.due("mod", "0.8", now - UpdateState.WEEK));
		for (int retry = 0; retry < 12; retry++) state.finish("mod", "0.8", now + retry * UpdateState.HOUR, UpdateState.HOUR, "failed", null);
		check("多次失败后仍可继续重试", state.due("mod", "0.8", now + 12 * UpdateState.HOUR));
		state.finish("initialization", "0.8:4", now, UpdateState.HOUR, "failed", null);
		check("目录初始化失败冷却跨重启保留", !new UpdateState(file).due("initialization", "0.8:4", now + 1000));
		state.initialized("0.8:4", now + 2000);
		check("提前恢复成功后重启不再受旧初始化冷却阻挡", new UpdateState(file).due("initialization", "0.8:4", now + 2001));
		Files.writeString(file, "broken config");
		check("损坏更新记录重新检查", new UpdateState(file).due("mod", "0.8", now));
	}

	private static void transfers(Path root) throws Exception {
		byte[] data = new byte[96 * 1024];
		new java.util.Random(27).nextBytes(data);
		Path original = root.resolve("original"); Files.write(original, data);
		String digest = UpdateFiles.sha256(original);
		AtomicInteger requests = new AtomicInteger(), resumes = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/resume", exchange -> {
			int call = requests.incrementAndGet();
			String range = exchange.getRequestHeaders().getFirst("Range");
			int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
			if (range != null) resumes.incrementAndGet();
			if (start > 0) exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
			exchange.sendResponseHeaders(start > 0 ? 206 : 200, data.length - start);
			try (var output = exchange.getResponseBody()) {
				output.write(data, start, call == 1 ? 4096 : data.length - start); output.flush();
			}
			catch (IOException ignored) {} finally { exchange.close(); }
		});
		server.createContext("/full", exchange -> {
			exchange.sendResponseHeaders(200, data.length);
			try (var output = exchange.getResponseBody()) { output.write(data); } finally { exchange.close(); }
		});
		server.createContext("/bad-range", exchange -> {
			exchange.getResponseHeaders().add("Content-Range", "bytes 1-" + (data.length - 1) + "/" + data.length);
			exchange.sendResponseHeaders(206, data.length);
			exchange.getResponseBody().flush();
			exchange.close();
		});
		server.createContext("/metadata", exchange -> {
			byte[] json = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, json.length);
			try (var output = exchange.getResponseBody()) { output.write(json); } finally { exchange.close(); }
		});
		server.createContext("/slow-metadata", exchange -> {
			byte[] json = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, json.length);
			try (var output = exchange.getResponseBody()) {
				for (byte value : json) { output.write(value); output.flush(); Thread.sleep(100); }
			} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			finally { exchange.close(); }
		});
		server.createContext("/idle", exchange -> {
			exchange.sendResponseHeaders(200, data.length);
			exchange.getResponseBody().flush();
			try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			finally { exchange.close(); }
		});
		server.start();
		URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		UpdateHttp http = new UpdateHttp(HttpClient.newHttpClient(), uri -> uri.getHost().equals("127.0.0.1"), 1000);
		try {
			Path target = root.resolve("download.zip");
			var download = new UpdateHttp.Download(base.resolve("/resume"), data.length, digest);
			fails("截断下载不产生正式文件", () -> http.download(download, target, () -> true));
			check("部分数据保留用于下次续传", !Files.exists(target) && Files.size(root.resolve("download.zip.part")) == 4096);
			http.download(download, target, () -> true);
			check("断点续传取得完整原文件", resumes.get() == 1 && Arrays.equals(Files.readAllBytes(target), data));
			check("续传完成清理临时记录", !Files.exists(root.resolve("download.zip.part.json")));
			var full = new UpdateHttp.Download(base.resolve("/full"), data.length, digest);
			preparePartial(target, full, Arrays.copyOf(data, 100));
			http.download(full, target, () -> true);
			check("服务器忽略 Range 时覆盖而不拼接", Arrays.equals(Files.readAllBytes(target), data));
			var badRange = new UpdateHttp.Download(base.resolve("/bad-range"), data.length, digest);
			preparePartial(target, badRange, Arrays.copyOf(data, 100));
			check("范围校验确实拦截错误续传", fails("拒绝错误续传范围", () -> http.download(badRange, target, () -> true))
				.getMessage().contains("范围"));
			check("失败不覆盖已安装文件", UpdateFiles.sha256(target).equals(digest));
			fails("摘要错误拒绝安装", () -> http.download(new UpdateHttp.Download(base.resolve("/full"), data.length, "0".repeat(64)), target, () -> true));
			check("坏摘要清理部分文件以便重新下载", !Files.exists(root.resolve("download.zip.part")));
			fails("关闭自动更新取消下载", () -> http.download(full, target, () -> false));
			fails("无数据传输超时结束下载", () -> http.download(new UpdateHttp.Download(base.resolve("/idle"), data.length, digest), target, () -> true));
			check("JSON 元数据可读取", http.json(base.resolve("/metadata")).get("hello").getAsString().equals("world"));
			check("慢速元数据持续有进度时不误超时", http.json(base.resolve("/slow-metadata")).get("hello").getAsString().equals("world"));
			check("正式下载限定项目 GitHub 地址", UpdateHttp.github(URI.create("https://github.com/BingKKni/SkyBlockZH/releases/download/a/translations.zip")));
			check("允许项目 Pages 的固定摘要翻译包地址", UpdateHttp.github(URI.create("https://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/translations.zip")));
			for (String url : new String[] {
				"https://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/translations.zip?download=1",
				"https://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/translations.zip#fragment",
				"https://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/%74ranslations.zip",
				"http://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/translations.zip",
				"https://bingkkni.github.io/Other/skyzh-updates/" + "a".repeat(64) + "/translations.zip",
				"https://bingkkni.github.io/SkyBlockZH/skyzh-updates/../translations.zip",
				"https://other.github.io/SkyBlockZH/skyzh-updates/" + "a".repeat(64) + "/translations.zip"})
				check("拒绝非项目 Pages 更新地址 " + url, !UpdateHttp.github(URI.create(url)));
			for (String url : new String[] {"http://github.com/BingKKni/SkyBlockZH/releases/download/a/x", "https://evil.test/x", "https://github.com/other/SkyBlockZH/releases/download/a/x", "https://user@api.github.com/repos/BingKKni/SkyBlockZH/x"})
				check("拒绝外部更新地址 " + url, !UpdateHttp.github(URI.create(url)));
		} finally { server.stop(0); }
	}

	private static void preparePartial(Path target, UpdateHttp.Download download, byte[] bytes) throws Exception {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		Files.write(part, bytes);
		JsonObject json = new JsonObject(); json.addProperty("url", download.uri().toString());
		json.addProperty("size", download.size()); json.addProperty("sha256", download.sha256());
		UpdateFiles.write(part.resolveSibling(part.getFileName() + ".json"), json);
	}

	private static void initialFailure() throws Exception {
		var ready = io.github.bingkkni.skyzh.text.Translator.class.getDeclaredField("ready");
		ready.setAccessible(true);
		boolean previous = ready.getBoolean(null); ready.setBoolean(null, false);
		try {
			var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
			var error = new java.util.concurrent.atomic.AtomicReference<Exception>();
			check("首次失败加载请求可启动", requestReload(() -> { throw new IllegalStateException("broken built-in corpus"); }, queue, ignored -> {}, error));
			Runnable publication = queue.poll(10, TimeUnit.SECONDS);
			check("失败回调已提交", publication != null); publication.run();
			check("首次加载失败仍允许进入更新修复路径", error.get() != null && UpdateChecker.eligible(true, true, true,
				!io.github.bingkkni.skyzh.text.Translator.loading()));
		} finally { ready.setBoolean(null, previous); }
	}

	private static boolean requestReload(java.util.function.Supplier<TranslationPack.Loaded> supplier,
		java.util.concurrent.LinkedBlockingQueue<Runnable> queue, java.util.function.Consumer<TranslationPack.Loaded> success,
		java.util.concurrent.atomic.AtomicReference<Exception> error) throws Exception {
		var method = io.github.bingkkni.skyzh.text.Translator.class.getDeclaredMethod("requestReload", java.util.function.Supplier.class,
			java.util.concurrent.Executor.class, java.util.function.Consumer.class, java.util.function.Consumer.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, supplier, (java.util.concurrent.Executor) queue::add, success,
			(java.util.function.Consumer<Exception>) error::set);
	}

	private static void publication(Path root, Path candidate, Path installed, Path manual, boolean manualActive) throws Exception {
		if (!manualActive) Files.deleteIfExists(manual);
		TranslationPackHarness.write(candidate, TranslationPackHarness.fixture(true), meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(2)));
		var offer = offer(candidate);
		var previous = io.github.bingkkni.skyzh.text.Translator.index();
		int generation = SkyZHConfig.generation();
		var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
		var error = new java.util.concurrent.atomic.AtomicReference<Exception>();
		var loaded = new java.util.concurrent.atomic.AtomicReference<TranslationPack.Loaded>();
		check("真实安装取得重载锁 " + manualActive, requestReload(() -> {
			try { return TranslationUpdate.install(candidate, installed, manual, offer, () -> true); }
			catch (IOException e) { throw new java.io.UncheckedIOException(e); }
		}, queue, loaded::set, error));
		Runnable publication = queue.poll(10, TimeUnit.SECONDS);
		check("安装后的索引等待客户端线程发布 " + manualActive, publication != null
			&& io.github.bingkkni.skyzh.text.Translator.index() == previous);
		publication.run();
		check("安装和索引代际切换一起完成 " + manualActive, error.get() == null && loaded.get() != null
			&& io.github.bingkkni.skyzh.text.Translator.index() == loaded.get().index() && SkyZHConfig.generation() > generation);
		check("发布后保持正确手动优先级 " + manualActive, loaded.get().source()
			== (manualActive ? TranslationPack.Source.MANUAL : TranslationPack.Source.AUTOMATIC));
	}

	private static TranslationUpdate.Offer offer(Path candidate) throws Exception {
		JsonObject manifest = TranslationPack.read(candidate).manifest().deepCopy();
		manifest.addProperty("update_format", 1);
		manifest.addProperty("zip_size", Files.size(candidate));
		manifest.addProperty("zip_sha256", UpdateFiles.sha256(candidate));
		manifest.addProperty("download_url", "https://bingkkni.github.io/SkyBlockZH/skyzh-updates/" + UpdateFiles.sha256(candidate) + "/translations.zip");
		return TranslationUpdate.offer(manifest);
	}

	private static void translations(Path root) throws Exception {
		Path installed = root.resolve("automatic.zip"), candidate = root.resolve("candidate.zip"), manual = root.resolve("manual.zip");
		JsonObject builtin = TranslationPack.builtinManifest();
		TranslationPackHarness.write(installed, TranslationPackHarness.fixture(false),
			meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(-2)));
		TranslationPackHarness.write(candidate, TranslationPackHarness.fixture(true),
			meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(-1)));
		check("旧自动包仍留在磁盘也以内置包作更新基准", TranslationUpdate.baseline(installed).equals(builtin)
			&& Files.isRegularFile(installed));
		check("远端虽比旧自动包新但比内置包旧时不下载", !TranslationUpdate.newer(offer(candidate).manifest(), TranslationUpdate.baseline(installed)));
		TranslationPackHarness.write(installed, TranslationPackHarness.fixture(false),
			meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(0)));
		check("自动包与内置包同时间时以内置包为基准", TranslationUpdate.baseline(installed).equals(builtin));

		TranslationPackHarness.write(installed, TranslationPackHarness.fixture(false),
			meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(1)));
		TranslationPackHarness.write(candidate, TranslationPackHarness.fixture(true),
			meta -> meta.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(2)));
		var offer = offer(candidate);
		check("较新自动包继续作为更新基准", TranslationUpdate.baseline(installed).equals(TranslationPack.read(installed).manifest()));
		check("更新比对使用自动包日期和摘要", TranslationUpdate.newer(offer.manifest(), TranslationUpdate.baseline(installed)));
		String oldHash = UpdateFiles.sha256(installed);
		fails("关闭开关后不安装已下载包", () -> TranslationUpdate.install(candidate, installed, manual, offer, () -> false));
		check("取消保留旧正式包", UpdateFiles.sha256(installed).equals(oldHash));
		JsonObject incorrect = offer.manifest().deepCopy(); incorrect.addProperty("revision", "b".repeat(40));
		var wrong = new TranslationUpdate.Offer(incorrect, offer.download());
		fails("ZIP 与更新清单不一致时不安装", () -> TranslationUpdate.install(candidate, installed, manual, wrong, () -> true));
		check("清单不一致保留旧包", UpdateFiles.sha256(installed).equals(oldHash));
		TranslationPackHarness.write(manual, TranslationPackHarness.fixture(false), ignored -> {});
		var next = TranslationUpdate.install(candidate, installed, manual, offer, () -> true);
		check("自动包安装后手动包仍然优先", next.source() == TranslationPack.Source.MANUAL);
		check("安装后版本比较使用较新自动包", !TranslationUpdate.newer(offer.manifest(), TranslationUpdate.baseline(installed)));
		check("重启仍加载已安装自动包", TranslationPack.load(root.resolve("missing-manual"), installed, null).source() == TranslationPack.Source.AUTOMATIC);
		JsonObject repacked = offer.manifest().deepCopy(); repacked.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(3));
		check("相同内容重新打包不重复更新", !TranslationUpdate.newer(repacked, offer.manifest()));
		JsonObject older = offer.manifest().deepCopy(); older.addProperty("content_sha256", "e".repeat(64));
		older.addProperty("build_at_utc8", TranslationPackHarness.builtinTime(1));
		check("拒绝日期更旧的不同内容", !TranslationUpdate.newer(older, offer.manifest()));
		publication(root, candidate, installed, manual, true);
		publication(root, candidate, installed, manual, false);
		Files.writeString(installed, "damaged automatic bundle");
		check("自动包损坏时使用内置版本作比较基准", TranslationUpdate.baseline(installed).equals(TranslationPack.builtinManifest()));
		TranslationPackHarness.write(installed, TranslationPackHarness.fixture(true), meta -> meta.addProperty("translation_api", TranslationPack.api() + 1));
		check("自动包 API 不兼容时使用内置比较基准", TranslationUpdate.baseline(installed).equals(TranslationPack.builtinManifest()));
		TranslationPackHarness.write(candidate, TranslationPackHarness.fixture(true), meta -> meta.addProperty("translation_api", TranslationPack.api() + 1));
		fails("不兼容 API 不能进入安装", () -> TranslationUpdate.install(candidate, installed, manual, offer, () -> true));
	}

	private static void mods(Path root) throws Exception {
		JsonObject release = new JsonObject(); release.addProperty("tag_name", "0.9");
		var assets = new com.google.gson.JsonArray(); release.add("assets", assets);
		for (String target : new String[] {"26.1", "26.2", "26.3"}) {
			JsonObject asset = new JsonObject(); asset.addProperty("name", "SkyBlockZH-0.9-Beta-Fabric-" + target + ".jar");
			asset.addProperty("size", 1024); asset.addProperty("digest", "sha256:" + "a".repeat(64));
			asset.addProperty("browser_download_url", "https://github.com/BingKKni/SkyBlockZH/releases/download/0.9/" + target + ".jar"); assets.add(asset);
		}
		check("自动下载只选对应 Minecraft 附件", ModUpdate.select(release, "26.2").download().uri().toString().endsWith("/26.2.jar"));
		assets.get(1).getAsJsonObject().remove("digest");
		fails("缺少可信摘要不自动安装 Mod", () -> ModUpdate.select(release, "26.2"));
		Path jar = root.resolve("test-mod.jar");
		modJar(jar, "skyzh", "0.9+mc26.2", ">=26.2 <26.3");
		ModUpdate.validate(jar, "v0.9", "26.2", "0.19.3");
		check("正确 Mod 版本和游戏依赖通过", true);
		fails("拒绝不兼容 Minecraft", () -> ModUpdate.validate(jar, "0.9", "26.1", "0.19.3"));
		fails("拒绝错误发布版本", () -> ModUpdate.validate(jar, "1.0", "26.2", "0.19.3"));
		modJar(jar, "other", "0.9+mc26.2", ">=26.2 <26.3");
		fails("拒绝非 SkyZH Mod", () -> ModUpdate.validate(jar, "0.9", "26.2", "0.19.3"));
	}

	private static void modJar(Path jar, String id, String version, String minecraft) throws Exception {
		String text = "{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"depends\":{\"minecraft\":\"" + minecraft + "\",\"fabricloader\":\">=0.19.3\",\"java\":\">=25\"}}";
		try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json")); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
		}
	}

	private enum InstalledChange { NONE, REPLACE, REMOVE }

	private static void installer(Path directory, InstalledChange change, boolean corruptDownload) throws Exception {
		boolean manualReplacement = change == InstalledChange.REPLACE;
		boolean removed = change == InstalledChange.REMOVE;
		Files.createDirectories(directory);
		Path jar = directory.resolve("mods/skyzh.jar"); Files.createDirectories(jar.getParent());
		Files.writeString(jar, "old installed mod");
		Files.writeString(directory.resolve("mod-update.jar"), "new downloaded mod");
		Files.writeString(directory.resolve("previous-mod.jar"), "earlier backup");
		if (manualReplacement) {
			var stale = new java.util.Properties();
			stale.setProperty("installed", jar.toAbsolutePath().toString());
			stale.setProperty("old_sha256", "0".repeat(64));
			stale.setProperty("new_sha256", "1".repeat(64));
			stale.setProperty("status", "installed");
			try (var output = Files.newOutputStream(directory.resolve("mod-install.properties"))) { stale.store(output, "Previous completed installation"); }
		}
		Path arguments = directory.resolve("parent.args");
		String contents = "-cp\n\"" + System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"")
			+ "\"\n" + InstallerParent.class.getName() + "\n\"" + directory.toString().replace("\\", "\\\\") + "\"\n";
		Files.writeString(arguments, contents);
		Process parent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
			.redirectErrorStream(true).redirectOutput(directory.resolve("parent.log").toFile()).start();
		long installerPid = 0;
		try {
			waitUntil(() -> Files.exists(directory.resolve("parent.ready")) || !parent.isAlive(), 15_000);
			String scenario = directory.getFileName().toString();
			check("真实辅助进程确认已准备 " + scenario, parent.isAlive() && Files.exists(directory.resolve("parent.ready")));
			installerPid = Long.parseLong(ModUpdate.plan(directory).getProperty("installer_pid"));
			check("游戏退出前 JAR 完全不变 " + scenario, Files.readString(jar).equals("old installed mod"));
			if (manualReplacement) {
				check("旧安装记录不再阻止手动替换后的更新", ModUpdate.plan(directory).getProperty("old_sha256").equals(UpdateFiles.sha256(jar)));
				Files.writeString(jar, "user replaced mod");
			}
			if (removed) Files.delete(jar);
			if (corruptDownload) Files.writeString(directory.resolve("mod-update.jar"), "corrupted download");
			Files.writeString(directory.resolve("parent.exit"), "exit");
			check("父 JVM 正常退出 " + scenario, parent.waitFor(10, TimeUnit.SECONDS) && parent.exitValue() == 0);
			waitUntil(() -> {
				try { String status = ModUpdate.plan(directory).getProperty("status"); return status.equals("installed") || status.equals("failed"); }
				catch (Exception e) { return false; }
			}, 10_000);
			check(removed ? "等待期间删除目标不复活旧备份" : corruptDownload ? "下载包被改动时保留当前 Mod" : "允许覆盖手动替换的 JAR，保留原文件名 " + scenario,
				removed ? !Files.exists(jar) : Files.readString(jar).equals(corruptDownload ? "old installed mod" : "new downloaded mod"));
			check("安装结果正确 " + scenario, ModUpdate.plan(directory).getProperty("status").equals(corruptDownload || removed ? "failed" : "installed"));
			if (removed) check("缺失目标在备份阶段失败且清理 staging", ModUpdate.plan(directory).getProperty("error", "").contains("NoSuchFileException")
				&& !Files.exists(jar.resolveSibling(jar.getFileName() + ".skyzh-new")));
			check("备份对应实际被替换的文件 " + scenario, Files.readString(directory.resolve("previous-mod.jar"))
				.equals(corruptDownload || removed ? "earlier backup" : manualReplacement ? "user replaced mod" : "old installed mod"));
		} finally {
			if (parent.isAlive()) parent.destroyForcibly().waitFor();
			if (installerPid != 0) {
				var process = ProcessHandle.of(installerPid).orElse(null);
				if (process != null) try { process.onExit().get(5, TimeUnit.SECONDS); } catch (Exception e) { process.destroyForcibly(); }
			}
		}
	}

	public static final class InstallerParent {
		public static void main(String[] args) throws Exception {
			Path directory = Path.of(args[0]);
			ModUpdate.arm(directory, directory.resolve("mods/skyzh.jar"), UpdateFiles.sha256(directory.resolve("mod-update.jar")), "0.9");
			Files.writeString(directory.resolve("parent.ready"), "ready");
			waitUntil(() -> Files.exists(directory.resolve("parent.exit")), 25_000);
			System.exit(0);
		}
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition, long milliseconds) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) throw new AssertionError("等待条件超时");
			Thread.sleep(25);
		}
	}
	private static Exception fails(String label, Checked action) throws Exception {
		Exception failure = null;
		try { action.run(); } catch (Exception e) { failure = e; }
		check(label, failure != null);
		return failure;
	}
	private static void check(String label, boolean success) {
		if (!success) throw new AssertionError(label);
		passed++; System.out.println("  ✓ " + label);
	}
	@FunctionalInterface private interface Checked { void run() throws Exception; }
}
