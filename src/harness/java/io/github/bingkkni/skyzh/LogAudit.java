package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.capture.CaptureAnnouncer;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.minecraft.network.chat.Component;

/**
 * Runs every chat line out of a Minecraft client log through the corpus and prints the ones nothing
 * answered for.
 *
 * <p>This is the answer to a question the game cannot be asked directly: not "is this line
 * translated correctly" but "which lines did a real session show that the corpus has never heard
 * of". A player reports "the mining messages aren't translated" and means six particular sentences
 * out of the eighty a session produces; the log holds all eighty, verbatim, including the ones
 * nobody thought to look at.
 *
 * <p>It reads a log, never the running game. Minecraft writes every chat message it receives to
 * {@code logs/latest.log}, so the collection has already happened by the time this runs — no hook,
 * no capture, nothing shipped in the mod. Colours are mostly lost on the way into the log (the
 * logger prints the flattened string), so this reports coverage, not colouring; {@code segments}
 * gaps are what the mod's own startup warnings are for.
 *
 * <pre>
 *   ./gradlew auditLog -Plog=logs/latest.log
 * </pre>
 */
public final class LogAudit {
	private static final String MARK = "[CHAT] ";

	/**
	 * This mod's own capture announcements, which Minecraft writes to the log like any other chat
	 * message. Skipped rather than reported: they are Chinese, they came from here, and letting them
	 * turn up as "SkyBlock text nobody has translated" would be this tool marking its own homework.
	 */
	private static final Pattern OWN_MESSAGE = Pattern.compile("^(?:§.)*" + Pattern.quote(CaptureAnnouncer.PREFIX) + ".*");

	/** Lines that are somebody talking, not the game: a rank tag, a level bracket, a guild prefix. */
	private static final Pattern PLAYER = Pattern.compile(
		"^(?:§.)*\\[(?:\\d+|MVP|VIP|VIP\\+|MVP\\+\\+|Lv\\d+).*|.*§7:.*|.*\\bhas joined.*"
	);

	private LogAudit() {
	}

	public static void main(String[] args) throws Exception {
		Path corpus = Path.of(args.length > 0 ? args[0] : "original_text");
		List<Path> logs = new ArrayList<>();

		for (int i = 1; i < args.length; i++) {
			Path path = Path.of(args[i]);

			if (Files.isDirectory(path)) {
				try (Stream<Path> walk = Files.walk(path)) {
					walk.filter(Files::isRegularFile).sorted().forEach(logs::add);
				}
			} else {
				logs.add(path);
			}
		}

		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(corpus));
		TranslationHarness.installIndex(index);

		Map<String, Integer> unmatched = new LinkedHashMap<>();
		int total = 0;
		int matched = 0;

		for (Path log : logs) {
			for (String line : chatLines(log)) {
				if (line.isBlank() || PLAYER.matcher(line).matches() || OWN_MESSAGE.matcher(line).matches()) {
					continue;
				}

				total++;

				if (covered(line)) {
					matched++;
				} else {
					unmatched.merge(line, 1, Integer::sum);
				}
			}
		}

		System.out.printf("聊天行 %d 条（去重前），已有记录 %d 条，未覆盖 %d 种%n", total, matched, unmatched.size());
		System.out.println();

		unmatched.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(entry -> System.out.printf("%4d× %s%n", entry.getValue(), entry.getKey()));
	}

	/**
	 * Whether the corpus answers for a message, counting each of its lines separately.
	 *
	 * <p>SkyBlock sends some announcements as several lines inside one message — the mining event
	 * countdown is two — and the mod translates those a line at a time, so a message whose lines each
	 * have a record is covered even though the block as a whole matches nothing. Minecraft's logger
	 * writes the newline as the two characters {@code \n}, which is why the split is on a literal
	 * backslash rather than on a line break.
	 */
	private static boolean covered(String message) {
		for (String line : message.split("\\\\n")) {
			if (!line.isBlank() && !Translator.translate(Component.literal(line), Surface.CHAT).matched()) {
				return false;
			}
		}

		return true;
	}

	/** Every chat message in one log file, plain or gzipped, with the log's own prefix stripped. */
	private static List<String> chatLines(Path path) throws Exception {
		List<String> lines = new ArrayList<>();
		InputStream stream = Files.newInputStream(path);

		if (path.getFileName().toString().endsWith(".gz")) {
			stream = new GZIPInputStream(stream);
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				int mark = line.indexOf(MARK);

				if (mark >= 0) {
					lines.add(line.substring(mark + MARK.length()));
				}
			}
		}

		return lines;
	}
}
