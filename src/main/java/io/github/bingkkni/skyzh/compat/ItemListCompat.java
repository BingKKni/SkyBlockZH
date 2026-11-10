package io.github.bingkkni.skyzh.compat;

import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * SkyBlock Item List's item tooltips.
 *
 * <p>The mod has no API for tooltip text. It builds an item's lines itself and hands them to vanilla's
 * component-list entry, which no vanilla item tooltip uses — so the translation is added at that entry
 * of this mod's own, and only for a call that comes from the list: the same entry also carries entity
 * hover text and other mods' tooltips, none of which are item lore.
 */
public final class ItemListCompat {
	private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("skyblock-item-list");
	private static final String OWNER = "com.operationpotato.itemlist.";
	private static final StackWalker CALLERS = StackWalker.getInstance(java.util.Set.of(StackWalker.Option.DROP_METHOD_INFO));

	private ItemListCompat() {
	}

	/** No third-party class is loaded or changed; the caller is read off the stack, once per hovered frame. */
	public static List<Component> tooltip(List<Component> lines) {
		if (!INSTALLED || !CALLERS.walk(frames -> frames.limit(16).anyMatch(frame -> frame.getClassName().startsWith(OWNER)))) {
			return lines;
		}
		return TooltipTranslator.translate(Minecraft.getInstance().font, lines);
	}
}
