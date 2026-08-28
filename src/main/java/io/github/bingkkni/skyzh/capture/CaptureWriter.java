package io.github.bingkkni.skyzh.capture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes one capture file, in the shape a record in {@code original_text/} has.
 *
 * <p>Deliberately the same shape, field for field, so that finishing a capture is deleting the
 * {@code _capture} block and filling in {@code zh} — not transcribing it into another format by hand.
 * The mod's own loader ignores fields it does not know and treats an empty {@code zh} as "still
 * English", so a capture file dropped into the corpus half-finished changes nothing on screen until
 * somebody translates a line of it. That is the property that makes this safe to do in bulk.
 *
 * <p>Everything that is the capture's opinion rather than the server's fact lives under
 * {@code _capture}, one underscore-prefixed key, so there is never a question about which half of a
 * record came from Hypixel and which half a program guessed.
 */
public final class CaptureWriter {
	private static final DateTimeFormatter STAMP =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private static final String NOTE =
		"本文件由 SkyZH 运行时采集自动生成，未经人工核对。搬进 original_text/ 之前请："
			+ "① 删掉所有 _capture 字段；② 核对 placeholders 是不是真的可变（observed 里列了实际见过的取值）；"
			+ "③ 补 context/gloss；④ 填 zh。zh 为空的记录不会影响游戏内显示，可以安全地分批完成。";

	private CaptureWriter() {
	}

	/**
	 * Writes the file, via a temporary file next to it.
	 *
	 * <p>Flushes happen while the game is running and the player may quit at any point in one. A
	 * half-written JSON file is worse than a stale one: it is unreadable, and the capture it replaced
	 * is gone. The rename is the only step that is visible.
	 */
	public static void write(Path path, Meta meta, List<CapturedLine> lines) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("_capture_note", NOTE);
		json.addProperty("source", "runtime capture (SkyZH)");
		json.addProperty("fetched_at", STAMP.format(Instant.now()));
		json.addProperty("verified_ingame", true);
		json.addProperty("gameplay", meta.gameplay());
		json.addProperty("surface", meta.surface().directory());
		json.addProperty("name", meta.name());

		JsonArray records = new JsonArray();

		for (CapturedLine line : lines) {
			records.add(record(line));
		}

		json.add("lines", records);

		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json, writer);
		}

		Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
	}

	/** One record as it will appear in the file. Public so the harness can check the JSON itself. */
	public static JsonObject record(CapturedLine line) {
		CapturedLine.Rendered rendered = line.render();
		JsonObject json = new JsonObject();

		json.addProperty("id", line.id());
		json.addProperty("context", "");
		json.addProperty("raw", rendered.encoded().raw());
		json.addProperty("text", rendered.text());

		JsonArray placeholders = new JsonArray();

		for (CapturedLine.Placeholder placeholder : rendered.placeholders()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("token", placeholder.token());
			entry.addProperty("desc", "运行时采集推断出的可变位置，语义待人工确认");
			entry.addProperty("type", placeholder.type());
			entry.addProperty("example", placeholder.observed().getFirst());
			placeholders.add(entry);
		}

		json.add("placeholders", placeholders);
		json.addProperty("gloss", "");
		json.addProperty("translate", true);
		json.addProperty("zh", "");

		// Only when the line really does change colour partway through, which is the case the corpus
		// needs a segments array for. Writing one for every line would bury the ones that matter.
		if (rendered.encoded().runs().size() >= 2) {
			json.add("segments", segments(rendered));
		}

		json.add("_capture", meta(line, rendered));

		return json;
	}

	private static JsonArray segments(CapturedLine.Rendered rendered) {
		JsonArray array = new JsonArray();

		for (LegacyText.Run run : rendered.encoded().runs()) {
			JsonObject segment = new JsonObject();
			segment.addProperty("color", run.codes());
			segment.addProperty("text", run.text());
			segment.addProperty("zh", "");
			array.add(segment);
		}

		return array;
	}

	private static JsonObject meta(CapturedLine line, CapturedLine.Rendered rendered) {
		JsonObject capture = new JsonObject();
		capture.addProperty("count", line.count());
		capture.addProperty("first_seen", STAMP.format(Instant.ofEpochMilli(line.firstSeen())));
		capture.addProperty("last_seen", STAMP.format(Instant.ofEpochMilli(line.lastSeen())));

		if (!line.note().isEmpty()) {
			capture.addProperty("where", line.note());
		}

		// Which island, as opposed to which gameplay folder — see CapturedLine#place. Named so it does
		// not read as another spelling of "where", which is the menu the line was in.
		if (!line.seenIn().isEmpty()) {
			JsonArray places = new JsonArray();
			line.seenIn().forEach(places::add);
			capture.add("seen_in", places);
		}

		// The other menus this same line turned up in. Its presence is the signal that the record
		// belongs in _shared/ rather than in the menu it happens to be filed under.
		if (!line.alsoSeen().isEmpty()) {
			JsonArray elsewhere = new JsonArray();
			line.alsoSeen().forEach(elsewhere::add);
			capture.add("also_seen", elsewhere);
			capture.addProperty(
				"also_seen_fix",
				"这一行在多个菜单里出现，按 original_text/README.md §5.6 抽进 _shared/ 并用 ref 引用，不要各抄一份"
			);
		}

		if (!rendered.placeholders().isEmpty()) {
			JsonObject observed = new JsonObject();

			for (CapturedLine.Placeholder placeholder : rendered.placeholders()) {
				JsonArray values = new JsonArray();
				placeholder.observed().forEach(values::add);
				observed.add(placeholder.token(), values);
			}

			capture.add("observed", observed);
		}

		// Spelled out only when there is something invisible to spell — a private-use icon, a
		// non-breaking space, a stray control character. That is exactly when a record written by
		// hand from the text above would silently fail to match.
		String raw = rendered.encoded().raw();

		if (LegacyText.hasInvisible(raw)) {
			capture.addProperty("raw_escaped", LegacyText.escape(raw));
		}

		if (rendered.encoded().lossy()) {
			capture.addProperty("legacy_codes_lossy", true);
			capture.add("style_runs", LegacyText.styleRuns(rendered.encoded().runs()));
		}

		explain(capture, line.verdict());

		return capture;
	}

	/**
	 * Why the line is in this pile, in the terms of the fix it needs.
	 *
	 * <p>The mixed and colour piles name the record and the file it lives in, because the work there is
	 * editing an existing record rather than writing a new one, and finding it again by searching for
	 * the English is exactly the step worth removing. The untranslated pile names a near miss when
	 * there is one, which turns "why is this still English when the corpus clearly covers it" from a
	 * suspicion into a line of the file.
	 */
	private static void explain(JsonObject capture, Classifier.Verdict verdict) {
		if (verdict == null) {
			return;
		}

		if (!verdict.recordId().isEmpty()) {
			capture.addProperty("matched_record", verdict.recordId());
			capture.addProperty("matched_file", verdict.recordFile());
		}

		if (verdict.bucket() == Classifier.Bucket.COLOUR) {
			capture.addProperty(
				"colour_fix",
				"这一行的译文已经全是中文，但整段被刷成了同一个颜色：游戏里它中途换色，语料里却记成了一整行。"
					+ "把上面这份 segments 数组抄到 matched_record 那条记录里（颜色边界就是服务器实际发的），"
					+ "再把原来的整句译文按段拆进各自的 zh；中文语序和英文不同时用 segments[].order 指定该段渲染到第几位。"
					+ "segments 里的 color 只是给人看的，渲染时颜色仍然从屏幕上当场读——分段只决定边界。"
			);
		}

		if (!verdict.words().isEmpty()) {
			JsonArray words = new JsonArray();
			verdict.words().forEach(words::add);
			capture.add("still_english", words);
			capture.addProperty("fix", "该记录有 segments 未填 zh，或整段未译；补上对应 segments[].zh 即可");
		}

		if (!verdict.values().isEmpty()) {
			JsonArray values = new JsonArray();
			verdict.values().forEach(values::add);
			capture.add("terms_missing", values);
			capture.addProperty("terms_fix", "这些是占位符捕到的值，往 _shared/Terms.json 的 terms 里各加一条");
		}

		Classifier.NearMiss near = verdict.nearMiss();

		if (near != null) {
			JsonObject miss = new JsonObject();
			miss.addProperty("record", near.id());
			miss.addProperty("file", near.file());
			miss.addProperty("record_text", near.record());
			miss.addProperty("actual_text", near.actual());
			miss.addProperty(
				"why",
				"语料里这条记录的文字和游戏里这一行只差在不可见字符/标点上，所以永远匹配不上。"
					+ "对比上面两行的 \\uXXXX 转义，把记录改成游戏里实际发的写法（图标类差异见 text/Glyphs.java）。"
			);
			capture.add("near_miss", miss);
		}
	}

	/** Everything a capture file's own header says about where its contents came from. */
	public record Meta(Classifier.Bucket bucket, String gameplay, CaptureSurface surface, String name) {
		/** {@code untranslated/Mining/ScoreBoard/Sidebar.json} — the corpus layout, one level down. */
		public Path path(Path root) {
			return root.resolve(this.bucket.directory())
				.resolve(this.gameplay)
				.resolve(this.surface.directory())
				.resolve(this.name + ".json");
		}
	}
}
