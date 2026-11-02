package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TranslationPack;
import io.github.bingkkni.skyzh.text.Translator;
import io.github.bingkkni.skyzh.update.UpdateActions;
import io.github.bingkkni.skyzh.update.UpdateActions.Action;
import io.github.bingkkni.skyzh.update.UpdateFiles;
import io.github.bingkkni.skyzh.update.UpdateMessages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Exact player text, interaction boundaries, retry scheduling and irreversible manual-pack removal. */
public final class UpdateInteractionHarness {
	private static int passed;
	private static final String COMMAND = "/skyzh updateaction example";

	public static int run(Path root) throws Exception {
		messages();
		actions();
		retries();
		uninstall(root.resolve("manual-removal"));
		return passed;
	}

	private static void messages() {
		check("翻译成功文案", legacy(UpdateMessages.translationUpdated(false, "")).equals("§a翻译更新成功!"));
		check("手动包覆盖提示的文字和颜色", legacy(UpdateMessages.translationUpdated(true, COMMAND)).equals(
			"§a翻译更新成功! §e但由于你手动安装了外置翻译包，新安装的翻译不会生效。§c[点击卸载外置翻译包]"));
		check("重启安装统一文案", legacy(UpdateMessages.modDownloaded("0.9")).equals("§a已下载 SkyZH 0.9，下次重启时安装。"));
		check("不兼容文案", legacy(UpdateMessages.incompatible(COMMAND)).equals(
			"§e发现新版本翻译包，但它与此计算机上的 SkyZH 不兼容，请更新 Mod。§a[点击这里下载]"));
		check("翻译失败文案", legacy(UpdateMessages.translationFailed(COMMAND)).equals("§e更新翻译包时出错，将在1小时后重试。§6[立即重试]"));
		check("Mod 失败文案", legacy(UpdateMessages.modFailed(COMMAND)).equals("§e更新 SkyZH 时出错，将在1小时后重试。§6[立即重试]"));
		check("检查失败文案", legacy(UpdateMessages.checkFailed(COMMAND)).equals("§c检查更新时出错。§a[重试]"));
		check("统一浅蓝色前缀", legacy(UpdateMessages.chat(UpdateMessages.translationUpdated(false, ""))).equals("§b[SkyZH] §a翻译更新成功!"));
		scope(UpdateMessages.translationUpdated(true, COMMAND), 0xFF5555, "点击卸载外置翻译包，此操作不可撤销!");
		scope(UpdateMessages.incompatible(COMMAND), 0x55FF55, "点击这里来下载新版 SkyZH!");
		scope(UpdateMessages.translationFailed(COMMAND), 0xFFAA00, "点击来尝试再次安装新版翻译包!");
		scope(UpdateMessages.modFailed(COMMAND), 0xFFAA00, "点击来尝试再次安装新版 SkyZH!");
		scope(UpdateMessages.checkFailed(COMMAND), 0x55FF55, "点击来尝试再次检查更新!");
		for (boolean mod : List.of(false, true)) for (boolean translation : List.of(false, true)) {
			check("只有三条件同时成立才提醒更新 Mod " + mod + translation,
				UpdateChecker.needsModNotice(mod, translation, 5, 4) == (mod && translation));
			check("API 相同不提醒更新 Mod " + mod + translation, !UpdateChecker.needsModNotice(mod, translation, 4, 4));
		}
		check("较旧 API 也复用同一提示条件", UpdateChecker.needsModNotice(true, true, 3, 4));
		check("同版本 Mod 不构成新 Mod", !UpdateChecker.isNewer("0.8", "0.8+mc26.2"));
	}

	private static void scope(Component message, int color, String hover) {
		Component chat = UpdateMessages.chat(message);
		for (Component display : List.of(chat, Translator.translateChatBlock(chat, 320, c -> StyledText.of(c).length() * 6))) {
			StyledText styled = StyledText.of(display);
			int button = styled.plain().lastIndexOf('[');
			boolean correct = button > 0;
			for (int i = 0; i < styled.length(); i++) {
				var style = styled.styleAt(i);
				if (i < button) correct &= style.getClickEvent() == null && style.getHoverEvent() == null;
				else correct &= new ClickEvent.RunCommand(COMMAND).equals(style.getClickEvent())
					&& style.getColor() != null && style.getColor().getValue() == color
					&& style.getHoverEvent() instanceof HoverEvent.ShowText text && legacy(text.value()).equals("§e" + hover);
			}
			check("仅按钮携带点击和黄色悬浮说明 " + message.getString(), correct);
		}
	}

	private static void actions() {
		UpdateActions actions = new UpdateActions();
		AtomicInteger calls = new AtomicInteger();
		check("伪造按钮令牌不执行", !actions.run("forged", action -> { calls.incrementAndGet(); return true; }) && calls.get() == 0);
		String retry = token(actions.issue(Action.TRANSLATIONS));
		check("重试未受理时保留按钮", !actions.run(retry, action -> false));
		check("重试准确路由到翻译流程", actions.run(retry, action -> action == Action.TRANSLATIONS));
		check("已受理的重试按钮不能重放", !actions.run(retry, action -> true));
		String fresh = token(actions.issue(Action.TRANSLATIONS));
		check("再次失败生成的新按钮仍可重试", actions.run(fresh, action -> action == Action.TRANSLATIONS));
		String remove = token(actions.issue(Action.REMOVE_MANUAL));
		check("重载忙时不消费卸载按钮", !actions.run(remove, action -> false));
		check("卸载按钮可在空闲后执行", actions.run(remove, action -> action == Action.REMOVE_MANUAL));
		check("卸载准备失败后按钮仍可重试", !actions.run(remove, action -> { calls.incrementAndGet(); return false; }) && calls.get() == 1);
		actions.complete(remove);
		check("成功卸载的按钮不能再次执行", !actions.run(remove, action -> true));
		String expired = token(actions.issue(Action.CHECK));
		for (int i = 0; i < 128; i++) actions.issue(Action.CHECK);
		check("按钮令牌记录有容量上限", !actions.run(expired, action -> true));
	}

	private static void retries() {
		AtomicBoolean busy = new AtomicBoolean();
		AtomicInteger calls = new AtomicInteger();
		LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
		check("自动检查遵守冷却", !UpdateChecker.launch(true, false, false, busy, calls::incrementAndGet, queue::add));
		check("点击重试跳过冷却", UpdateChecker.launch(true, false, true, busy, calls::incrementAndGet, queue::add));
		check("重复点击不会产生并发更新", !UpdateChecker.launch(true, true, true, busy, calls::incrementAndGet, queue::add));
		queue.remove().run();
		check("重试只执行一次并释放忙状态", calls.get() == 1 && !busy.get());
		check("关闭更新后按钮不启动下载", !UpdateChecker.launch(false, false, true, busy, calls::incrementAndGet, queue::add));
		check("到期的自动检查复用同一入口", UpdateChecker.launch(true, true, false, busy, calls::incrementAndGet, queue::add));
		queue.remove().run();
		check("自动检查可正常完成", calls.get() == 2 && !busy.get());
		try {
			UpdateChecker.launch(true, true, true, busy, () -> {}, task -> { throw new IllegalStateException("executor stopped"); });
			throw new AssertionError("应拒绝无法启动的后台任务");
		} catch (IllegalStateException expected) { check("后台任务启动失败也释放忙状态", !busy.get()); }
	}

	private static void uninstall(Path directory) throws Exception {
		Path manual = directory.resolve("manual/translations.zip"), automatic = directory.resolve("updates/translations.zip");
		Files.createDirectories(manual.getParent()); Files.createDirectories(automatic.getParent());
		TranslationPackHarness.write(manual, TranslationPackHarness.fixture(false), ignored -> {});
		TranslationPackHarness.write(automatic, TranslationPackHarness.fixture(true), ignored -> {});
		Path note = manual.resolveSibling("notes.txt"); Files.writeString(note, "keep me");
		String automaticHash = UpdateFiles.sha256(automatic);
		var selected = TranslationPack.load(manual, automatic, null);
		var previous = Translator.index();
		LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
		AtomicReference<Exception> error = new AtomicReference<>();
		AtomicReference<TranslationPack.Loaded> loaded = new AtomicReference<>();
		java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
		check("先占用正常重载流程", reload(() -> { entered.countDown(); await(release); return selected; }, queue, loaded::set, error));
		await(entered);
		check("重载忙时拒绝卸载且不删除文件", !reload(() -> remove(manual, automatic), queue, loaded::set, error) && Files.exists(manual));
		release.countDown(); take(queue).run();
		int generation = SkyZHConfig.generation();
		var manualIndex = Translator.index();
		check("卸载复用真实重载锁", reload(() -> remove(manual, automatic), queue, loaded::set, error));
		Runnable publication = take(queue);
		check("卸载后等待发布时仍保留旧索引", !Files.exists(manual) && Translator.index() == manualIndex);
		publication.run();
		check("卸载完成立即加载自动包", error.get() == null && loaded.get().source() == TranslationPack.Source.AUTOMATIC
			&& Translator.index() == loaded.get().index() && SkyZHConfig.generation() > generation);
		check("仅删除固定手动包，保留自动包和其他文件", Files.exists(note) && UpdateFiles.sha256(automatic).equals(automaticHash));
		check("手动包已删除时可重复正常重载", remove(manual, automatic).source() == TranslationPack.Source.AUTOMATIC);
		Files.createDirectory(manual);
		boolean refused = false;
		try { TranslationPack.removeManualAndLoad(manual, automatic, null); } catch (java.io.IOException expected) { refused = true; }
		check("不把目录误当手动包删除", refused && Files.isDirectory(manual));
		error.set(null);
		check("发布回调错误的重载可启动", reload(() -> selected, queue, result -> { throw new IllegalStateException("publication failed"); }, error));
		take(queue).run();
		check("发布回调失败会交付错误并释放重载锁", error.get() != null && reload(() -> selected, queue, ignored -> {}, new AtomicReference<>()));
		take(queue).run();
		TranslationHarness.installIndex(previous);
	}

	private static TranslationPack.Loaded remove(Path manual, Path automatic) {
		try { return TranslationPack.removeManualAndLoad(manual, automatic, null); }
		catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
	}

	private static boolean reload(Supplier<TranslationPack.Loaded> supplier, LinkedBlockingQueue<Runnable> queue,
		Consumer<TranslationPack.Loaded> success, AtomicReference<Exception> error) throws Exception {
		var method = Translator.class.getDeclaredMethod("requestReload", Supplier.class, java.util.concurrent.Executor.class, Consumer.class, Consumer.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, supplier, (java.util.concurrent.Executor) queue::add, success, (Consumer<Exception>) error::set);
	}
	private static Runnable take(LinkedBlockingQueue<Runnable> queue) throws Exception {
		Runnable result = queue.poll(10, TimeUnit.SECONDS);
		if (result == null) throw new AssertionError("重载未交付结果");
		return result;
	}
	private static void await(java.util.concurrent.CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("等待重载超时"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
	private static String token(String command) { return command.substring(command.lastIndexOf(' ') + 1); }
	private static String legacy(Component text) { return TranslationHarness.legacy(text); }
	private static void check(String label, boolean ok) { if (!ok) throw new AssertionError(label); passed++; System.out.println("  ✓ " + label); }
}
