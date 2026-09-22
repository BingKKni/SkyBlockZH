package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.capture.TextCapture;
import io.github.bingkkni.skyzh.gui.SkyZHWelcomeScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** The two local command aliases and their deliberately small grammar. */
public final class SkyZHCommand {
	public static final List<String> ALIASES = List.of("skyzh", "skyblockzh");
	public static final List<String> SWITCHES = List.of("power", "tip", "capture", "updatecheck");
	public static final List<String> PLAIN = List.of("on", "off", "clear");

	private SkyZHCommand() {
	}

	/** Runs a SkyZH command, returning whether it belonged to this mod and must not reach the server. */
	public static boolean run(String command) {
		Parsed parsed = parse(command);

		if (parsed == null) {
			return false;
		}

		switch (parsed.sub()) {
			case "" -> SkyZHWelcomeScreen.openFromCommand();
			case "on" -> plainPower(parsed.alias(), true, parsed.argument(), parsed.state());
			case "off" -> plainPower(parsed.alias(), false, parsed.argument(), parsed.state());
			case "switch" -> flip(parsed.alias(), parsed.argument(), parsed.state());
			case "updatecheck" -> updateCheck(parsed.alias(), parsed.argument(), parsed.state());
			case "clear" -> clear(parsed.alias(), parsed.argument(), parsed.state());
			default -> unknown(parsed.alias(), parsed.sub());
		}

		return true;
	}

	/** Pure parsing for the command harness; {@code null} means this command belongs elsewhere. */
	static Parsed parse(String command) {
		if (command == null) {
			return null;
		}

		String trimmed = command.trim();

		if (trimmed.isEmpty()) {
			return null;
		}

		String[] words = trimmed.split("\\s+");
		String alias = words[0].toLowerCase(Locale.ROOT);

		if (!ALIASES.contains(alias)) {
			return null;
		}

		return new Parsed(
			alias,
			words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "",
			words.length > 2 ? words[2].toLowerCase(Locale.ROOT) : "",
			words.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(words, 3, words.length)).toLowerCase(Locale.ROOT) : ""
		);
	}

	/** The exact fallback menu text, separate from chat so every alias and conditional row can be tested. */
	static List<String> helpLines(String alias, boolean captureEnabled) {
		List<String> lines = new ArrayList<>();
		lines.add("§e=============== §b" + Feedback.NAME + " §e===============");
		lines.add("§6/" + alias + "  §f打开设置");
		lines.add("§6/" + alias + " switch power/tip/capture [on/off]  §f切换总功能/显示原文提示/采集功能为开/关");
		lines.add("§6/" + alias + " updatecheck [on/off]  §f切换启动检查更新功能");

		if (captureEnabled) {
			lines.add("§6/" + alias + " clear  §f清空捕捉到的文本");
		}

		lines.add("§e======================================");
		return List.copyOf(lines);
	}

	private static void power(boolean on) {
		SkyZHConfig config = SkyZHConfig.get();
		change("翻译", () -> config.enabled, value -> config.enabled = value, on);
	}

	private static void plainPower(String alias, boolean on, String argument, String extra) {
		if (!hasUnexpectedArguments(alias, on ? "on" : "off", argument, extra)) {
			power(on);
		}
	}

	private static boolean hasUnexpectedArguments(String alias, String command, String argument, String extra) {
		if (argument.isEmpty() && extra.isEmpty()) {
			return false;
		}

		unknown(alias, (command + " " + argument + " " + extra).trim());
		return true;
	}

	private static void updateCheck(String alias, String state, String extra) {
		if (!extra.isEmpty()) {
			unknown(alias, "updatecheck " + state + " " + extra);
			return;
		}

		flip(alias, "updatecheck", state);
	}

	private static void flip(String alias, String which, String state) {
		if (!state.isEmpty() && !state.equals("on") && !state.equals("off")) {
			unknown(alias, "switch " + which + " " + state);
			return;
		}
		SkyZHConfig config = SkyZHConfig.get();

		switch (which) {
			case "power" -> change("翻译", () -> config.enabled,
				value -> config.enabled = value, switchValue(config.enabled, state));
			case "tip" -> change("显示原文提示", () -> config.originalTips,
				value -> config.originalTips = value, switchValue(config.originalTips, state));
			case "capture" -> change("采集未翻译文本", () -> config.captureUntranslated,
				value -> config.captureUntranslated = value, switchValue(config.captureUntranslated, state));
			case "updatecheck" -> change("检查更新", () -> config.updateCheck,
				value -> config.updateCheck = value, switchValue(config.updateCheck, state));
			default -> unknown(alias, which.isEmpty() ? "switch" : "switch " + which);
		}
	}

	static boolean switchValue(boolean current, String state) {
		return state.isEmpty() ? !current : state.equals("on");
	}

	static String clearSucceeded() {
		return "§a已清空所有捕捉到的未翻译文本!";
	}

	static String changed(String what, boolean on) {
		return (on ? "§a" : "§c") + what + "功能已" + (on ? "打开" : "关闭") + "!";
	}

	static String changeFailed(String what, String reason) {
		return "§c很抱歉，开关" + what + "功能时出现错误惹... 原因: " + reason;
	}

	/**
	 * Persists one setting before claiming success, restoring its runtime value if the write fails.
	 *
	 * <p>Capture is flushed only after its off state is safely persisted. Once the switch is closed no
	 * later packet will wake the capture path up, so this is the last dependable chance to write what the
	 * worker is already holding.
	 */
	private static void change(String what, BooleanSupplier current, Setting setting, boolean on) {
		boolean previous = current.getAsBoolean();
		setting.set(on);

		try {
			SkyZHConfig.get().saveChecked();
		} catch (Exception e) {
			setting.set(previous);
			Feedback.send(changeFailed(what, e.toString()));
			return;
		}

		if (what.equals("采集未翻译文本") && previous && !on) {
			TextCapture.flush();
		}

		Feedback.send(changed(what, on));
	}

	private static void clear(String alias, String argument, String extra) {
		if (!hasUnexpectedArguments(alias, "clear", argument, extra)) {
			clear();
		}
	}

	private static void clear() {
		try {
			TextCapture.clear();
			Feedback.send(clearSucceeded());
		} catch (Exception e) {
			Feedback.send("§c很抱歉，清空捕捉到的未翻译文本时出现错误惹... 原因: " + e);
		}
	}

	private static void unknown(String alias, String detail) {
		Feedback.send("§c未知命令: /" + alias + (detail.isEmpty() ? "" : " " + detail));
		for (String line : helpLines(alias, SkyZHConfig.get().captureUntranslated)) {
			Feedback.raw(line);
		}
	}

	@FunctionalInterface
	private interface Setting {
		void set(boolean value);
	}

	record Parsed(String alias, String sub, String argument, String state) {
	}
}
