package io.github.bingkkni.skyzh.update;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.TranslationPack;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Small metadata requests and bounded resumable downloads, with an idle timeout rather than a short total deadline. */
public final class UpdateHttp {
	private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "SkyZH-download-timeout"); thread.setDaemon(true); return thread;
	});
	private final HttpClient client;
	private final Predicate<URI> allowed;
	private final long idleMillis;

	public record Download(URI uri, long size, String sha256) {
		public Download {
			if (size <= 0 || size > 128L * 1024 * 1024 || !sha256.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("无效的下载大小或摘要");
		}
	}

	public UpdateHttp() {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build(),
			UpdateHttp::github, 60_000);
	}

	/** Injected transport policy allows deterministic loopback-server tests. */
	public UpdateHttp(HttpClient client, Predicate<URI> allowed, long idleMillis) {
		this.client = client; this.allowed = allowed; this.idleMillis = idleMillis;
	}

	public static boolean github(URI uri) {
		if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
		String path = uri.getPath();
		return ("api.github.com".equals(uri.getHost()) && path.startsWith("/repos/BingKKni/SkyBlockZH/"))
			|| ("github.com".equals(uri.getHost()) && path.startsWith("/BingKKni/SkyBlockZH/releases/download/"))
			|| ("bingkkni.github.io".equals(uri.getHost()) && uri.getRawQuery() == null && uri.getRawFragment() == null
				&& (uri.getRawPath().matches("/SkyBlockZH/skyzh-updates/[0-9a-f]{64}/translations\\.zip")
					|| uri.getRawPath().matches("/SkyBlockZH/skyzh-updates/mod/[0-9a-f]{64}/SkyBlockZH-[0-9A-Za-z.+-]+\\.jar")));
	}

	private HttpRequest.Builder request(URI uri) throws IOException {
		if (!allowed.test(uri)) throw new IOException("更新地址不属于项目的 GitHub 发布位置");
		return HttpRequest.newBuilder(uri).header("User-Agent", "SkyZH-Updater")
			.header("Accept", "application/vnd.github+json").timeout(Duration.ofSeconds(30));
	}

	public JsonObject json(URI uri) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = client.send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream input = response.body()) {
			if (response.statusCode() != 200) throw new IOException("GitHub HTTP " + response.statusCode());
			AtomicLong progress = new AtomicLong(System.nanoTime());
			var guard = guard(input, progress);
			try {
				var bytes = new java.io.ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				for (int count; (count = input.read(buffer)) >= 0;) {
					if (bytes.size() + count > 1024 * 1024) throw new IOException("GitHub 更新信息过大");
					bytes.write(buffer, 0, count);
					progress.set(System.nanoTime());
				}
				return TranslationPack.parse(bytes.toByteArray(), "GitHub 更新信息");
			} finally { guard.cancel(false); }
		}
	}

	private java.util.concurrent.ScheduledFuture<?> guard(InputStream input, AtomicLong progress) {
		return WATCHDOG.scheduleAtFixedRate(() -> {
			if (System.nanoTime() - progress.get() >= TimeUnit.MILLISECONDS.toNanos(idleMillis)) {
				try { input.close(); } catch (IOException ignored) {}
			}
		}, idleMillis, Math.max(10, idleMillis / 2), TimeUnit.MILLISECONDS);
	}

	public Path download(Download download, Path target, BooleanSupplier enabled) throws IOException, InterruptedException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		Path info = partial.resolveSibling(partial.getFileName() + ".json");
		JsonObject identity = new JsonObject();
		identity.addProperty("url", download.uri().toString());
		identity.addProperty("size", download.size());
		identity.addProperty("sha256", download.sha256());
		boolean same = false;
		try { same = Files.isRegularFile(info) && UpdateFiles.read(info).equals(identity); }
		catch (IOException | RuntimeException ignored) {}
		if (!same || !Files.isRegularFile(partial) || Files.size(partial) > download.size()) Files.deleteIfExists(partial);
		UpdateFiles.write(info, identity);
		long offset = Files.exists(partial) ? Files.size(partial) : 0;
		if (offset < download.size()) {
			if (!enabled.getAsBoolean()) throw new IOException("更新已关闭");
			var builder = request(download.uri()).setHeader("Accept", "application/octet-stream");
			if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
			HttpResponse<InputStream> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream input = response.body()) {
				if (response.statusCode() == 200) offset = 0; // Range ignored: restart, never append a full response.
				else if (response.statusCode() == 206) {
					var range = RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
					if (!range.matches() || Long.parseLong(range.group(1)) != offset
						|| Long.parseLong(range.group(2)) != download.size() - 1 || Long.parseLong(range.group(3)) != download.size())
						throw new IOException("断点续传范围不符");
				} else throw new IOException("GitHub 下载 HTTP " + response.statusCode());
				long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
				if (contentLength != -1 && contentLength != download.size() - offset) throw new IOException("下载大小声明不符");
				AtomicLong progress = new AtomicLong(System.nanoTime());
				var guard = guard(input, progress);
				try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					offset == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
					byte[] bytes = new byte[64 * 1024];
					long count = offset;
					for (int read; (read = input.read(bytes)) >= 0;) {
						if (!enabled.getAsBoolean()) throw new IOException("更新已关闭");
						if ((count += read) > download.size()) throw new IOException("下载超过声明大小");
						output.write(bytes, 0, read);
						progress.set(System.nanoTime());
					}
					if (count != download.size()) throw new IOException("下载尚未完成");
				} finally { guard.cancel(false); }
			}
		}
		if (!enabled.getAsBoolean()) throw new IOException("更新已关闭");
		if (!UpdateFiles.sha256(partial).equals(download.sha256())) {
			Files.deleteIfExists(partial); Files.deleteIfExists(info);
			throw new IOException("下载文件 SHA-256 校验失败");
		}
		UpdateFiles.move(partial, target);
		Files.deleteIfExists(info);
		return target;
	}
}
