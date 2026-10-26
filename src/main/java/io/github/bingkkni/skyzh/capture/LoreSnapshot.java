package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.StyledText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Immutable server item context, shared by all problematic lines from the same packet. */
public record LoreSnapshot(
	String menu, int containerId, int slot, StyledText itemName, List<StyledText> lines,
	int originalLineCount, boolean truncated, String signature
) {
	private static final int MAX_LINES = 256;
	private static final int MAX_CHARACTERS = 65_536;

	public LoreSnapshot {
		lines = List.copyOf(lines);
	}

	public static LoreSnapshot of(String menu, int containerId, int slot, Component name, List<Component> source) {
		List<StyledText> lines = new ArrayList<>();
		int remaining = MAX_CHARACTERS;
		boolean truncated = source.size() > MAX_LINES;
		for (Component component : source) {
			if (lines.size() >= MAX_LINES || remaining <= 0) break;
			StyledText line = StyledText.of(component);
			int keep = Math.min(remaining, line.length());
			truncated |= keep < line.length();
			lines.add(line.sub(0, keep));
			remaining -= keep;
		}
		truncated |= lines.size() < source.size();
		StyledText itemName = StyledText.of(name == null ? Component.empty() : name);
		if (itemName.length() > 1024) {
			itemName = itemName.sub(0, 1024);
			truncated = true;
		}
		String signature = signature(menu, itemName, lines, source.size(), truncated);
		return new LoreSnapshot(menu, containerId, slot, itemName, lines, source.size(), truncated, signature);
	}

	/** A record points back to its exact position; blank and already-covered neighbours remain here. */
	public record Target(LoreSnapshot snapshot, int start, int count) {
		public String key() {
			return snapshot.signature() + ":" + start + ":" + count;
		}

		public JsonObject json() {
			JsonObject json = new JsonObject();
			json.addProperty("menu", snapshot.menu());
			json.addProperty("container_id", snapshot.containerId());
			json.addProperty("slot", snapshot.slot());
			json.addProperty("item_name", LegacyText.encode(snapshot.itemName()).raw());
			json.addProperty("target_line", start + 1);
			json.addProperty("target_line_count", count);
			json.addProperty("original_line_count", snapshot.originalLineCount());
			json.addProperty("truncated", snapshot.truncated());
			JsonArray raw = new JsonArray();
			JsonArray styles = new JsonArray();
			for (int i = 0; i < snapshot.lines().size(); i++) {
				LegacyText.Encoded encoded = LegacyText.encode(snapshot.lines().get(i));
				raw.add(encoded.raw());
				if (encoded.lossy()) {
					JsonObject styled = new JsonObject();
					styled.addProperty("line", i + 1);
					styled.add("style_runs", LegacyText.styleRuns(encoded.runs()));
					styles.add(styled);
				}
			}
			json.add("original_lines", raw);
			if (!styles.isEmpty()) json.add("line_styles", styles);
			LegacyText.Encoded name = LegacyText.encode(snapshot.itemName());
			if (name.lossy()) json.add("item_name_styles", LegacyText.styleRuns(name.runs()));
			return json;
		}
	}

	// Container IDs and slots describe the first observation, not a new paragraph on every reopen.
	private static String signature(String menu, StyledText name,
		List<StyledText> lines, int originalCount, boolean truncated) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, menu);
			update(digest, originalCount + ":" + truncated);
			styled(digest, name);
			for (StyledText line : lines) styled(digest, line);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void styled(MessageDigest digest, StyledText text) {
		update(digest, text.plain());
		for (int i = 0; i < text.length(); i++) {
			if (i == 0 || !text.styleAt(i).equals(text.styleAt(i - 1))) {
				update(digest, i + ":" + text.styleAt(i));
			}
		}
	}

	private static void update(MessageDigest digest, String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
		digest.update(bytes);
	}
}
