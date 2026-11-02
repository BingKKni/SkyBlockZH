package io.github.bingkkni.skyzh.update;

import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.TranslationPack;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared bounded JSON and atomic file operations; no game state or networking. */
public final class UpdateFiles {
	private UpdateFiles() {}

	public static void move(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
	}

	public static void write(Path target, JsonObject json) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.writeString(temporary, json.toString(), java.nio.charset.StandardCharsets.UTF_8);
			move(temporary, target);
		} finally { Files.deleteIfExists(temporary); }
	}

	public static JsonObject read(Path file) throws IOException {
		if (Files.size(file) > 1024 * 1024) throw new IOException("更新信息文件过大");
		return TranslationPack.parse(Files.readAllBytes(file), file.getFileName().toString());
	}

	public static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var stream = Files.newInputStream(file)) {
				byte[] bytes = new byte[64 * 1024];
				for (int count; (count = stream.read(bytes)) >= 0;) digest.update(bytes, 0, count);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}

	public static String string(JsonObject json, String key) {
		var value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
			throw new IllegalArgumentException("缺少字符串字段: " + key);
		return value.getAsString();
	}

	public static long number(JsonObject json, String key) {
		var value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
			|| !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException("字段必须为非负整数: " + key);
		return value.getAsLong();
	}
}
