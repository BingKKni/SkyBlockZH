package io.github.bingkkni.skyzh.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

/** Standalone JDK-only process. It never opens the running Mod JAR for writing. */
public final class ModInstaller {
	private ModInstaller() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Expected installation plan");
		Path planFile = Path.of(args[0]).toAbsolutePath().normalize();
		Path directory = planFile.getParent();
		boolean ownsReady = false;
		try (FileChannel channel = FileChannel.open(directory.resolve("installer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			var lock = channel.tryLock()) {
			if (lock == null) return;
			Properties plan = read(planFile);
			long pid = Long.parseLong(plan.getProperty("pid"));
			Instant start = Instant.parse(plan.getProperty("process_start"));
			ProcessHandle parent = ProcessHandle.of(pid).orElse(null);
			if (parent == null || !parent.info().startInstant().orElse(Instant.MIN).equals(start))
				throw new IOException("Game process identity changed");
			plan.setProperty("installer_pid", Long.toString(ProcessHandle.current().pid()));
			plan.setProperty("installer_start", ProcessHandle.current().info().startInstant().orElseThrow().toString());
			plan.setProperty("status", "waiting");
			write(planFile, plan);
			ownsReady = true;
			Files.writeString(directory.resolve("installer.ready"), Long.toString(pid));
			parent.onExit().get();
			Path installed = Path.of(plan.getProperty("installed")).toAbsolutePath().normalize();
			Path candidate = directory.resolve("mod-update.jar");
			String expectedOld = plan.getProperty("old_sha256"), expectedNew = plan.getProperty("new_sha256");
			if (!installed.getFileName().toString().endsWith(".jar") || !expectedOld.matches("[0-9a-f]{64}")
				|| !expectedNew.matches("[0-9a-f]{64}")) throw new IOException("Invalid installation plan");
			if (hash(installed).equals(expectedNew)) { plan.setProperty("status", "installed"); write(planFile, plan); return; }
			if (!hash(candidate).equals(expectedNew) || !hash(installed).equals(expectedOld))
				throw new IOException("Installed or downloaded JAR changed; refusing to replace");
			// Keep the original filename, so Fabric never discovers both versions as duplicate mods.
			Path staging = installed.resolveSibling(installed.getFileName() + ".skyzh-new");
			Path backup = directory.resolve("previous-mod.jar");
			try {
				Files.copy(candidate, staging, StandardCopyOption.REPLACE_EXISTING);
				if (!hash(staging).equals(expectedNew)) throw new IOException("Staged JAR checksum mismatch");
				Files.copy(installed, backup, StandardCopyOption.REPLACE_EXISTING);
				IOException failure = null;
				for (int attempt = 0; attempt < 120; attempt++) {
					// If the user or a launcher changed the file, do not overwrite their choice.
					if (!hash(installed).equals(expectedOld)) throw new IOException("Installed JAR changed during update");
					try { move(staging, installed); failure = null; break; }
					catch (IOException e) {
						// A filesystem without atomic rename may remove the target before reporting failure.
						if (Files.notExists(installed)) {
							Files.copy(backup, installed, StandardCopyOption.REPLACE_EXISTING);
							throw new IOException("Replacement failed; restored backup", e);
						}
						failure = e; Thread.sleep(500);
					}
				}
				if (failure != null) throw failure;
				if (!hash(installed).equals(expectedNew)) {
					Files.copy(backup, installed, StandardCopyOption.REPLACE_EXISTING);
					throw new IOException("Installed JAR verification failed; restored backup");
				}
				plan.setProperty("status", "installed");
				write(planFile, plan);
			} finally { Files.deleteIfExists(staging); }
		} catch (Exception e) {
			Properties plan = read(planFile);
			plan.setProperty("status", "failed");
			plan.setProperty("error", e.toString());
			write(planFile, plan);
			e.printStackTrace();
			System.exit(1);
		} finally { if (ownsReady) Files.deleteIfExists(directory.resolve("installer.ready")); }
	}

	private static Properties read(Path file) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
		return properties;
	}

	private static void write(Path file, Properties properties) throws IOException {
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "SkyZH installation state"); }
		move(temporary, file);
	}

	private static void move(Path source, Path target) throws IOException {
		try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
		catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
	}

	private static String hash(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = Files.newInputStream(path)) {
			byte[] bytes = new byte[64 * 1024];
			for (int length; (length = input.read(bytes)) >= 0;) digest.update(bytes, 0, length);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
