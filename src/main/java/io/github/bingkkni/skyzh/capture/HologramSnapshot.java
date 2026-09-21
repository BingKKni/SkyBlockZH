package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.StyledText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Style;

/** One spatial observation, never a paragraph reconstructed from different packets or times. */
public record HologramSnapshot(Row target, List<Row> neighbours, boolean truncated) {
	public static final double CAPTURE_DISTANCE = 32;
	private static final int MAX_ROWS = 12;

	public HologramSnapshot {
		neighbours = List.copyOf(neighbours);
	}

	public record Row(int entityId, double x, double y, double z, StyledText text) {
		public boolean near(double px, double py, double pz) {
			double dx = x - px, dy = y - py, dz = z - pz;
			return dx * dx + dy * dy + dz * dz <= CAPTURE_DISTANCE * CAPTURE_DISTANCE;
		}
	}

	private record Cell(int x, int z) {}

	/** Spatial bins avoid an all-loaded-entities squared scan every ten ticks. */
	public static final class Index {
		private final Map<Cell, List<Row>> cells = new HashMap<>();

		public Index(List<Row> rows) {
			for (Row row : rows) {
				cells.computeIfAbsent(cell(row.x(), row.z()), ignored -> new ArrayList<>()).add(row);
			}
		}

		public HologramSnapshot around(Row target) {
			List<Row> nearby = new ArrayList<>();
			Cell center = cell(target.x(), target.z());
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (Row row : cells.getOrDefault(new Cell(center.x() + dx, center.z() + dz), List.of())) {
						double x = row.x() - target.x(), z = row.z() - target.z();
						if (x * x + z * z <= 0.75 * 0.75 && Math.abs(row.y() - target.y()) <= 3) {
							nearby.add(row);
						}
					}
				}
			}
			// Keep the target even when many names overlap; then restore the displayed vertical order.
			nearby.sort(Comparator.<Row>comparingInt(r -> r.entityId() == target.entityId() ? 0 : 1)
				.thenComparingDouble(r -> Math.abs(r.y() - target.y())).thenComparingInt(Row::entityId));
			boolean truncated = nearby.size() > MAX_ROWS;
			List<Row> selected = new ArrayList<>(nearby.subList(0, Math.min(MAX_ROWS, nearby.size())));
			selected.sort(Comparator.comparingDouble(Row::y).reversed().thenComparingInt(Row::entityId));
			return new HologramSnapshot(target, selected, truncated);
		}

		private static Cell cell(double x, double z) {
			return new Cell((int) Math.floor(x), (int) Math.floor(z));
		}
	}

	/** One slot per physical display; a ticking counter must not consume all scene slots. */
	public String displayKey() {
		return Math.round(target.x() * 4) + ":" + Math.round(target.y() * 4) + ":" + Math.round(target.z() * 4);
	}

	/**
	 * What makes two observations of this hologram the same: the same entities, in the same order,
	 * saying the same things. Cheap enough to build on the client thread for every hologram in range
	 * every half second, which the JSON below is not — that is built once, on the worker, for the first
	 * sighting only. Rounded positions distinguish relocated displays without sub-block jitter.
	 * Visible style runs are included so a later colour correction is not mistaken for a repeat.
	 */
	public String signature() {
		StringBuilder key = new StringBuilder(64).append(target.entityId()).append(':').append(displayKey())
			.append(':').append(truncated);
		for (Row row : neighbours) {
			key.append('\u0001').append(row.entityId()).append('\u0002').append(row.text().plain());
			String previous = null;
			Style previousStyle = null;
			for (int i = 0; i < row.text().length(); i++) {
				Style style = row.text().styleAt(i);
				if (style.equals(previousStyle)) continue;
				previousStyle = style;
				String look = style.getColor() + ":" + style.getFont() + ":"
					+ style.isBold() + style.isItalic() + style.isUnderlined()
					+ style.isStrikethrough() + style.isObfuscated();
				if (!look.equals(previous)) {
					key.append('\u0003').append(i).append(':').append(look);
					previous = look;
				}
			}
		}
		return key.toString();
	}

	public JsonObject json() {
		JsonObject result = new JsonObject();
		result.addProperty("kind", "spatial_neighbours_not_confirmed_paragraph");
		result.addProperty("target_entity", target.entityId());
		result.addProperty("truncated", truncated);
		JsonArray rows = new JsonArray();
		for (Row row : neighbours) {
			JsonObject item = new JsonObject();
			item.addProperty("entity_id", row.entityId());
			item.addProperty("x", row.x());
			item.addProperty("y", row.y());
			item.addProperty("z", row.z());
			LegacyText.Encoded encoded = LegacyText.encode(row.text());
			item.addProperty("raw", encoded.raw());
			if (encoded.lossy()) {
				item.addProperty("legacy_codes_lossy", true);
				item.add("style_runs", LegacyText.styleRuns(encoded.runs()));
			}
			rows.add(item);
		}
		result.add("rows_top_to_bottom", rows);
		return result;
	}
}
