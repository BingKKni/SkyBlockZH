package io.github.bingkkni.skyzh.update;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Only locally issued chat buttons may invoke update actions; command text never supplies file paths. */
public final class UpdateActions {
	public enum Action { REMOVE_MANUAL, MOD, TRANSLATIONS, CHECK }
	private static final int LIMIT = 128;
	private final Map<String, Action> issued = new LinkedHashMap<>();

	public synchronized String issue(Action action) {
		String token = UUID.randomUUID().toString();
		issued.put(token, action);
		while (issued.size() > LIMIT) issued.remove(issued.keySet().iterator().next());
		return "/skyzh updateaction " + token;
	}

	public synchronized boolean run(String token, Predicate<Action> dispatch) {
		Action action = issued.get(token);
		if (action == null || !dispatch.test(action)) return false;
		if (action != Action.REMOVE_MANUAL) issued.remove(token);
		return true;
	}

	/** Keep failed or busy removals retryable; revoke only after removal and replacement loading succeed. */
	public synchronized void complete(String token) {
		if (issued.get(token) == Action.REMOVE_MANUAL) issued.remove(token);
	}
}
