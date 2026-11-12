package io.github.bingkkni.skyzh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

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

	static PackResources pack(String id, Path root) {
		return new JarPack(new PackLocationInfo(id, Component.literal("SkyZH"), PackSource.BUILT_IN, Optional.empty()), root);
	}

	private static Path find(String name) {
		try {
			return FabricLoader.getInstance().getModContainer(SkyZH.MOD_ID).flatMap(mod -> mod.findPath(name)).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * One directory of the mod's jar, read as a resource pack.
	 *
	 * <p>Its own class rather than vanilla's {@code PathPackResources}, because other clients patch that
	 * one on the assumption that such a pack is a folder on disk. Lunar Client reads the folder's name
	 * on every lookup, and the root of a jar, where the mod's own assets are, has none: each lookup
	 * threw, the resource reload failed and the game stayed on a black screen. Nothing here calls into
	 * that class, static helpers included.
	 */
	private record JarPack(PackLocationInfo location, Path root) implements PackResources {
		@Override
		public IoSupplier<InputStream> getRootResource(String... path) {
			FileUtil.validatePath(path);
			return file(FileUtil.resolvePath(this.root, List.of(path)));
		}

		@Override
		public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
			Path top = this.root.resolve(type.getDirectory()).resolve(id.getNamespace());
			// An identifier may spell "..": decomposePath refuses it, so a lookup stays inside its namespace.
			return FileUtil.decomposePath(id.getPath()).result().map(path -> file(FileUtil.resolvePath(top, path))).orElse(null);
		}

		@Override
		public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
			List<String> path = FileUtil.decomposePath(directory).result().orElse(null);
			if (path == null) return;
			Path top = this.root.resolve(type.getDirectory()).resolve(namespace);
			Path start = FileUtil.resolvePath(top, path);
			try (Stream<Path> entries = Files.find(start, Integer.MAX_VALUE, (entry, attributes) -> attributes.isRegularFile())) {
				entries.forEach(entry -> {
					// Identifiers use '/', whichever separator the file system holding the mod uses.
					String name = top.relativize(entry).toString().replace(top.getFileSystem().getSeparator(), "/");
					Identifier id = Identifier.tryBuild(namespace, name);
					if (id != null) output.accept(id, IoSupplier.create(entry));
				});
			} catch (NoSuchFileException | NotDirectoryException e) {
				// This pack has nothing under that directory, which is the usual answer.
			} catch (IOException e) {
				LOGGER.warn("SkyZH 内置资源无法列出，已跳过: {}", start, e);
			}
		}

		@Override
		public Set<String> getNamespaces(PackType type) {
			Set<String> namespaces = new HashSet<>();
			try (DirectoryStream<Path> directories = Files.newDirectoryStream(this.root.resolve(type.getDirectory()), Files::isDirectory)) {
				for (Path directory : directories) {
					String name = directory.getFileName().toString();
					if (Identifier.isValidNamespace(name)) namespaces.add(name);
				}
			} catch (NoSuchFileException | NotDirectoryException e) {
				// This pack has no resources of that type.
			} catch (IOException e) {
				LOGGER.warn("SkyZH 内置资源无法读取，已跳过: {}", this.root, e);
			}
			return namespaces;
		}

		/** Always absent: neither directory carries a pack.mcmeta, these not being packs a player selects. */
		@Override
		public <T> T getMetadataSection(MetadataSectionType<T> type) {
			return null;
		}

		@Override
		public void close() {
			// The jar is Fabric Loader's and stays open.
		}

		private static IoSupplier<InputStream> file(Path path) {
			return Files.isRegularFile(path) ? IoSupplier.create(path) : null;
		}
	}
}
