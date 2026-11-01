package io.github.bingkkni.skyzh.capture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server book pages waiting for capture/identity gates, never a mirror of the client inventory. */
public final class BookCandidates {
	private static final int MAX_BOOKS = 64;
	private static final int MAX_CHARACTERS = 1_048_576;
	private final Map<String, BookSnapshot> pending = new LinkedHashMap<>();
	private Object connection;
	private Object world;
	private int characters;

	/** Identity changes discard only the previous session's candidates, including while capture is off. */
	public void session(Object connection, Object world) {
		if (this.connection != connection || this.world != world) {
			clear();
			this.connection = connection;
			this.world = world;
		}
	}

	public void remember(BookSnapshot snapshot) {
		if (connection == null || world == null) return;
		int length = snapshot.pages().stream().mapToInt(page -> page.length()).sum();
		if (length > MAX_CHARACTERS) return;
		String key = snapshot.signature();
		if (pending.containsKey(key)) return;
		while (!pending.isEmpty() && (pending.size() >= MAX_BOOKS || characters + length > MAX_CHARACTERS)) {
			BookSnapshot oldest = pending.remove(pending.keySet().iterator().next());
			characters -= oldest.pages().stream().mapToInt(page -> page.length()).sum();
		}
		pending.put(key, snapshot);
		characters += length;
	}

	/** A closed gate leaves immutable candidates in memory and submits nothing to capture. */
	public List<BookSnapshot> take(boolean allowed) {
		if (!allowed) return List.of();
		List<BookSnapshot> ready = List.copyOf(pending.values());
		clear();
		return ready;
	}

	public boolean isEmpty() { return pending.isEmpty(); }

	public void clear() {
		pending.clear();
		characters = 0;
	}
}
