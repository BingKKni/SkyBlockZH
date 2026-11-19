package io.github.bingkkni.skyzh;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ends a Java process that a closed game left running.
 *
 * <p>A JVM outlives its main thread for as long as any non-daemon thread does, and a mod that starts
 * an executor without ever shutting it down supplies exactly that. Minecraft 26.2 onwards waits
 * fifteen seconds after closing and then kills itself with a crash report; until then the "closed"
 * game still holds every mod JAR open, which is what stops the post-exit installer in
 * {@code update/} from replacing this one. 26.1 exits by itself while closing, so the game thread
 * never ends there and this never acts.
 *
 * <p>So this is a fallback, not a shortcut. It does nothing until the game thread has ended after a
 * normal stop, gives everything else {@link #GRACE} to finish, and only then exits — with status
 * zero, through the ordinary shutdown hooks, having logged which threads it gave up on. A game that
 * exits by itself never reaches that point, and one that died some other way is left to Minecraft.
 */
final class ExitGuard {
	static final Duration GRACE = Duration.ofSeconds(5);
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	/** Never registered: removing it only asks the runtime whether shutdown has begun. */
	private static final Thread PROBE = new Thread(() -> {});

	private ExitGuard() {}

	/** Client initialization only: that runs on the thread whose end means the game finished closing. */
	static void install() {
		watch(Thread.currentThread(), () -> {
			Minecraft minecraft = Minecraft.getInstance();
			return minecraft != null && !minecraft.isRunning();
		}, GRACE, System::exit);
	}

	/** Injected thread, state and exit keep this testable in a child JVM without a game. */
	static Thread watch(Thread game, BooleanSupplier stopped, Duration grace, IntConsumer exit) {
		Thread guard = new Thread(() -> {
			try {
				game.join();
				if (!stopped.getAsBoolean()) return;
				Thread.sleep(grace);
			} catch (InterruptedException e) {
				return;
			}
			List<String> lingering = lingering();
			// Nothing left, or hooks already running: the process is on its way out by itself.
			if (lingering.isEmpty() || shuttingDown()) return;
			LOGGER.warn("游戏已关闭，但仍有 {} 个线程阻止 Java 进程退出：{}。SkyZH 将结束进程。", lingering.size(), String.join("、", lingering));
			exit.accept(0);
		}, "SkyZH-exit-guard");
		guard.setDaemon(true);
		guard.start();
		return guard;
	}

	/** The threads a JVM waits for, minus the one that is doing the waiting. */
	private static List<String> lingering() {
		return Thread.getAllStackTraces().keySet().stream()
			.filter(thread -> thread.isAlive() && !thread.isDaemon() && !thread.getName().equals("DestroyJavaVM"))
			.map(Thread::getName).sorted().toList();
	}

	private static boolean shuttingDown() {
		try {
			Runtime.getRuntime().removeShutdownHook(PROBE);
			return false;
		} catch (IllegalStateException e) {
			return true;
		}
	}
}
