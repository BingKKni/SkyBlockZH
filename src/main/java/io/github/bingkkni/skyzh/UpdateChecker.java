package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.platform.ClientGui;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks the project's GitHub Releases page once per eligible client startup. */
public final class UpdateChecker {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final URI LATEST_RELEASE_URI = URI.create(
		"https://api.github.com/repos/BingKKni/SkyBlockZH/releases/latest"
	);
	private static final URI PROJECT_URI = URI.create("https://github.com/BingKKni/SkyBlockZH");
	private static final Pattern RELEASE_VERSION = Pattern.compile(
		"\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
	);
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final AtomicBoolean STARTED = new AtomicBoolean();

	private UpdateChecker() {
	}

	/** Starts a non-blocking check only when both settings permit it. */
	public static void checkAtStartup() {
		SkyZHConfig config = SkyZHConfig.get();

		if (!config.enabled || !config.updateCheck || !STARTED.compareAndSet(false, true)) {
			return;
		}

		String installedVersion = installedVersion();
		Thread.startVirtualThread(() -> check(installedVersion));
	}

	private static String installedVersion() {
		return FabricLoader.getInstance().getModContainer(SkyZH.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static void check(String installedVersion) {
		try {
			HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_URI)
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "SkyZH-Update-Checker")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() != 200) {
				LOGGER.debug("SkyZH 更新检查返回 HTTP {}。", response.statusCode());
				return;
			}

			JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!release.has("tag_name") || !release.get("tag_name").isJsonPrimitive()) {
				LOGGER.debug("SkyZH 更新检查未收到 Releases 的 tag_name。");
				return;
			}

			String latestVersion = release.get("tag_name").getAsString();
			if (isNewer(latestVersion, installedVersion)) {
				announce(installedVersion, latestVersion, releaseUri(release));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.debug("SkyZH 更新检查被中断。", e);
		} catch (Exception e) {
			// A failed convenience request must never affect startup or fill the chat with network errors.
			LOGGER.debug("SkyZH 更新检查失败：{}", e.toString());
		}
	}

	private static URI releaseUri(JsonObject release) {
		if (release.has("html_url") && release.get("html_url").isJsonPrimitive()) {
			try {
				return URI.create(release.get("html_url").getAsString());
			} catch (IllegalArgumentException ignored) {
				// Fall back to the stable project address below.
			}
		}

		return PROJECT_URI;
	}

	private static void announce(String installedVersion, String latestVersion, URI releaseUri) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null) {
			return;
		}

		minecraft.execute(() -> {
			SkyZHConfig config = SkyZHConfig.get();
			if (!config.enabled || !config.updateCheck) {
				return;
			}

			Component github = Component.literal("[Github]").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(releaseUri))
			);
			Component message = Component.literal(
				"§b[SkyZH] §e发现新版本 " + latestVersion + "（当前 " + displayVersion(installedVersion) + "） "
			).append(github);
			ClientGui.chat(minecraft, message);
		});
	}

	/** Uses Fabric's version parser so 0.5 and 0.5.0 compare as equal and prereleases sort correctly. */
	static boolean isNewer(String available, String installed) {
		try {
			String candidate = normalizeVersion(available);
			String current = normalizeVersion(installed);
			if (!RELEASE_VERSION.matcher(candidate).matches() || !RELEASE_VERSION.matcher(current).matches()) {
				return false;
			}

			return Version.parse(candidate).compareTo(Version.parse(current)) > 0;
		} catch (Exception ignored) {
			return false;
		}
	}

	private static String normalizeVersion(String version) {
		String normalized = version == null ? "" : version.trim();
		return normalized.startsWith("v") || normalized.startsWith("V") ? normalized.substring(1) : normalized;
	}

	private static String displayVersion(String version) {
		int buildMetadata = version.indexOf('+');
		return buildMetadata >= 0 ? version.substring(0, buildMetadata) : version;
	}
}
