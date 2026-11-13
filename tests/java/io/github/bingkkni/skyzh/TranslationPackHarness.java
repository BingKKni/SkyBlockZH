package io.github.bingkkni.skyzh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bingkkni.skyzh.capture.Areas;
import io.github.bingkkni.skyzh.capture.CaptureStore;
import io.github.bingkkni.skyzh.capture.CaptureSurface;
import io.github.bingkkni.skyzh.capture.PreservedText;
import io.github.bingkkni.skyzh.text.ChatDisplayCache;
import io.github.bingkkni.skyzh.text.CorpusData;
import io.github.bingkkni.skyzh.text.ItemNames;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.TranslationPack;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.network.chat.Component;

/** Actual exported bytes plus small independent fixtures for failure and publication behavior. */
public final class TranslationPackHarness {
	private static int passed;

	public static void main(String[] args) throws Exception {
		Path authored = Path.of(args[0]);
		TranslationPack.Content exported = TranslationPack.read(Path.of(args[1]));
		Map<String, JsonObject> source = TranslationHarness.readCorpus(authored);
		check("导出包含全部源文件", exported.files().keySet().equals(source.keySet()));
		TranslationPack.validateCorpus(source);
		TranslationIndex full = TranslationLoader.compile(exported.files());
		check("实际导出可编译", full.size() > 30_000);
		for (String path : CorpusData.FILES) {
			for (var member : exported.files().get(path).entrySet())
				check("辅助数据精简后无损 " + path + "/" + member.getKey(), member.getValue().equals(source.get(path).get(member.getKey())));
		}
		check("导出不再声明 Mod 版本范围", !exported.manifest().has("require_low_version")
			&& !exported.manifest().has("require_max_version"));
		check("导出 API 与引擎完全一致", exported.manifest().get("translation_api").getAsInt() == TranslationPack.api());
		check("导出包结构版本独立校验", exported.manifest().get("format_version").getAsInt() == TranslationPack.FORMAT);
		check("导出时间明确 UTC+8", exported.manifest().get("build_at_utc8").getAsString().endsWith("+08:00"));
		check("修复河豚收藏品引用", full.lookup(Surface.ITEM, "Pufferfish VII") != null);
		check("修复热带鱼收藏品引用", full.lookup(Surface.ITEM, "Tropical Fish III") != null);

		Path temporary = Files.createTempDirectory(Path.of("."), "translation-pack-check-");
		try {
			Map<String, JsonObject> oldFiles = fixture(false);
			Map<String, JsonObject> newFiles = fixture(true);
			Path builtin = temporary.resolve("builtin");
			for (var file : oldFiles.entrySet()) {
				Path path = builtin.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue().toString(), StandardCharsets.UTF_8);
			}
			Path archive = temporary.resolve("translations.zip");
			Path automatic = temporary.resolve("automatic.zip");
			write(automatic, newFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(1)));
			check("无手动包时使用较新自动包且保留文件", TranslationPack.load(archive, automatic, builtin).source() == TranslationPack.Source.AUTOMATIC
				&& Files.isRegularFile(automatic));
			Files.writeString(archive, "broken manual");
			var manualBroken = TranslationPack.load(archive, automatic, builtin);
			check("手动包失败后自动包接替且保留警告", manualBroken.source() == TranslationPack.Source.AUTOMATIC && !manualBroken.warning().isEmpty());
			write(archive, oldFiles, ignored -> {});
			check("手动包始终覆盖自动包", TranslationPack.load(archive, automatic, builtin).source() == TranslationPack.Source.MANUAL
				&& TranslationPack.load(archive, automatic, builtin).index().lookup(Surface.ITEM, "Removed line") != null);
			checkAutomaticSelection(archive, automatic, builtin, oldFiles, newFiles, exported.files());
			Files.writeString(archive, "broken manual");
			Files.writeString(automatic, "broken automatic");
			check("两种外置包都失败后回退内置", TranslationPack.load(archive, automatic, builtin).source() == TranslationPack.Source.BUILTIN);
			Files.delete(archive);
			var absent = TranslationPack.load(archive, builtin);
			check("没有外置包使用内置", !absent.external() && absent.warning().isEmpty());
			write(archive, newFiles, ignored -> {});
			var external = TranslationPack.load(archive, builtin);
			check("有效外置包优先", external.external());
			check("外置正常时无需读取内置目录", TranslationPack.load(archive, null).external());
			check("完整替换不会复活已删除的旧模板", external.index().lookup(Surface.ITEM, "Removed line") == null);
			check("外置编译不会提前发布物品目录", Translator.index() != external.index());
			checkData(external.index(), true);
			checkData(absent.index(), false);

			write(archive, newFiles, meta -> {
				meta.addProperty("require_low_version", "99.0");
				meta.addProperty("require_max_version", "0.1");
			});
			check("遗留 Mod 版本字段不参与兼容判断", TranslationPack.load(archive, builtin).external());
			write(archive, newFiles, meta -> meta.addProperty("translation_api", TranslationPack.api() - 1));
			fallback("较低 API 也不兼容", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("translation_api", TranslationPack.api() + 1));
			fallback("较高 API 不兼容", archive, builtin);
			write(archive, newFiles, meta -> meta.remove("translation_api"));
			fallback("缺少翻译协议", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("translation_api", "4"));
			fallback("API 必须是整数而非字符串", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("translation_api", 4.5));
			fallback("API 不能是小数", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("format_version", 99));
			fallback("未知包格式", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("build_at_utc8", "2026-11-01T12:00:00Z"));
			fallback("拒绝伪 UTC+8 时间", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("file_count", 1));
			fallback("文件数量不符", archive, builtin);
			write(archive, newFiles, meta -> meta.addProperty("content_sha256", "0".repeat(64)));
			fallback("摘要不符", archive, builtin);
			for (String missing : List.of(CorpusData.ITEMS, CorpusData.AREAS, CorpusData.PRESERVED, "_shared/Terms.json", "_shared/SkyBlock_Name.json")) {
				Map<String, JsonObject> broken = new HashMap<>(newFiles);
				broken.remove(missing);
				write(archive, broken, ignored -> {});
				fallback("缺少必要表 " + missing, archive, builtin);
			}
			Map<String, JsonObject> broken = fixture(true);
			broken.put("Test/GUI_Item/Bad.json", json("{\"lines\":[{\"id\":\"bad\",\"ref\":\"_shared/Missing.json#missing\"}]}"));
			write(archive, broken, ignored -> {});
			fallback("缺失共享引用", archive, builtin);
			broken.put("Test/GUI_Item/Bad.json", json("{\"lines\":[{\"id\":\"bad\",\"text\":\"Bad\",\"zh\":\"坏\",\"segments\":[{\"text\":\"Bad\",\"order\":-1}]}]}"));
			write(archive, broken, ignored -> {});
			fallback("错误分段数据", archive, builtin);
			broken.put("Test/GUI_Item/Bad.json", json("""
				{"lines":[{"id":"oversized","text":"Energy: %1500000000$s","zh":"能量: %1500000000$s",
				"placeholders":[{"token":"%1500000000$s","type":"number"}]}]}
				"""));
			write(archive, broken, ignored -> {});
			fallback("超大占位符编号不分配巨量数组", archive, builtin);
			broken = fixture(true);
			broken.get(CorpusData.AREAS).getAsJsonObject("areas").addProperty("Test Harbor", "../../outside");
			write(archive, broken, ignored -> {});
			fallback("区域归类不能逃逸采集目录", archive, builtin);
			Map<String, byte[]> bytes = bytes(newFiles);
			bytes.put("Test/GUI_Item/Bad.json", "{broken".getBytes(StandardCharsets.UTF_8));
			writeBytes(archive, bytes, ignored -> {});
			fallback("摘要正确但 JSON 损坏", archive, builtin);
			bytes.put("Test/GUI_Item/Bad.json", new byte[] {(byte) 0xC0, (byte) 0xAF});
			writeBytes(archive, bytes, ignored -> {});
			fallback("非法 UTF-8", archive, builtin);
			bytes = bytes(newFiles);
			bytes.put("../outside.json", "{}".getBytes(StandardCharsets.UTF_8));
			writeBytes(archive, bytes, ignored -> {});
			fallback("非法归档路径", archive, builtin);
			Files.writeString(archive, "not a zip");
			fallback("损坏 ZIP", archive, builtin);
			checkPublication(absent, external, () -> TranslationPack.load(archive, temporary.resolve("missing")));
			checkQueuedCapture(temporary.resolve("capture"), absent.index(), external.index());
			write(archive, newFiles, ignored -> {});
			TranslationPack.read(archive);
			Files.delete(archive);
			check("读取结束已释放 ZIP，可直接删除替换", !Files.exists(archive));
		} finally {
			try (var walk = Files.walk(temporary)) {
				for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
		System.out.println("翻译包检查通过: " + passed + " 项");
	}

	private static void checkAutomaticSelection(Path manual, Path automatic, Path builtin,
		Map<String, JsonObject> oldFiles, Map<String, JsonObject> newFiles, Map<String, JsonObject> exported) throws Exception {
		Files.delete(manual);
		write(automatic, newFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(-1)));
		var loaded = TranslationPack.load(manual, automatic, builtin);
		check("新版 Mod 内置包接替较旧自动包且使用内置译文", loaded.source() == TranslationPack.Source.BUILTIN
			&& Translator.withIndex(loaded.index(), () -> Translator.translateAvailable(Component.literal("Reload marker"), Surface.ITEM)
				.core().getString().equals("旧译文")));
		check("内置包编译成功后清理过时自动包", !Files.exists(automatic));
		check("清理后重载仍使用内置包", TranslationPack.load(manual, automatic, builtin).source() == TranslationPack.Source.BUILTIN);
		if (System.getProperty("os.name").startsWith("Windows")) {
			write(automatic, newFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(-1)));
			Files.setAttribute(automatic, "dos:readonly", true);
			try {
				loaded = TranslationPack.load(manual, automatic, builtin);
				check("自动包只读无法删除仍成功使用内置翻译", loaded.source() == TranslationPack.Source.BUILTIN
					&& loaded.warning().isEmpty() && Files.isRegularFile(automatic));
				check("清理失败残留旧包不干扰更新检查", io.github.bingkkni.skyzh.update.TranslationUpdate.baseline(automatic)
					.equals(TranslationPack.builtinManifest()));
			} finally { Files.setAttribute(automatic, "dos:readonly", false); }
		}

		write(automatic, newFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(0)));
		check("同时间不同内容优先内置包并清理自动包", TranslationPack.load(manual, automatic, builtin).source() == TranslationPack.Source.BUILTIN
			&& !Files.exists(automatic));
		write(automatic, exported, meta -> meta.addProperty("build_at_utc8", builtinTime(1)));
		check("相同内容重新打包优先内置且清理冗余自动包", TranslationPack.load(manual, automatic, builtin).source() == TranslationPack.Source.BUILTIN
			&& !Files.exists(automatic));

		write(automatic, newFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(-1)));
		loaded = TranslationPack.load(manual, automatic, builtin.resolve("missing"));
		check("内置包无法加载时保留较旧但可用的自动包", loaded.source() == TranslationPack.Source.AUTOMATIC
			&& !loaded.warning().isEmpty() && Files.isRegularFile(automatic));
		Path broken = builtin.resolveSibling("broken-builtin");
		Files.createDirectories(broken);
		Files.writeString(broken.resolve("Bad.json"), "not json");
		loaded = TranslationPack.load(manual, automatic, broken);
		check("内置 JSON 损坏不删除可用自动包", loaded.source() == TranslationPack.Source.AUTOMATIC
			&& !loaded.warning().isEmpty() && Files.isRegularFile(automatic));

		write(manual, oldFiles, meta -> meta.addProperty("build_at_utc8", builtinTime(-2)));
		byte[] manualBytes = Files.readAllBytes(manual);
		check("手动包即使更旧也优先且不被清理", TranslationPack.load(manual, automatic, builtin).source() == TranslationPack.Source.MANUAL
			&& java.util.Arrays.equals(manualBytes, Files.readAllBytes(manual)));
		loaded = TranslationPack.removeManualAndLoad(manual, automatic, builtin);
		check("主动卸载手动包后选择内置并清理旧自动包", loaded.source() == TranslationPack.Source.BUILTIN
			&& !Files.exists(manual) && !Files.exists(automatic));
		write(manual, oldFiles, ignored -> {});
	}

	static String builtinTime(int days) {
		try {
			return java.time.OffsetDateTime.parse(TranslationPack.builtinManifest().get("build_at_utc8").getAsString())
				.plusDays(days).toString();
		} catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
	}

	private static void checkData(TranslationIndex index, boolean newer) {
		Translator.withIndex(index, () -> {
			String item = newer ? "New Relic" : "Old Relic";
			check("物品目录随索引切换 " + newer, ItemNames.canonical(item) != null
				&& ItemNames.canonical(newer ? "Old Relic" : "New Relic") == null);
			check("受目录约束的模板使用同代数据 " + newer, index.lookup(Surface.ITEM, "Inspect " + item) != null
				&& index.lookup(Surface.ITEM, "Inspect " + (newer ? "Old Relic" : "New Relic")) == null);
			check("地点表随索引切换 " + newer, Areas.get().gameplay("Test Harbor").equals(newer ? "Foraging" : "Fishing"));
			check("专名表随索引切换 " + newer, PreservedText.enchantmentNames().contains(newer ? "New Charm" : "Old Charm"));
			check("档案身份表随索引切换 " + newer, PreservedText.fruitProfileLine("Fruit Bowl", newer ? "NewFruit" : "Apple"));
			check("词表使用同代译文 " + newer, index.terms().translate("location_name", "Test Harbor").equals(newer ? "新港" : "旧港"));
			check("渲染使用同代译文 " + newer, Translator.translateAvailable(Component.literal("Reload marker"), Surface.ITEM).core().getString().equals(newer ? "新译文" : "旧译文"));
			return null;
		});
	}

	private static void checkPublication(TranslationPack.Loaded old, TranslationPack.Loaded next,
		Supplier<TranslationPack.Loaded> broken) throws Exception {
		TranslationHarness.installIndex(old.index());
		LinkedBlockingQueue<Runnable> publications = new LinkedBlockingQueue<>();
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicReference<Exception> error = new AtomicReference<>();
		Component marker = Component.literal("Reload marker");
		SkyZHConfig.get().enabled = true;
		String before = Translator.translateLine(marker, Surface.ITEM).getString();
		ChatDisplayCache chat = new ChatDisplayCache();
		chat.needsRefresh(true, true, old.index());
		int generation = SkyZHConfig.generation();
		var remember = io.github.bingkkni.skyzh.hook.ForceChat.class.getDeclaredMethod("rememberServer", Component.class, int.class);
		remember.setAccessible(true);
		remember.invoke(null, Component.literal("Server history"), 1);
		var history = io.github.bingkkni.skyzh.hook.ForceChat.class.getDeclaredField("MESSAGES");
		history.setAccessible(true);
		List<?> messages = (List<?>) history.get(null);
		int historySize = messages.size();
		io.github.bingkkni.skyzh.hook.ForceChat.scrolled(7, true);
		check("首次请求被接受", request(() -> { started.countDown(); await(release); return next; }, publications, error));
		await(started);
		check("重载不会关闭 SkyBlock 加载门控", !Translator.loading());
		check("并发请求被拒绝", !request(() -> next, publications, error));
		check("读取期间继续使用旧译文", Translator.index() == old.index()
			&& Translator.translateLine(marker, Surface.ITEM).getString().equals(before));
		release.countDown();
		Runnable publication = take(publications);
		check("排队发布之前仍使用旧索引", Translator.index() == old.index());
		CountDownLatch pinned = new CountDownLatch(1), switched = new CountDownLatch(1), finished = new CountDownLatch(1);
		AtomicReference<Throwable> workerError = new AtomicReference<>();
		Thread.ofPlatform().daemon().start(() -> {
			try (var ignored = Translator.useIndex(old.index())) {
				pinned.countDown();
				await(switched);
				if (Translator.index() != old.index() || ItemNames.canonical("New Relic") != null
					|| !Areas.get().gameplay("Test Harbor").equals("Fishing")) throw new AssertionError("后台操作混用新旧语料");
			} catch (Throwable e) { workerError.set(e); }
			finally { finished.countDown(); }
		});
		await(pinned);
		HoldOriginal.setActive(true);
		SkyZHConfig.get().captureUntranslated = true;
		publication.run();
		switched.countDown();
		await(finished);
		check("后台处理中途发布仍保留完整旧快照", workerError.get() == null);
		check("发布后所有数据一起生效", Translator.index() == next.index() && ItemNames.canonical("New Relic") != null);
		check("发布刷新缓存代际", SkyZHConfig.generation() > generation);
		check("重载保留原文模式且继续显示原文", HoldOriginal.active() && Translator.translateLine(marker, Surface.ITEM) == marker);
		check("重载不修改功能和采集开关", SkyZHConfig.get().enabled && SkyZHConfig.get().captureUntranslated);
		check("重载保留独立聊天历史及滚动状态", messages.size() == historySize
			&& io.github.bingkkni.skyzh.hook.ForceChat.scroll() == 7 && io.github.bingkkni.skyzh.hook.ForceChat.unread());
		HoldOriginal.setActive(false);
		SkyZHConfig.get().captureUntranslated = false;
		check("相同输入不再命中旧译文缓存", Translator.translateLine(marker, Surface.ITEM).getString().equals("新译文"));
		check("已有聊天需要重新排版", chat.needsRefresh(true, true, next.index()));
		check("成功不报告失败", error.get() == null);
		check("完成后可以再次请求", request(broken, publications, error));
		take(publications).run();
		check("两个来源都失败时保留上一份有效索引", error.get() != null && Translator.index() == next.index());
		check("失败释放忙状态", request(() -> old, publications, error));
		take(publications).run();
		check("删除外置包后可以切回内置", Translator.index() == old.index());
		checkData(Translator.index(), false);
	}

	private static void checkQueuedCapture(Path directory, TranslationIndex old, TranslationIndex next) throws Exception {
		CaptureStore.clear(directory);
		CaptureStore.root(directory);
		try {
			TranslationHarness.installIndex(old);
			String line = "A queued sentence awaits review";
			CaptureStore.offer(new CaptureStore.Sighting(CaptureSurface.GUI_ITEM, "queued-corpus",
				new CaptureStore.Line(io.github.bingkkni.skyzh.text.StyledText.of(Component.literal(line))),
				Areas.get().gameplay("Test Harbor"), "Test Harbor", "Queue", "queued before reload", 1L));
			TranslationHarness.installIndex(next);
			check("新语料已覆盖排队原文且区域归类已改变", next.lookup(Surface.ITEM, line) != null
				&& Areas.get().gameplay("Test Harbor").equals("Foraging"));
			var queueField = CaptureStore.class.getDeclaredField("QUEUE");
			queueField.setAccessible(true);
			var queue = (java.util.concurrent.BlockingQueue<?>) queueField.get(null);
			Object queued = queue.poll();
			check("实际采集队列保留待处理事件", queued != null);
			var consume = CaptureStore.class.getDeclaredMethod("accept", queued.getClass());
			consume.setAccessible(true);
			consume.invoke(null, queued);
			CaptureStore.flush();
			Path output = directory.resolve("untranslated/Fishing/GUI_Item/Queue.json");
			check("重载前排队事件按旧索引和旧区域表一起分类落盘", Files.isRegularFile(output)
				&& Files.readString(output, StandardCharsets.UTF_8).contains(line));
			check("处理旧事件不会回滚当前索引", Translator.index() == next && ItemNames.canonical("New Relic") != null);
		} finally {
			CaptureStore.clear(directory);
			CaptureStore.root(null);
		}
	}

	private static boolean request(Supplier<TranslationPack.Loaded> read, LinkedBlockingQueue<Runnable> queue,
		AtomicReference<Exception> error) throws Exception {
		var method = Translator.class.getDeclaredMethod("requestReload", Supplier.class, java.util.concurrent.Executor.class, Consumer.class, Consumer.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, read, (java.util.concurrent.Executor) queue::add,
			(Consumer<TranslationPack.Loaded>) ignored -> {}, (Consumer<Exception>) error::set);
	}

	private static Runnable take(LinkedBlockingQueue<Runnable> queue) throws InterruptedException {
		Runnable value = queue.poll(10, TimeUnit.SECONDS);
		if (value == null) throw new AssertionError("重载未提交结果");
		return value;
	}

	private static void await(CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("线程未到达检查点"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	private static void fallback(String label, Path archive, Path builtin) {
		var loaded = TranslationPack.load(archive, builtin);
		check(label + "整包回退并报告原因", !loaded.external() && !loaded.warning().isEmpty());
		check(label + "回退包含原物品目录", Translator.withIndex(loaded.index(), () -> ItemNames.canonical("Old Relic") != null));
	}

	static Map<String, JsonObject> fixture(boolean newer) {
		Map<String, JsonObject> files = new HashMap<>();
		files.put(CorpusData.ITEMS, json("{\"names\":[\"" + (newer ? "New Relic" : "Old Relic") + "\"],\"pets\":[],\"reforges\":[]}"));
		files.put(CorpusData.AREAS, json("{\"unknown\":\"_Unknown_Gameplay\",\"modes\":{},\"areas\":{\"Test Harbor\":\"" + (newer ? "Foraging" : "Fishing") + "\"}}"));
		files.put(CorpusData.PRESERVED, json("{\"enchantments\":[\"" + (newer ? "New Charm" : "Old Charm") + "\"],\"profile_names\":[\"" + (newer ? "NewFruit" : "Apple") + "\"]}"));
		files.put("_shared/SkyBlock_Name.json", json("{\"full\":\"空岛生存\",\"short\":\"空岛\",\"compound_stoplist\":\"的\"}"));
		files.put("_shared/Terms.json", json("{\"applies_to_types\":[\"location_name\"],\"terms\":[{\"en\":\"Test Harbor\",\"zh\":\"" + (newer ? "新港" : "旧港") + "\"}]}"));
		files.put("Test/GUI_Item/Fixture.json", json("{\"lines\":[{\"id\":\"marker\",\"text\":\"Reload marker\",\"zh\":\"" + (newer ? "新译文" : "旧译文")
			+ "\"},{\"id\":\"inspect\",\"text\":\"Inspect %s\",\"zh\":\"查看 %s\",\"placeholders\":[{\"token\":\"%s\",\"type\":\"catalog_item_name\"}]}"
			+ (newer ? ",{\"id\":\"queued\",\"text\":\"A queued sentence awaits review\",\"zh\":\"等待复查的排队句子\"}"
				: ",{\"id\":\"removed\",\"text\":\"Removed line\",\"zh\":\"已删除\"}") + "]}"));
		return files;
	}

	private static Map<String, byte[]> bytes(Map<String, JsonObject> files) {
		Map<String, byte[]> result = new TreeMap<>();
		files.forEach((path, json) -> result.put(path, json.toString().getBytes(StandardCharsets.UTF_8)));
		return result;
	}

	static void write(Path archive, Map<String, JsonObject> files, Consumer<JsonObject> mutate) throws Exception {
		writeBytes(archive, bytes(files), mutate);
	}

	private static void writeBytes(Path archive, Map<String, byte[]> files, Consumer<JsonObject> mutate) throws Exception {
		JsonObject manifest = json("{\"format_version\":1,\"build_at_utc8\":\"2026-11-01T12:00:00+08:00\"}");
		manifest.addProperty("translation_api", TranslationPack.api());
		manifest.addProperty("revision", "a".repeat(40));
		manifest.addProperty("file_count", files.size());
		manifest.addProperty("content_sha256", TranslationPack.checksum(files));
		mutate.accept(manifest);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			for (var file : files.entrySet()) {
				zip.putNextEntry(new ZipEntry("original_text/" + file.getKey()));
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}
	}

	private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
	private static void check(String label, boolean success) {
		if (!success) throw new AssertionError(label);
		passed++;
		System.out.println("  ✓ " + label);
	}
}
