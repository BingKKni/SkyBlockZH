package io.github.bingkkni.skyzh.update;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Persisted independent clocks. A restart or failed request never resets the retry deadline. */
public final class UpdateState {
	public static final long HOUR = Duration.ofHours(1).toMillis();
	public static final long WEEK = Duration.ofDays(7).toMillis();
	private final Path file;
	private final Object writing = new Object();
	private JsonObject json;

	public UpdateState(Path file) throws IOException {
		this.file = file;
		try {
			json = Files.exists(file) ? UpdateFiles.read(file) : new JsonObject();
			for (String lane : new String[] {"mod", "translations", "initialization"}) {
				if (!json.has(lane)) continue;
				JsonObject state = json.getAsJsonObject(lane);
				UpdateFiles.string(state, "identity");
				UpdateFiles.number(state, "next_check");
			}
		} catch (IOException | RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("SkyZH").warn("更新检查记录无法读取，本次重新检查", e);
			json = new JsonObject();
		}
	}

	public synchronized boolean due(String lane, String identity, long now) {
		JsonObject state = json.has(lane) && json.get(lane).isJsonObject() ? json.getAsJsonObject(lane) : new JsonObject();
		if (!state.has("identity") || !identity.equals(state.get("identity").getAsString())) return true;
		long next = state.has("next_check") ? UpdateFiles.number(state, "next_check") : 0;
		// A corrected system clock must not suppress checks for more than the longest cooldown.
		return next <= now || next - now > WEEK;
	}

	/** Successful explicit recovery must not leave a failed-start deadline active after restart. */
	public void initialized(String identity, long now) throws IOException {
		finish("initialization", identity, now, 0, "ready", null);
	}

	public void finish(String lane, String identity, long now, long delay, String result, JsonObject details) throws IOException {
		JsonObject state = new JsonObject();
		state.addProperty("identity", identity);
		state.addProperty("last_check", now);
		state.addProperty("next_check", now + delay);
		state.addProperty("result", result);
		state.add("details", details == null ? new JsonObject() : details.deepCopy());
		// Serialize disk writes without holding the lock read by the client tick's due() check.
		synchronized (writing) {
			JsonObject snapshot;
			synchronized (this) { json.add(lane, state); snapshot = json.deepCopy(); }
			UpdateFiles.write(file, snapshot);
		}
	}
}
