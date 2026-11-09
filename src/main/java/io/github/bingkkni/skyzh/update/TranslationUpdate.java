package io.github.bingkkni.skyzh.update;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.TranslationPack;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;

/** Validates an entire candidate before replacing the installed automatic bundle. */
public final class TranslationUpdate {
	public record Offer(JsonObject manifest, UpdateHttp.Download download) {
		public Offer {
			if (UpdateFiles.number(manifest, "update_format") != 1 || UpdateFiles.number(manifest, "format_version") != TranslationPack.FORMAT)
				throw new IllegalArgumentException("不支持的更新信息格式");
			long api = UpdateFiles.number(manifest, "translation_api");
			if (api > Integer.MAX_VALUE || !UpdateFiles.string(manifest, "revision").matches("[0-9a-f]{40}|[0-9a-f]{64}")
				|| !UpdateFiles.string(manifest, "content_sha256").matches("[0-9a-f]{64}")
				|| !OffsetDateTime.parse(UpdateFiles.string(manifest, "build_at_utc8")).getOffset().equals(ZoneOffset.ofHours(8)))
				throw new IllegalArgumentException("无效的翻译更新信息");
		}
		public int api() { return Math.toIntExact(UpdateFiles.number(manifest, "translation_api")); }
	}

	private TranslationUpdate() {}

	public static Offer offer(JsonObject json) {
		return new Offer(json, new UpdateHttp.Download(URI.create(UpdateFiles.string(json, "download_url")),
			UpdateFiles.number(json, "zip_size"), UpdateFiles.string(json, "zip_sha256")));
	}

	public static boolean newer(JsonObject candidate, JsonObject installed) {
		return TranslationPack.newer(candidate, installed);
	}

	/** Manual bundles never affect the update baseline, and stale automatic files need not be deleted first. */
	public static JsonObject baseline(Path automatic) throws IOException {
		JsonObject downloaded = null;
		if (Files.exists(automatic)) {
			try { downloaded = TranslationPack.compile(automatic, TranslationPack.Source.AUTOMATIC).manifest(); }
			catch (IOException | RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger("SkyZH").warn("自动翻译包不能作为版本比较基准，使用内置版本", e);
			}
		}
		JsonObject builtin;
		try { builtin = TranslationPack.builtinManifest(); }
		catch (IOException | RuntimeException e) {
			if (downloaded == null) throw e;
			org.slf4j.LoggerFactory.getLogger("SkyZH").warn("内置翻译版本信息不可用，使用已校验的自动包作比较基准", e);
			return downloaded;
		}
		return downloaded != null && newer(downloaded, builtin) ? downloaded : builtin;
	}

	/** Called under Translator's reload lock. No other reload can publish an older disk snapshot. */
	public static TranslationPack.Loaded install(Path candidate, Path automatic, Path manual, Offer offer,
		BooleanSupplier enabled) throws IOException {
		TranslationPack.Loaded next = TranslationPack.compile(candidate, TranslationPack.Source.AUTOMATIC);
		for (String key : new String[] {"format_version", "translation_api", "revision", "build_at_utc8", "content_sha256"}) {
			if (!next.manifest().get(key).equals(offer.manifest().get(key))) throw new IOException("更新清单与翻译包不一致: " + key);
		}
		TranslationPack.Loaded selected = next;
		if (Files.exists(manual)) {
			try { selected = TranslationPack.compile(manual, TranslationPack.Source.MANUAL); }
			catch (IOException | RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger("SkyZH").warn("手动翻译包不可用，本次使用已校验的自动翻译包", e);
			}
		}
		if (!enabled.getAsBoolean()) throw new IOException("更新已关闭");
		Files.createDirectories(automatic.getParent());
		UpdateFiles.move(candidate, automatic);
		return selected;
	}
}
