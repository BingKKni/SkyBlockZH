package io.github.bingkkni.skyzh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;

/**
 * The resources the mod contributes without Fabric API.
 *
 * <p>The mod's own assets (language files, icon) go in right above vanilla, so a player's resource
 * packs can still override them; with Fabric API installed they are simply present twice. The 1.12.2
 * Unicode glyphs go on top, exactly where the stand-alone resource pack they come from would sit, and
 * only while {@link SkyZHConfig#legacyFont} is on.
 */
public final class ClientPacks {
	private static final String LEGACY_FONT = "skyzh_legacy_font";

	private ClientPacks() {
	}

	public static List<PackResources> with(List<PackResources> packs) {
		List<PackResources> result = new ArrayList<>(packs);
		Path assets = find("assets");
		if (assets != null) result.add(Math.min(1, result.size()), pack("skyzh", assets.getParent()));
		Path font = find(LEGACY_FONT);
		if (SkyZHConfig.get().legacyFont && font != null && Files.isDirectory(font)) result.add(pack("skyzh/legacy_font", font));
		return result;
	}

	/** Applies a changed text spacing choice; vanilla reloads every pack, which takes a moment. */
	public static void reload() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) minecraft.reloadResourcePacks();
	}

	private static PackResources pack(String id, Path path) {
		return new PathPackResources(new PackLocationInfo(id, Component.literal("SkyZH"), PackSource.BUILT_IN, Optional.empty()), path);
	}

	private static Path find(String name) {
		try {
			return FabricLoader.getInstance().getModContainer(SkyZH.MOD_ID).flatMap(mod -> mod.findPath(name)).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
