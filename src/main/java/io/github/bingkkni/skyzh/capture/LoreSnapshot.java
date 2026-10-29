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

/** Immutable packet text for worker analysis; only the exported context is size-limited. */
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
		List<StyledText> lines = new ArrayList<>(source.size());
		long characters = 0;
		for (Component component : source) {
			StyledText line = StyledText.of(component);
			lines.add(line);
			characters += line.length();
		}
		StyledText itemName = StyledText.of(name == null ? Component.empty() : name);
		boolean truncated = lines.size() > MAX_LINES || characters > MAX_CHARACTERS || itemName.length() > 1024;
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
			StyledText boundedName = snapshot.itemName().sub(0, Math.min(1024, snapshot.itemName().length()));
			LegacyText.Encoded name = LegacyText.encode(boundedName);
			json.addProperty("item_name", name.raw());
			json.addProperty("target_line", start + 1);
			json.addProperty("target_line_count", count);
			json.addProperty("original_line_count", snapshot.originalLineCount());
			json.addProperty("truncated", snapshot.truncated());
			JsonArray raw = new JsonArray();
			JsonArray styles = new JsonArray();
			int remaining = MAX_CHARACTERS;
			for (int i = 0; i < Math.min(MAX_LINES, snapshot.lines().size()); i++) {
				StyledText line = snapshot.lines().get(i);
				if (remaining == 0 && line.length() > 0) break;
				int keep = Math.min(remaining, line.length());
				LegacyText.Encoded encoded = LegacyText.encode(line.sub(0, keep));
				remaining -= keep;
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
