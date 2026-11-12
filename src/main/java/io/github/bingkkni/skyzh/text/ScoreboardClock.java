package io.github.bingkkni.skyzh.text;

import java.util.regex.Pattern;

/** Complete 12-hour scoreboard clocks; minutes need not be rounded to ten. */
public final class ScoreboardClock {
	private static final Pattern CLOCK = Pattern.compile("(?:0?[1-9]|1[0-2]):[0-5][0-9](?:am|pm)(?: [\\p{S}\\p{Co}]{1,8})?");
	private ScoreboardClock() {}

	public static boolean matches(String text) {
		return text.length() >= 6 && text.length() <= 32 && text.charAt(0) >= '0' && text.charAt(0) <= '9'
			&& CLOCK.matcher(text).matches();
	}
}
