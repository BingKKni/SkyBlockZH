package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.text.StyledText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything captured so far, and the one thread allowed to think about it.
 *
 * <p>Capture points sit in packet handlers and in the client tick, which is to say on the thread that
 * draws frames. Matching a line against the corpus, merging it into a template and writing JSON are
 * all far too much to do there — a translation mod that costs a player frames while they mine is a
 * mod they will uninstall, and rightly. So the game thread does the least it can: it decides whether
 * this text has been seen before, and if not, flattens it once and drops it on a queue.
 *
 * <p>The queue is bounded and drops rather than blocks when it fills. There is no version of this
 * feature where stalling the render thread to keep a capture is the right trade.
 */
public final class CaptureStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

	/** Deep enough for a menu's worth of lore arriving in one packet, shallow enough to notice. */
	private static final int QUEUE_DEPTH = 8192;

	private static final long FLUSH_INTERVAL_MS = 20_000L;

	/**
	 * How long the worker waits on an empty queue.
	 *
	 * <p>Short, because this is also the resolution of the chat announcement: a batch is sent one
	 * second after captures stop arriving, and a poll that blocked for two would make "one second"
	 * mean three. Blocking on a queue costs nothing while nothing is happening.
	 */
	private static final long POLL_MS = 250L;

	/** Ceilings that keep a long session from turning into an unreadable pile. */
	private static final int MAX_FILES = 512;
	private static final int MAX_LINES_PER_FILE = 800;

	/**
	 * One line as the game thread saw it.
	 *
	 * @param text     the flattened line, or {@code null} when this is another sighting of text already
	 *                 sent — the second time a line is seen there is nothing new to snapshot
	 * @param area     the place the sidebar named — {@code Dwarven Mines}, {@code Your Island} — as
	 *                 opposed to {@code gameplay}, which is the folder that place maps to. Kept because
	 *                 several places share a folder: everything general lands in {@code Hub_General},
	 *                 so a capture from a private island reads afterwards as if it happened in the Hub,
	 *                 and nobody looking at the file can tell which. Written out as {@code seen_in}
	 */
	public record Sighting(
		CaptureSurface surface, String key, StyledText text, String gameplay, String area, String name,
		String note, long when
	) {}

	private static final BlockingQueue<Sighting> QUEUE = new ArrayBlockingQueue<>(QUEUE_DEPTH);

	private static final Map<CaptureWriter.Meta, List<CapturedLine>> FILES = new LinkedHashMap<>();
	private static final Map<String, CapturedLine> BY_KEY = new HashMap<>();
	private static final Map<CaptureWriter.Meta, Boolean> DIRTY = new HashMap<>();

	/**
	 * Menu lines already written down somewhere in this gameplay, by their exact text.
	 *
	 * <p>Only the item surface has more than one file per gameplay, and it has sixty: every SkyBlock
	 * menu carries the same navigation buttons, the same rarity lines and, on a guide, the same
	 * paragraph on every page. Filed per menu, one session wrote a thousand records that were copies
	 * of another record in the folder next door. The first menu a line is seen in keeps it and the
	 * rest are noted on it — {@link CapturedLine#again(long, String, String)} — which is the same shape the
	 * corpus's own {@code _shared/} library has.
	 *
	 * <p>Keyed by gameplay as well as by text, because filing a Foraging line into a Mining folder is
	 * the one kind of contamination nobody would spot afterwards.
	 */
	private static final Map<String, CapturedLine> SHARED = new HashMap<>();

	private static Path root;
	private static Thread worker;
	private static boolean warnedFull;
	private static int dropped;
	private static int kept;

	private CaptureStore() {
	}

	/** Where files are written. Split out from {@link #start} so the harness can run without a thread. */
	public static synchronized void root(Path directory) {
		root = directory;
	}

	/** Starts the worker. Called once, when the first capture point finds the switch turned on. */
	public static synchronized void start(Path directory) {
		if (worker != null) {
			return;
		}

		root(directory);
		worker = new Thread(CaptureStore::run, "SkyZH-capture");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();

		Runtime.getRuntime().addShutdownHook(new Thread(CaptureStore::flush, "SkyZH-capture-flush"));
		LOGGER.info("SkyZH 未翻译文本采集已开启，输出目录：{}", root.toAbsolutePath());
	}

	/** Hands one line to the worker, or drops it if the queue has backed up. */
	public static void offer(Sighting sighting) {
		if (QUEUE.offer(sighting)) {
			return;
		}

		dropped++;

		if (!warnedFull) {
			warnedFull = true;
			LOGGER.warn("SkyZH 采集队列已满，本次会话有文本被丢弃（不影响游戏，只影响采集完整度）。");
		}
	}

	private static void run() {
		long lastFlush = System.currentTimeMillis();

		while (true) {
			try {
				Sighting sighting = QUEUE.poll(POLL_MS, TimeUnit.MILLISECONDS);

				if (sighting != null) {
					accept(sighting);
				}

				// Files first, then the message that links to them.
				CaptureAnnouncer.tick(CaptureStore::flush);

				if (System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) {
					flush();
					lastFlush = System.currentTimeMillis();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				flush();
				return;
			} catch (Exception e) {
				// One malformed line must not take the worker down with it; the rest of the session
				// is still worth capturing.
				LOGGER.warn("SkyZH 采集处理一行时出错，已跳过：{}", e.toString());
			}
		}
	}

	/**
	 * One line, all the way from "is this worth keeping" to "which record does it belong to".
	 *
	 * <p>Public because it is the whole of the interesting behaviour and the harness runs it directly:
	 * pushing test lines through a queue and a thread would test the thread, which is not the part that
	 * can be subtly wrong.
	 */
	public static synchronized void accept(Sighting sighting) {
		if (sighting.text() == null) {
			CapturedLine known = BY_KEY.get(sighting.key());

			if (known != null) {
				known.again(sighting.when(), sighting.area(), "");
				DIRTY.put(known.meta(), true);
			}

			return;
		}

		String shared = shareable(sighting) ? sighting.gameplay() + '\u0000' + sighting.text().plain() : null;

		if (shared != null) {
			CapturedLine already = SHARED.get(shared);

			if (already != null) {
				already.again(sighting.when(), sighting.area(), sighting.note());
				BY_KEY.put(sighting.key(), already);
				DIRTY.put(already.meta(), true);

				return;
			}
		}

		Classifier.Verdict verdict = Classifier.of(sighting.surface(), sighting.text());

		if (verdict == null) {
			return;
		}

		CaptureWriter.Meta meta = new CaptureWriter.Meta(
			verdict.bucket(), sighting.gameplay(), sighting.surface(), sighting.name()
		);

		if (!FILES.containsKey(meta) && FILES.size() >= MAX_FILES) {
			return;
		}

		List<CapturedLine> lines = FILES.computeIfAbsent(meta, key -> new ArrayList<>());

		for (CapturedLine line : lines) {
			if (line.merge(sighting.text(), sighting.note(), sighting.area(), sighting.when())) {
				BY_KEY.put(sighting.key(), line);
				DIRTY.put(meta, true);
				return;
			}
		}

		if (lines.size() >= MAX_LINES_PER_FILE) {
			return;
		}

		CapturedLine line = new CapturedLine(
			sighting.surface(), sighting.text(), sighting.note(), verdict, sighting.area(), sighting.when()
		);

		line.id(uniqueId(lines, Classifier.id(sighting.text().plain())));
		line.meta(meta);
		lines.add(line);
		BY_KEY.put(sighting.key(), line);
		DIRTY.put(meta, true);
		kept++;

		if (shared != null) {
			SHARED.put(shared, line);
		}

		if (root != null) {
			// Only new records are announced. A repeat or a merge into an existing template is real
			// information — a value nobody had seen in that position — but it is not a line the corpus
			// did not have, and "captured 3" has to mean three records appeared.
			CaptureAnnouncer.record(meta, meta.path(root), sighting.area());
		}
	}

	/**
	 * Whether this line should be looked for in the other menus of its gameplay before a new record
	 * is opened for it.
	 *
	 * <p>Items only. Every other surface writes one file per gameplay, so the per-file search below
	 * already sees everything — and on the surfaces where it does not, the separation is the point: a
	 * sentence two NPCs both say is two records, because they are two characters saying it.
	 */
	private static boolean shareable(Sighting sighting) {
		return sighting.surface() == CaptureSurface.GUI_ITEM && sighting.gameplay() != null;
	}

	/** {@code id}s are unique inside a file, which is the scope the corpus requires them to be. */
	private static String uniqueId(List<CapturedLine> lines, String wanted) {
		String id = wanted;
		int suffix = 2;

		while (taken(lines, id)) {
			id = wanted + '_' + suffix++;
		}

		return id;
	}

	private static boolean taken(List<CapturedLine> lines, String id) {
		for (CapturedLine line : lines) {
			if (line.id().equals(id)) {
				return true;
			}
		}

		return false;
	}

	/** Writes every file that has changed since the last time. Safe to call from any thread. */
	public static synchronized void flush() {
		if (root == null || DIRTY.isEmpty()) {
			return;
		}

		for (Map.Entry<CaptureWriter.Meta, Boolean> dirty : DIRTY.entrySet()) {
			CaptureWriter.Meta meta = dirty.getKey();
			List<CapturedLine> lines = FILES.get(meta);

			if (lines == null || lines.isEmpty()) {
				continue;
			}

			try {
				CaptureWriter.write(meta.path(root), meta, lines);
			} catch (IOException e) {
				LOGGER.warn("SkyZH 采集写入 {} 失败：{}", meta.path(root), e.toString());
			}
		}

		DIRTY.clear();
	}

	/** What this session has collected, for the line printed when the player disconnects. */
	public static synchronized String summary() {
		Map<Classifier.Bucket, Integer> counts = new EnumMap<>(Classifier.Bucket.class);

		for (Map.Entry<CaptureWriter.Meta, List<CapturedLine>> file : FILES.entrySet()) {
			counts.merge(file.getKey().bucket(), file.getValue().size(), Integer::sum);
		}

		return String.format(
			"未翻译 %d 条 / 中英混杂 %d 条 / 颜色失真 %d 条，共 %d 个文件%s",
			counts.getOrDefault(Classifier.Bucket.UNTRANSLATED, 0),
			counts.getOrDefault(Classifier.Bucket.MIXED, 0),
			counts.getOrDefault(Classifier.Bucket.COLOUR, 0),
			FILES.size(), dropped > 0 ? "（队列满丢弃 " + dropped + " 条）" : ""
		);
	}

	static int kept() {
		return kept;
	}
}
