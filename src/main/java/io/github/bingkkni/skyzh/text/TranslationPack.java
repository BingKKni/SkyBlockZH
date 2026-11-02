package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** A full, versioned corpus snapshot. ZIPs are read and closed, never extracted into the game directory. */
public final class TranslationPack {
	public static final String DIRECTORY = "skyzh-translations";
	public static final String FILE_NAME = "translations.zip";
	public static final String UPDATE_DIRECTORY = "skyzh-updates";
	public static final int FORMAT = 1;
	private static final int MAX_FILES = 20_000;
	private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
	private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
	private static final int API = readApi();

	public enum Source {
		MANUAL("手动安装的翻译包"), AUTOMATIC("自动更新的翻译包"), BUILTIN("Mod 自带的翻译");
		private final String label;
		Source(String label) { this.label = label; }
		public String label() { return label; }
	}
	public record Loaded(TranslationIndex index, Source source, JsonObject manifest, String warning) {
		public boolean external() { return source != Source.BUILTIN; }
		public String revision() { return manifest.has("revision") ? string(manifest, "revision") : ""; }
	}
	public record Content(Map<String, JsonObject> files, JsonObject manifest) {}

	private TranslationPack() {}

	public static int api() { return API; }

	private static int readApi() {
		try (var stream = TranslationPack.class.getResourceAsStream("/skyzh-translations.properties")) {
			if (stream == null) throw new IllegalStateException("缺少翻译协议声明");
			Properties properties = new Properties();
			properties.load(stream);
			return Integer.parseInt(properties.getProperty("translation_api"));
		} catch (IOException e) {
			throw new IllegalStateException("读取翻译协议声明失败", e);
		}
	}

	public static Loaded load() {
		var loader = FabricLoader.getInstance();
		var mod = loader.getModContainer("skyzh").orElseThrow(() -> new IllegalStateException("找不到 SkyZH Mod 容器"));
		Path directory = loader.getGameDir().resolve(DIRECTORY);
		try { Files.createDirectories(directory); }
		catch (IOException e) { LoggerFactory.getLogger("SkyZH").warn("无法创建翻译包目录: {}", directory, e); }
		Path builtin = mod.findPath(TranslationLoader.ROOT).orElse(null);
		return load(directory.resolve(FILE_NAME), loader.getGameDir().resolve(UPDATE_DIRECTORY).resolve(FILE_NAME), builtin);
	}

	/** Run only inside the existing background reload lock, after a local uninstall-button click. */
	public static Loaded removeManualAndLoad() {
		var loader = FabricLoader.getInstance();
		var mod = loader.getModContainer("skyzh").orElseThrow();
		Path game = loader.getGameDir();
		try {
			return removeManualAndLoad(game.resolve(DIRECTORY).resolve(FILE_NAME),
				game.resolve(UPDATE_DIRECTORY).resolve(FILE_NAME), mod.findPath(TranslationLoader.ROOT).orElse(null));
		} catch (IOException e) { throw new java.io.UncheckedIOException(e); }
	}

	public static Loaded removeManualAndLoad(Path manual, Path automatic, Path builtin) throws IOException {
		if (Files.isDirectory(manual, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("手动翻译包路径不是文件");
		Files.deleteIfExists(manual);
		return load(manual, automatic, builtin);
	}

	public static JsonObject builtinManifest() throws IOException {
		try (var stream = TranslationPack.class.getResourceAsStream("/skyzh-builtin-translations.json")) {
			if (stream == null) throw new IOException("缺少内置翻译版本信息");
			JsonObject manifest = parse(stream.readNBytes(64 * 1024), "内置翻译版本信息");
			validateManifest(manifest);
			return manifest;
		}
	}

	public static Loaded compile(Path archive, Source source) throws IOException {
		Content content = read(archive);
		return new Loaded(TranslationLoader.compile(content.files()), source, content.manifest(), "");
	}

	/** Also exercised offline, including fallback and the case where no source is usable. */
	public static Loaded load(Path external, Path builtin) { return load(external, null, builtin); }

	public static Loaded load(Path manual, Path automatic, Path builtin) {
		String warning = "";
		Path[] archives = {manual, automatic};
		Source[] sources = {Source.MANUAL, Source.AUTOMATIC};
		for (int i = 0; i < archives.length; i++) {
			if (archives[i] == null || Files.notExists(archives[i])) continue;
			try {
				Loaded loaded = compile(archives[i], sources[i]);
				return new Loaded(loaded.index(), loaded.source(), loaded.manifest(), warning);
			} catch (IOException | RuntimeException e) {
				warning += (warning.isEmpty() ? "" : "；") + sources[i].label() + ": " + e.getMessage();
				LoggerFactory.getLogger("SkyZH").warn("翻译包不可用，尝试下一个来源: {}", warning);
			}
		}
		try {
			if (builtin == null) throw new IllegalStateException("缺少内置翻译目录");
			Map<String, JsonObject> files = TranslationLoader.readDirectory(builtin);
			validateCorpus(files);
			return new Loaded(TranslationLoader.compile(files), Source.BUILTIN, builtinManifest(), warning);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException((warning.isEmpty() ? "" : warning + "；") + "内置语料加载失败: " + e.getMessage(), e);
		}
	}

	public static Content read(Path archive) throws IOException {
		if (Files.size(archive) > MAX_TOTAL_BYTES) throw new IOException("翻译包文件过大");
		Map<String, byte[]> documents = new TreeMap<>();
		JsonObject manifest = null;
		long total = 0;
		Set<String> seen = new HashSet<>();
		try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				String name = entry.getName();
				if (!seen.add(name) || seen.size() > MAX_FILES + 256) throw new IOException("翻译包含重复路径或文件过多");
				if (entry.isDirectory()) continue;
				boolean metadata = name.equals("manifest.json");
				if (!metadata && (!name.startsWith("original_text/") || !validPath(name.substring("original_text/".length()))))
					throw new IOException("无效的翻译包路径: " + name);
				int limit = metadata ? 64 * 1024 : MAX_FILE_BYTES;
				byte[] bytes;
				try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes(limit + 1); }
				if (bytes.length > limit || (total += bytes.length) > MAX_TOTAL_BYTES) throw new IOException("翻译包解压后的数据过大");
				if (metadata) manifest = parse(bytes, name);
				else documents.put(name.substring("original_text/".length()), bytes);
			}
		}
		if (manifest == null) throw new IOException("缺少 manifest.json");
		validateManifest(manifest);
		if (documents.isEmpty() || documents.size() > MAX_FILES || integer(manifest, "file_count") != documents.size())
			throw new IOException("翻译包文件数量不符");
		if (!checksum(documents).equals(string(manifest, "content_sha256"))) throw new IOException("翻译包内容校验失败");
		Map<String, JsonObject> files = new TreeMap<>();
		for (var entry : documents.entrySet()) files.put(entry.getKey(), parse(entry.getValue(), entry.getKey()));
		validateCorpus(files);
		return new Content(Map.copyOf(files), manifest);
	}

	private static boolean validPath(String path) {
		if (!path.endsWith(".json") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) return false;
		for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
		return true;
	}

	/** Hash sorted relative paths, NUL, exact UTF-8 JSON bytes, NUL; metadata is intentionally excluded. */
	public static String checksum(Map<String, byte[]> documents) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (var entry : new TreeMap<>(documents).entrySet()) {
				digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(entry.getValue());
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}

	public static void validateManifest(JsonObject manifest) {
		if (integer(manifest, "format_version") != FORMAT) throw new IllegalArgumentException("不支持的翻译包格式");
		if (integer(manifest, "translation_api") != API) throw new IllegalArgumentException("翻译协议不兼容，请使用适配当前 Mod 的翻译包");
		if (!OffsetDateTime.parse(string(manifest, "build_at_utc8")).getOffset().equals(ZoneOffset.ofHours(8)))
			throw new IllegalArgumentException("build_at_utc8 必须显式使用 +08:00");
		if (!string(manifest, "revision").matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException("无效的来源提交 ID");
		if (!string(manifest, "content_sha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException("无效的内容摘要");
	}

	public static void validateCorpus(Map<String, JsonObject> files) {
		for (String path : CorpusData.FILES) CorpusData.required(files, path);
		CorpusData.from(files);
		JsonObject name = CorpusData.required(files, "_shared/SkyBlock_Name.json");
		for (String key : new String[] {"full", "short", "compound_stoplist"}) string(name, key);
		JsonObject terms = CorpusData.required(files, "_shared/Terms.json");
		CorpusData.strings(terms, "applies_to_types");
		if (!terms.has("terms") || !terms.get("terms").isJsonArray()) throw new IllegalArgumentException("缺少 terms 词表");
		for (var value : terms.getAsJsonArray("terms")) {
			JsonObject term = value.getAsJsonObject();
			string(term, "en"); string(term, "zh");
			if (term.has("types")) CorpusData.strings(term, "types");
		}
	}

	public static JsonObject parse(byte[] bytes, String name) throws IOException {
		try {
			String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
			try (JsonReader reader = new JsonReader(new StringReader(text))) {
				reader.setStrictness(Strictness.STRICT);
				var value = JsonParser.parseReader(reader);
				if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
					throw new IllegalArgumentException("文件必须是单个 JSON 对象");
				return value.getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) { throw new IOException("解析 " + name + " 失败: " + e.getMessage(), e); }
	}

	private static String string(JsonObject json, String key) { return CorpusData.string(json.get(key), key); }

	private static int integer(JsonObject json, String key) {
		var value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
			|| !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException("字段必须为非负整数: " + key);
		return Integer.parseInt(value.getAsString());
	}
}
