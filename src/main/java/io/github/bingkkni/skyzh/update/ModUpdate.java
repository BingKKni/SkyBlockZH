package io.github.bingkkni.skyzh.update;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.TranslationPack;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** Selects a matching release asset and arms the independent, post-exit installer. */
public final class ModUpdate {
	public record Asset(String version, UpdateHttp.Download download) {}
	private ModUpdate() {}

	public static Asset select(JsonObject release, String target) throws IOException {
		String version = UpdateFiles.string(release, "tag_name");
		if (!target.matches("26\\.[123]")) throw new IOException("无法识别当前 Minecraft 构建目标");
		Asset selected = null;
		if (!release.has("assets") || !release.get("assets").isJsonArray()) throw new IOException("GitHub 发布缺少附件");
		for (var value : release.getAsJsonArray("assets")) {
			JsonObject asset = value.getAsJsonObject();
			String name = UpdateFiles.string(asset, "name");
			if (!name.startsWith("SkyBlockZH-") || !name.endsWith("-Fabric-" + target + ".jar")) continue;
			if (selected != null) throw new IOException("存在多个相同 Minecraft 目标的附件");
			String digest = UpdateFiles.string(asset, "digest");
			if (!digest.startsWith("sha256:")) throw new IOException("GitHub 附件缺少 SHA-256 摘要");
			URI uri = URI.create(UpdateFiles.string(asset, "browser_download_url"));
			if (!UpdateHttp.github(uri)) throw new IOException("Mod 下载地址不属于项目");
			selected = new Asset(version, new UpdateHttp.Download(uri, UpdateFiles.number(asset, "size"), digest.substring(7)));
		}
		return selected;
	}

	public static void validate(Path jar, String version, String minecraft, String loader) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.getEntry("fabric.mod.json");
			if (entry == null) throw new IOException("下载文件不是 Fabric Mod");
			JsonObject metadata;
			try (var input = zip.getInputStream(entry)) {
				byte[] bytes = input.readNBytes(64 * 1024 + 1);
				if (bytes.length > 64 * 1024) throw new IOException("Mod 声明过大");
				metadata = TranslationPack.parse(bytes, "fabric.mod.json");
			}
			String actual = UpdateFiles.string(metadata, "version");
			String requested = version.replaceFirst("^[vV]", "");
			if (!UpdateFiles.string(metadata, "id").equals("skyzh") || !Version.parse(actual).equals(Version.parse(requested))) {
				// Fabric's equality includes metadata; version ordering deliberately ignores +mc26.x.
				if (!UpdateFiles.string(metadata, "id").equals("skyzh")
					|| Version.parse(actual).compareTo(Version.parse(requested)) != 0) throw new IOException("Mod 身份或版本不符");
			}
			JsonObject depends = metadata.getAsJsonObject("depends");
			if (depends == null || !matches(depends, "minecraft", minecraft) || !matches(depends, "fabricloader", loader)
				|| !matches(depends, "java", Integer.toString(Runtime.version().feature()))) throw new IOException("新 Mod 与当前游戏或加载器不兼容");
		}
	}

	private static boolean matches(JsonObject depends, String key, String version) throws Exception {
		var value = depends.get(key);
		if (value == null) return false;
		if (value.isJsonArray()) {
			for (var alternative : value.getAsJsonArray()) if (VersionPredicate.parse(alternative.getAsString()).test(Version.parse(version))) return true;
			return false;
		}
		return VersionPredicate.parse(value.getAsString()).test(Version.parse(version));
	}

	public static Properties plan(Path directory) throws IOException {
		Properties properties = new Properties();
		Path file = directory.resolve("mod-install.properties");
		if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
		return properties;
	}

	public static boolean installerAlive(Properties properties) {
		try {
			return ProcessHandle.of(Long.parseLong(properties.getProperty("installer_pid", "0")))
				.filter(ProcessHandle::isAlive).filter(process -> process.info().startInstant().orElseThrow().toString()
					.equals(properties.getProperty("installer_start"))).isPresent();
		} catch (RuntimeException e) { return false; }
	}

	public static Process arm(Path directory, Path installed, String newHash, String version) throws Exception {
		Properties previous = plan(directory);
		if (installerAlive(previous)) throw new IOException("另一更新安装进程尚未完成");
		String currentHash = UpdateFiles.sha256(installed);
		if (previous.containsKey("installed") && Path.of(previous.getProperty("installed")).toAbsolutePath().normalize()
			.equals(installed.toAbsolutePath().normalize()) && previous.containsKey("old_sha256")
			&& !currentHash.equals(previous.getProperty("old_sha256")) && !currentHash.equals(previous.getProperty("new_sha256")))
			throw new IOException("用户已更换原 Mod，取消自动替换；手动处理后可移除 mod-install.properties 重新启用安装");
		Path helper = directory.resolve("skyzh-updater.jar");
		try (var input = ModUpdate.class.getResourceAsStream("/assets/skyzh/skyzh-updater.jar")) {
			if (input == null) throw new IOException("缺少更新安装程序");
			Path temporary = directory.resolve("skyzh-updater.jar.tmp");
			Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			UpdateFiles.move(temporary, helper);
		}
		Path planFile = directory.resolve("mod-install.properties");
		Properties properties = new Properties();
		properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
		properties.setProperty("process_start", ProcessHandle.current().info().startInstant().orElseThrow().toString());
		properties.setProperty("installed", installed.toAbsolutePath().toString());
		properties.setProperty("old_sha256", currentHash);
		properties.setProperty("new_sha256", newHash);
		properties.setProperty("version", version);
		properties.setProperty("status", "prepared");
		storePlan(planFile, properties);
		Path ready = directory.resolve("installer.ready");
		Files.deleteIfExists(ready);
		Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
		Process process = new ProcessBuilder(java.toString(), "-jar", helper.toString(), planFile.toString())
			.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("installer.log").toFile())).start();
		boolean armed = false;
		try {
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (process.isAlive() && System.nanoTime() < deadline) {
				if (Files.isRegularFile(ready) && Files.readString(ready).equals(Long.toString(ProcessHandle.current().pid()))) {
					Properties confirmed = plan(directory);
					if (!Long.toString(process.pid()).equals(confirmed.getProperty("installer_pid")))
						throw new IOException("安装进程身份不符");
					armed = true;
					return process;
				}
				Thread.sleep(50);
			}
			throw new IOException("更新安装程序未能启动");
		} finally { if (!armed) process.destroyForcibly(); }
	}

	private static void storePlan(Path file, Properties properties) throws IOException {
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (var output = Files.newOutputStream(temporary)) { properties.store(output, "SkyZH post-exit installer"); }
		UpdateFiles.move(temporary, file);
	}
}
