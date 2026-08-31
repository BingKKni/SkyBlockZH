package io.github.bingkkni.skyzh.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lines that arrived before the sidebar said where the player was standing.
 *
 * <p>Everything captured is filed under a gameplay category worked out from the area on the sidebar,
 * and there is a window — every warp, every join, every server hop inside Hypixel — where the client
 * has the text but not yet the area. The sidebar is rebuilt from scratch on the other side of a warp:
 * the title packet, the score entries and the team prefixes that hold the location row all arrive
 * separately, and in the meantime the server is already talking. Joining an island sends the profile
 * line, the island's welcome messages and a full inventory before the location row exists.
 *
 * <p>Asking {@link CaptureContext#gameplay()} in that window gets an honest "I don't know", and the
 * old code wrote that answer down: a session's loudest moments — precisely the ones worth capturing —
 * were filed under {@code _Unknown_Gameplay} while the corpus folder they belonged to sat empty. The
 * text was not wrong and the area was not wrong; they were only asked about in the wrong order.
 *
 * <p>So the answer is deferred instead of guessed. A line with no place yet waits here, keeps its own
 * timestamp, and is filed the moment the sidebar names the area — which is the area it belongs to,
 * because the text arrived after the warp that brought the player there. Waiting is bounded in both
 * directions: {@code hold} milliseconds, after which the sidebar plainly is not going to answer and
 * the unknown folder is the truthful place for the line, and {@code max} lines, because a queue that
 * grows without limit is a memory leak wearing a feature's clothes.
 *
 * <p>Pure logic with no game state in it, so the ordering it exists to get right is exercised by
 * {@code checkCapture} rather than by warping around Hypixel and reading folder names afterwards.
 */
public final class Unplaced {
	/** What a held line is stamped with when the wait runs out. Matches {@link Areas#unknown()}. */
	private static final String UNKNOWN = "_Unknown_Gameplay";

	private final Deque<CaptureStore.Sighting> held = new ArrayDeque<>();
	private final int max;
	private final long hold;

	/**
	 * @param max  how many lines may wait at once; the oldest is filed as unknown to make room
	 * @param hold how long a line waits for the sidebar before it is filed as unknown, in milliseconds
	 */
	public Unplaced(int max, long hold) {
		this.max = max;
		this.hold = hold;
	}

	/**
	 * Takes one line, and hands back everything now ready for the store, oldest first.
	 *
	 * <p>Ordering is the whole point of returning a list rather than filing as it goes: a line held
	 * from before the area was known has to reach the store ahead of the line that arrived after it,
	 * because the store's deduplication treats the first sighting of a piece of text as the one whose
	 * colours are kept.
	 *
	 * @param gameplay the category the sidebar reports, or {@code null} while it has not said
	 */
	public List<CaptureStore.Sighting> offer(CaptureStore.Sighting sighting, String gameplay, long now) {
		List<CaptureStore.Sighting> ready = tick(gameplay, now);

		if (gameplay == null) {
			this.held.addLast(sighting);
			overflow(ready);

			return ready;
		}

		ready.add(stamp(sighting, gameplay));

		return ready;
	}

	/**
	 * The same question with no new line to add: what is ready, given what is known now.
	 *
	 * <p>Called every tick, because the sidebar naming the area is not itself a capture — without this
	 * a line held during a quiet warp would sit here until the next thing the server said.
	 */
	public List<CaptureStore.Sighting> tick(String gameplay, long now) {
		List<CaptureStore.Sighting> ready = new ArrayList<>();

		if (gameplay != null) {
			while (!this.held.isEmpty()) {
				ready.add(stamp(this.held.removeFirst(), gameplay));
			}

			return ready;
		}

		while (!this.held.isEmpty() && now - this.held.peekFirst().when() >= this.hold) {
			ready.add(stamp(this.held.removeFirst(), UNKNOWN));
		}

		return ready;
	}

	/**
	 * Everything held, filed under whatever is known at the end of a session.
	 *
	 * <p>A disconnect is the one moment where waiting longer cannot help: nothing more is coming. The
	 * lines are still worth keeping — the last thing a server says before it drops a player is often
	 * the most interesting line of the session.
	 */
	public List<CaptureStore.Sighting> drain(String gameplay) {
		List<CaptureStore.Sighting> ready = new ArrayList<>();

		while (!this.held.isEmpty()) {
			ready.add(stamp(this.held.removeFirst(), gameplay == null ? UNKNOWN : gameplay));
		}

		return ready;
	}

	/** How many lines are waiting. For the harness and for the session summary. */
	public int size() {
		return this.held.size();
	}

	/**
	 * Throws away everything held without filing any of it.
	 *
	 * <p>The one caller is {@code /skyzh clear}, which is a request to forget this session — so unlike
	 * {@link #drain} these lines are not worth keeping. Filing them would write records back into files
	 * that were just deleted.
	 */
	public void clear() {
		this.held.clear();
	}

	/** Files the oldest held lines as unknown until the queue is back within its ceiling. */
	private void overflow(List<CaptureStore.Sighting> ready) {
		while (this.held.size() > this.max) {
			ready.add(stamp(this.held.removeFirst(), UNKNOWN));
		}
	}

	private static CaptureStore.Sighting stamp(CaptureStore.Sighting sighting, String gameplay) {
		return new CaptureStore.Sighting(
			sighting.surface(), sighting.key(), sighting.text(), gameplay, sighting.area(),
			sighting.name(), sighting.note(), sighting.when()
		);
	}
}
