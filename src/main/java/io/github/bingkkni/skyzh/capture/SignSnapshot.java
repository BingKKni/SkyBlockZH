package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import io.github.bingkkni.skyzh.platform.ServerSignData;
import io.github.bingkkni.skyzh.text.StyledText;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** One raw NBT side, including all four rows even when they are blank or symbolic. */
public record SignSnapshot(String dimension, BlockPos position, String type, String side,
	String dye, boolean glowing, List<StyledText> lines) {
	public SignSnapshot {
		position = position.immutable();
		lines = List.copyOf(lines);
	}

	public static Optional<SignSnapshot> of(String dimension, BlockPos position, String type, String side,
		CompoundTag tag, DynamicOps<Tag> ops) {
		return tag.read(side + "_text", ServerSignData.codec(), ops).map(text ->
			new SignSnapshot(dimension, position, type, side, text.getColor().getName(), text.hasGlowingText(),
				ServerSignData.lines(text).stream().map(StyledText::of).toList()));
	}

	public StyledText text() {
		var joined = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) joined.append("\n");
			StyledText line = lines.get(i);
			joined.append(line.slice(0, line.length()));
		}
		return StyledText.of(joined);
	}

	public JsonObject json() {
		JsonObject json = new JsonObject();
		json.addProperty("dimension", dimension);
		json.addProperty("x", position.getX());
		json.addProperty("y", position.getY());
		json.addProperty("z", position.getZ());
		json.addProperty("block_entity_type", type);
		json.addProperty("side", side);
		json.addProperty("dye", dye);
		json.addProperty("glowing", glowing);
		JsonArray original = new JsonArray();
		for (StyledText text : lines) {
			LegacyText.Encoded encoded = LegacyText.encode(text);
			JsonObject entry = new JsonObject();
			entry.addProperty("raw", encoded.raw());
			if (encoded.lossy()) entry.add("style_runs", LegacyText.styleRuns(encoded.runs()));
			original.add(entry);
		}
		json.add("original_lines", original);
		return json;
	}
}
