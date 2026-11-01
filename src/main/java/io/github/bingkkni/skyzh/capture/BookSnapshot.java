package io.github.bingkkni.skyzh.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bingkkni.skyzh.text.StyledText;
import java.util.List;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

/** All raw server pages, frozen before the inventory or an editor can change them. */
public record BookSnapshot(String kind, String title, String author, int containerId, int slot,
	List<StyledText> pages) {
	public BookSnapshot { pages = List.copyOf(pages); }

	public static BookSnapshot of(DataComponentGetter components, int containerId, int slot) {
		var written = components.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (written != null) {
			return new BookSnapshot("written", written.title().raw(), written.author(), containerId, slot,
				written.pages().stream().map(page -> StyledText.of(page.raw())).toList());
		}
		var writable = components.get(DataComponents.WRITABLE_BOOK_CONTENT);
		return writable == null ? null : new BookSnapshot("writable", "", "", containerId, slot,
			writable.pages().stream().map(page -> StyledText.of(Component.literal(page.raw()))).toList());
	}

	/** A compact queue key includes all pages and styles, without retaining a full book per page alias. */
	public String signature() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(json(-1).toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public JsonObject json(int page) {
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind);
		json.addProperty("title", title);
		json.addProperty("author", author);
		json.addProperty("container_id", containerId);
		json.addProperty("slot", slot);
		json.addProperty("target_page", page + 1);
		json.addProperty("page_count", pages.size());
		JsonArray original = new JsonArray();
		for (StyledText text : page < 0 ? pages : List.of(pages.get(page))) {
			LegacyText.Encoded encoded = LegacyText.encode(text);
			JsonObject entry = new JsonObject();
			entry.addProperty("raw", encoded.raw());
			if (encoded.lossy()) entry.add("style_runs", LegacyText.styleRuns(encoded.runs()));
			original.add(entry);
		}
		if (page < 0) json.add("original_pages", original);
		else json.add("original_page", original.get(0));
		return json;
	}
}
