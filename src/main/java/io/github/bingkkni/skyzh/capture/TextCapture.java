package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.compat.HypixelApi;
import io.github.bingkkni.skyzh.hook.NameTag;
import io.github.bingkkni.skyzh.text.ChatTranslation;
import io.github.bingkkni.skyzh.text.LineShape;
import io.github.bingkkni.skyzh.text.LoreTranslation;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.Translator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one door into runtime capture. Every hook calls a method here and nothing else.
 *
 * <p><b>What this class exists to get right.</b> An earlier attempt at this feature was abandoned
 * because it captured other mods' text along with Hypixel's, and no amount of filtering afterwards
 * could separate them — by the time text reaches a screen it is just text, and a line SkyHanni drew
 * looks exactly like a line the server sent. The mistake was where it hooked, not how it filtered.
 *
 * <p>So every caller of this class is a <em>packet handler</em> or a read of state only a packet can
 * write: {@code handleSystemChat} before Fabric's chat events and before any mod has seen the
 * message; {@code ItemStack}'s own {@code LORE} component rather than the tooltip list every mod adds
 * lines to; the {@code Scoreboard} the client keeps rather than whatever is drawn where the sidebar
 * used to be. A mod's own message goes into the chat through {@code ChatComponent#addMessage} and
 * never passes a packet handler, so it is not filtered out here — it is never seen. That is a
 * structural guarantee rather than a best effort, and it is why filtering by name was not attempted:
 * {@code [Bazaar]} and {@code [Sacks]} are Hypixel's own prefixes, and a blocklist of mod-shaped tags
 * would throw away real SkyBlock text to catch something that cannot arrive anyway.
 *
 * <p>The rendering hooks in {@code mixin/} are untouched by all of this. Capture reads; it never
 * changes a byte of what is drawn.
 */
public final class TextCapture {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");

	/**
	 * What separates the parts of a deduplication key.
	 *
	 * <p>A character no piece of game text can hold, so a name that ends in one word and a note that
	 * begins with another cannot collide with a name that happens to contain both. Built from its code
	 * point rather than typed into the source: a literal NUL in a file makes grep treat the whole file
	 * as binary and print nothing, which costs a great deal more than the character saves.
	 */
	private static final String KEY_SEPARATOR = Character.toString(0);

	/** Text already sent to the worker. Bounded, because a long session sees a lot of numbers. */
	private static final Map<String, Boolean> SEEN = new ConcurrentHashMap<>();
	private static final int MAX_SEEN = 200_000;

	/** Repeated container packets reuse font-free plans and evidence, including live styles. */
	private static final int MAX_LORE_CACHE = 128;
	private static final Map<List<StyledText>, List<LoreObservation>> LORE_CACHE =
		new LinkedHashMap<>(MAX_LORE_CACHE, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<List<StyledText>, List<LoreObservation>> eldest) {
				return size() > MAX_LORE_CACHE;
			}
		};
	private static TranslationIndex loreIndex;
	private static boolean loreSkyBlockName;

	/** File names for the surfaces where the text has no name of its own. */
	private static final String SIDEBAR = "Sidebar";
	private static final String BOSS_BAR = "Boss_Bar";
	private static final String ACTION_BAR = "Action_Bar";
	private static final String HOLOGRAMS = "Holograms";
	private static final String TAB_LIST = "Tab_List";
	private static final String SERVER_MESSAGES = "Server_Messages";
	private static final String INVENTORY = "_Inventory";

	/**
	 * How long a line waits for the sidebar to name the area, and how many may wait at once.
	 *
	 * <p>Eight seconds covers a Hypixel server switch with room to spare — the sidebar is usually
	 * whole within a second of the world loading — and is short enough that a genuinely unreadable
	 * sidebar still produces files rather than silence. The ceiling is one island join's worth of
	 * text: a full inventory, a tab list and the welcome messages, several times over.
	 */
	private static final long HOLD_MS = 8_000L;
	private static final int MAX_HELD = 4096;

	/**
	 * Lines seen before the sidebar said where the player was. Touched only from the client thread —
	 * every capture point goes through {@link #ready()}, which refuses any other thread, and the tick
	 * that drains it is the client tick.
	 */
	private static final Unplaced HELD = new Unplaced(MAX_HELD, HOLD_MS);

	/** Server metadata candidates retained only inside the exact ClientLevel that supplied them. */
	private static final int MAX_HOLOGRAM_ENTITIES = 4096;
	private static final Set<Integer> HOLOGRAM_ENTITIES = new LinkedHashSet<>();
	private static ClientLevel hologramLevel;

	private static boolean started;

	private TextCapture() {
	}

	/** A chat message straight off the wire. Player conversation is dropped, not filed. */
	public static void chat(Component message) {
		if (!ready()) {
			return;
		}

		String plain = plainOf(message);

		if (ChatShape.isPlayerChat(plain) || ChatShape.isMachineReadable(plain)) {
			return;
		}

		int body = ChatShape.npcTagEnd(plain);

		CaptureSurface surface = body >= 0 ? CaptureSurface.NPC_MESSAGE : CaptureSurface.CHAT_MESSAGE;
		String name = body >= 0 ? Classifier.fileName(ChatShape.npcName(plain)) : SERVER_MESSAGES;
		offer(surface, message, name, "", Math.max(0, body));
		Minecraft client = Minecraft.getInstance();

		if (client.font != null) {
			int width = (int) Math.floor(ChatComponent.getWidth(client.options.chatWidth().get())
				/ client.options.chatScale().get());
			ChatTranslation.Plan plan = ChatTranslation.plan(message, width, client.font::width);

			for (TranslationDiagnostics.Finding finding : TranslationDiagnostics.chat(plan)) {
				diagnose(surface, finding.source(), name, "聊天排版", finding.verdict());
			}
		}
	}

	/** The title the server opened a container with. */
	public static void containerTitle(Component title) {
		if (ready()) {
			offer(CaptureSurface.GUI_TITLE, title, Classifier.fileName(title.getString()), "", 0);
		}
	}

	/**
	 * One item's name and every line of its lore, read off the components the server sent.
	 *
	 * <p>{@code getTooltipLines} is not used and must not be: that is the list every mod adds to, and
	 * it is where the last attempt at this feature went wrong. {@code CUSTOM_NAME} and {@code LORE}
	 * hold what arrived in the packet and nothing else.
	 */
	public static void item(int containerId, int slot, ItemStack stack) {
		if (!ready() || stack == null || stack.isEmpty()) {
			return;
		}

		String menu = CaptureContext.menu(containerId, slot);
		String name = menu.isEmpty() ? INVENTORY : Classifier.fileName(menu);
		String where = menu.isEmpty() ? "背包" : menu;

		Component custom = stack.get(DataComponents.CUSTOM_NAME);

		if (custom == null) {
			custom = stack.get(DataComponents.ITEM_NAME);
		}

		if (custom != null) {
			offer(CaptureSurface.GUI_ITEM, custom, name, where + " 物品名", 0);
		}

		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore == null) {
			return;
		}

		List<Component> lines = lore.lines();

		for (LoreObservation observation : inspectLore(lines)) {
			if (observation.diagnostic() != null) {
				diagnose(observation.surface(), observation.source(), name,
					where + " / " + (custom == null ? "" : plainOf(custom)) + " Lore", observation.diagnostic());
			} else {
				offer(observation.surface(), observation.source().getFirst(), name, where + " Lore", 0, Integer.MAX_VALUE, false);
			}
		}
	}

	/** The production lore capture path, exposed without client state for packet-content regression tests. */
	public record LoreObservation(CaptureSurface surface, List<Component> source, Classifier.Verdict diagnostic) {}

	public static synchronized List<LoreObservation> inspectLore(List<Component> lines) {
		TranslationIndex index = Translator.index();
		boolean skyBlockName = SkyZHConfig.get().translateSkyBlockName;

		if (loreIndex != index || loreSkyBlockName != skyBlockName) {
			LORE_CACHE.clear();
			loreIndex = index;
			loreSkyBlockName = skyBlockName;
		}

		List<StyledText> key = lines.stream().map(StyledText::of).toList();
		List<LoreObservation> cached = LORE_CACHE.get(key);

		if (cached != null) {
			return cached;
		}

		// Cache-owned components cannot change if another caller later mutates the packet's text.
		List<Component> snapshot = key.stream().<Component>map(text -> text.slice(0, text.length())).toList();
		List<LoreObservation> observations = new ArrayList<>();
		List<LoreTranslation.Unit> plan = LoreTranslation.plan(snapshot);

		for (TranslationDiagnostics.Finding finding : TranslationDiagnostics.lore(plan)) {
			observations.add(new LoreObservation(CaptureSurface.GUI_LORE, finding.source(), finding.verdict()));
		}

		for (LoreTranslation.Unit unit : plan) {
			if (unit.complete()) {
				StyledText source = unit.matches().getFirst().source();
				observations.add(new LoreObservation(
					CaptureSurface.GUI_LORE, List.of(source.slice(0, source.length())), null
				));
			} else {
				for (Component line : unit.source()) {
					observations.add(new LoreObservation(CaptureSurface.GUI_ITEM, List.of(line), null));
				}
			}
		}

		List<LoreObservation> result = List.copyOf(observations);
		LORE_CACHE.put(key, result);

		return result;
	}

	private static synchronized void clearLoreCache() {
		LORE_CACHE.clear();
		loreIndex = null;
	}

	/** Packet-derived diagnostic with concrete, ordered evidence. Never called for mod-added tooltips. */
	private static void diagnose(
		CaptureSurface surface, List<Component> source, String name, String where, Classifier.Verdict verdict
	) {
		String gameplay = placed();
		String key = key(surface, name, where, verdict.bucket() + verdict.evidence().toString(), gameplay);
		boolean first = SEEN.putIfAbsent(key, Boolean.TRUE) == null;
		CaptureStore.Observation observation = CaptureStore.Repeated.INSTANCE;

		if (first) {
			var joined = Component.empty();

			for (int i = 0; i < source.size(); i++) {
				if (i > 0) {
					joined.append("\n");
				}

				joined.append(source.get(i));
			}

			observation = new CaptureStore.Diagnosed(StyledText.of(joined), verdict);
		}

		queue(surface, key, observation, name, where, gameplay);
	}

	/** The sidebar's title, or one of its rows. */
	public static void sidebar(Component line) {
		if (ready()) {
			offer(CaptureSurface.SCOREBOARD, line, SIDEBAR, "", 0);
		}
	}

	public static void bossBar(Component name) {
		if (ready()) {
			offer(CaptureSurface.BOSS_BAR, name, BOSS_BAR, "", 0);
		}
	}

	/**
	 * The action bar, one widget at a time.
	 *
	 * <p>SkyBlock's action bar is a HUD, not a sentence: health, the place, defence, mana and drill
	 * fuel laid out side by side with wide gaps between them. Filed whole, a session produced eight
	 * hundred records that differed only in numbers that change as the player walks, and not one of
	 * them could ever be translated. Split on the gaps — see {@link LineShape#widgets} — the same
	 * session is a dozen short widgets, which is what the corpus can answer for and what the renderer
	 * translates.
	 *
	 * <p>A message that arrives on its own has no wide gap in it and comes back as a single widget,
	 * so the messages the corpus already covers are unaffected.
	 */
	public static void actionBar(Component text) {
		if (!ready()) {
			return;
		}

		String plain = plainOf(text);
		List<LineShape.Range> widgets = LineShape.widgets(plain);

		if (widgets.size() <= 1) {
			offer(CaptureSurface.ACTION_BAR, text, ACTION_BAR, "", 0);
			return;
		}

		for (LineShape.Range widget : widgets) {
			offer(CaptureSurface.ACTION_BAR, text, ACTION_BAR, "", widget.start(), widget.end());
		}
	}

	public static void tabList(Component line, String where) {
		if (ready() && line != null) {
			offer(CaptureSurface.TABLIST, line, TAB_LIST, where, 0);
		}
	}

	/**
	 * One server-owned hologram after an entity-metadata packet has been applied.
	 *
	 * <p>Only visible custom names on invisible armour stands belong to the Hologram surface. That
	 * structural filter keeps players, ordinary mobs, named items and decorative stands out;
	 * {@link NameTag#eligible} additionally rejects NPC proper names and dynamic mob-health layers.
	 */
	public static void hologram(Entity entity) {
		// Only remembered here. Hypixel sends a hologram's name, visibility and position in separate
		// packets, and a stack of them one row at a time; the periodic scan below reads the whole
		// settled scene, which is what the capture writes down as the line's context.
		trackHologram(entity);
	}

	/**
	 * Periodic state read that also sees holograms whose one-shot metadata arrived before the
	 * hello/sidebar gate opened. Only entity ids observed by the server packet hook are revisited;
	 * walking every render entity would let another Mod's client-created armour stands into capture.
	 *
	 * <p>Only stands within {@link HologramSnapshot#CAPTURE_DISTANCE} of the player are read. Minecraft
	 * keeps entities loaded far beyond what the player can see, so without the radius a museum's
	 * display labels were filed under every Hub area the player later walked through, and the
	 * {@code seen_in} list stopped meaning anything. Each eligible stand is offered together with the
	 * stands stacked above and below it, so the file shows the whole sign and not one row of it.
	 */
	public static void holograms(ClientLevel level) {
		hologramLevel(level);

		if (!ready() || level == null || HOLOGRAM_ENTITIES.isEmpty()) {
			return;
		}

		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		List<HologramSnapshot.Row> rows = new ArrayList<>();
		for (Iterator<Integer> ids = HOLOGRAM_ENTITIES.iterator(); ids.hasNext();) {
			Entity entity = level.getEntity(ids.next());
			if (!(entity instanceof ArmorStand)) {
				ids.remove();
			} else if (entity.isInvisible()
				&& entity.isCustomNameVisible() && entity.getCustomName() != null
				&& entity.distanceToSqr(player) <= HologramSnapshot.CAPTURE_DISTANCE * HologramSnapshot.CAPTURE_DISTANCE) {
				rows.add(new HologramSnapshot.Row(entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
					StyledText.of(entity.getCustomName())));
			}
		}
		HologramSnapshot.Index spatial = new HologramSnapshot.Index(rows);
		for (HologramSnapshot.Row row : rows) {
			if (NameTag.eligible(row.text())) {
				offerHologram(spatial.around(row));
			}
		}
	}

	private static void trackHologram(Entity entity) {
		Minecraft client = Minecraft.getInstance();

		// Retain only bounded packet-observed IDs even while capture is off. Do not read text,
		// classify, queue or write until ready(); static names may never receive another packet.
		if (!client.isSameThread() || !(entity instanceof ArmorStand)) {
			return;
		}

		hologramLevel(client.level);
		rememberHologram(entity.getId());
	}

	private static void rememberHologram(int id) {
		if (HOLOGRAM_ENTITIES.contains(id)) {
			return;
		}

		if (HOLOGRAM_ENTITIES.size() >= MAX_HOLOGRAM_ENTITIES) {
			Iterator<Integer> oldest = HOLOGRAM_ENTITIES.iterator();
			oldest.next();
			oldest.remove();
		}

		HOLOGRAM_ENTITIES.add(id);
	}

	/** Clears packet-era ids as soon as Minecraft replaces or drops their owning world. */
	public static void hologramLevel(ClientLevel level) {
		if (hologramLevel != level) {
			hologramLevel = level;
			HOLOGRAM_ENTITIES.clear();
		}
	}

	private static void offerHologram(HologramSnapshot snapshot) {
		String gameplay = placed();
		// Neighbour updates deserve a new observation even when the target text did not change: the
		// scene around a line is what gets written down, so a changed scene is new evidence.
		// A nearby sign can be observed across an area boundary within the same gameplay. Keep
		// that sighting as a Line so the worker can retain its distinct observer_area context.
		String key = hologramKey(snapshot, gameplay, CaptureContext.area());
		boolean first = SEEN.putIfAbsent(key, Boolean.TRUE) == null;
		CaptureStore.Observation observation = first
			? new CaptureStore.Line(snapshot.target().text(), true, snapshot) : CaptureStore.Repeated.INSTANCE;
		queue(CaptureSurface.HOLOGRAM, key, observation, HOLOGRAMS, "", gameplay);
	}

	private static String hologramKey(HologramSnapshot snapshot, String gameplay, String area) {
		return key(CaptureSurface.HOLOGRAM, HOLOGRAMS, area, snapshot.signature(), gameplay);
	}

	/** The title and subtitle that flash across the middle of the screen. */
	public static void misc(Component text, String where) {
		if (ready()) {
			offer(CaptureSurface.MISC, text, Classifier.fileName(where), "", 0);
		}
	}

	/**
	 * Queues one line, flattening it only the first time it is seen.
	 *
	 * <p>Order matters here more than it looks. A container's contents arrive as one packet holding
	 * fifty-four items with a dozen lore lines each, several times a second while a menu is open, and
	 * the great majority of those lines have been seen already. So the deduplication key is built from
	 * the cheap flattening — the string the component already knows, with its colour codes stripped —
	 * and the expensive one, which allocates a style per character, only happens for text that turns
	 * out to be new.
	 *
	 * <p>The key is built from the text <em>without</em> colours on purpose. The sidebar title is
	 * re-sent every tick with its highlight one letter further along, and keying on the colours would
	 * make every frame of that animation a new line to be queued and matched.
	 *
	 * @param from where the sentence starts in the flattened line, past a speaker tag; 0 for all else
	 */
	private static void offer(CaptureSurface surface, Component component, String name, String where, int from) {
		offer(surface, component, name, where, from, Integer.MAX_VALUE);
	}

	/** @param to where it stops, for a line that is several widgets side by side */
	private static void offer(
		CaptureSurface surface, Component component, String name, String where, int from, int to
	) {
		offer(surface, component, name, where, from, to, true);
	}

	private static void offer(
		CaptureSurface surface, Component component, String name, String where, int from, int to, boolean checkValues
	) {
		String full = plainOf(component);
		int end = Math.min(to, full.length());

		if (from >= end) {
			return;
		}

		String plain = from > 0 || end < full.length() ? full.substring(from, end) : full;

		if (plain.isBlank()) {
			return;
		}

		// A record is a line. The tab list's header and footer arrive as one component with newlines
		// in it, and filing that whole block as one record would produce something nothing on screen
		// can ever match. Rare enough to be the slow path.
		if (plain.indexOf('\n') >= 0) {
			StyledText styled = StyledText.of(component);
			String gameplay = placed();
			int start = from;
			int limit = Math.min(end, styled.length());

			while (start < limit) {
				int stop = styled.plain().indexOf('\n', start);
				stop = stop < 0 || stop > limit ? limit : stop;

				if (stop > start) {
					StyledText line = styled.sub(start, stop);
					String lineKey = key(surface, name, where, line.plain(), gameplay);
					boolean lineFirst = SEEN.putIfAbsent(lineKey, Boolean.TRUE) == null;

					CaptureStore.Observation observation = lineFirst
						? new CaptureStore.Line(line, checkValues) : CaptureStore.Repeated.INSTANCE;
					queue(surface, lineKey, observation, name, where, gameplay);
				}

				start = stop + 1;
			}

			return;
		}

		String gameplay = placed();
		String key = key(surface, name, where, plain, gameplay);
		boolean first = SEEN.putIfAbsent(key, Boolean.TRUE) == null;
		CaptureStore.Observation observation = CaptureStore.Repeated.INSTANCE;

		if (first) {
			StyledText styled = StyledText.of(component);
			int start = Math.min(from, styled.length());
			int stop = Math.min(end, styled.length());
			StyledText text = from > 0 || stop < styled.length() ? styled.sub(start, Math.max(start, stop)) : styled;
			observation = new CaptureStore.Line(text, checkValues);
		}

		queue(surface, key, observation, name, where, gameplay);
	}

	/**
	 * Hands one line to the worker, with the session's context attached — or holds it until there is
	 * one.
	 *
	 * <p>The context that matters is the area, and there is a window on every warp where the text has
	 * arrived and the sidebar has not caught up. {@link Unplaced} is what stands in that window; see
	 * it for why the line waits rather than being filed under the honest but useless answer.
	 */
	private static void queue(
		CaptureSurface surface, String key, CaptureStore.Observation observation, String name, String where, String gameplay
	) {
		if (SEEN.size() > MAX_SEEN) {
			SEEN.clear();
		}

		long now = System.currentTimeMillis();
		// The area is read now rather than when the line is finally filed: a line held through a warp
		// belongs to the place it arrived in, not to wherever the player ended up.
		CaptureStore.Sighting sighting = new CaptureStore.Sighting(
			surface, key, observation, null, CaptureContext.area(), name, where, now
		);

		for (CaptureStore.Sighting ready : HELD.offer(sighting, gameplay, now)) {
			CaptureStore.offer(ready);
		}
	}

	/**
	 * Called from the client tick: files anything that was waiting on an area the sidebar has since
	 * named, and gives up on anything that has waited long enough.
	 *
	 * <p>Separate from {@link #queue} because the sidebar answering is not itself a capture. Without
	 * it a line held through a quiet warp would sit in memory until the next thing the server said,
	 * which on a private island can be minutes.
	 */
	public static void tick() {
		if (HELD.size() == 0) {
			return;
		}

		for (CaptureStore.Sighting ready : HELD.tick(placed(), System.currentTimeMillis())) {
			CaptureStore.offer(ready);
		}
	}

	/** The gameplay category, or {@code null} while the sidebar has not said where the player is. */
	private static String placed() {
		return CaptureContext.located() ? CaptureContext.gameplay() : null;
	}

	private static String key(CaptureSurface surface, String name, String where, String plain, String gameplay) {
		// The same HUD text may legitimately occur in several gameplays. Keeping the scope here means
		// seeing it in Hub first cannot discard the styled snapshot later needed by Mining or Dungeons.
		return (gameplay == null ? "_Unplaced" : gameplay) + KEY_SEPARATOR
			+ surface.name() + KEY_SEPARATOR + name + KEY_SEPARATOR + where + KEY_SEPARATOR + plain;
	}

	/**
	 * A component's text with colour codes gone, matching what {@link StyledText#plain} would give.
	 *
	 * <p>Both spellings of colour have to disappear: the {@code Style} on a nested component never
	 * reaches {@code getString} in the first place, and the legacy codes SkyBlock puts inside literal
	 * strings are stripped here. The two therefore agree, which is what lets this be the key for text
	 * the worker will later flatten properly.
	 */
	private static String plainOf(Component component) {
		return StyledText.plainOf(component.getString());
	}

	/**
	 * Whether capture is switched on, on the right thread, and somewhere it is allowed to run.
	 *
	 * <p>The thread check is not defensive tidiness. A packet handler's first statement is
	 * {@code PacketUtils.ensureRunningOnSameThread}, which throws on the network thread so the whole
	 * handler is re-run on the client thread — meaning a hook at {@code HEAD} fires <em>twice</em>,
	 * once on each. Without this the network pass would read state written by the client thread and
	 * every message would be counted twice. Hooking at {@code HEAD} is still right: it is the earliest
	 * point at which the packet exists and no mod has looked at it.
	 */
	public static boolean ready() {
		if (!Minecraft.getInstance().isSameThread() || !CaptureContext.active()) {
			return false;
		}

		if (!started) {
			started = true;
			CaptureStore.start(directory());
			// Covers the switch being turned on in Mod Menu after the game was started with it off.
			HypixelApi.install();
		}

		return true;
	}

	private static Path directory() {
		String configured = SkyZHConfig.get().captureDirectory.trim();
		Path base = FabricLoader.getInstance().getGameDir();

		return configured.isEmpty() ? base.resolve("skyzh-capture") : base.resolve(configured);
	}

	/**
	 * Writes out whatever the worker is holding, now rather than on its own timer.
	 *
	 * <p>For the moment the switch is turned off from chat: after that the switch is closed, so the
	 * next line to arrive returns at {@link #ready} and nothing would ever flush what was already
	 * collected.
	 */
	public static void flush() {
		if (started) {
			CaptureStore.finishPending();
		}
	}

	/**
	 * Forgets every captured line, in memory and on disk.
	 *
	 * <p>Order is the substance of this method. The client-thread buffers go first so nothing held back
	 * is handed to the worker on the way past. The store then advances its queue generation and clears
	 * pending announcements under the same monitor used to accept lines; that atomic step prevents a
	 * worker which was already processing a line from recreating either state between two separate
	 * clears. The seen set in particular <em>has</em> to be cleared: it is what makes a second sighting of a
	 * line cost nothing, and leaving it behind would mean the menu whose capture was just discarded
	 * produces nothing at all when it is opened again — the lines would all be recognised as already seen.
	 *
	 * <p>{@code started} is deliberately left alone. The worker thread is still wanted; this clears
	 * what it has collected, not the fact that it is running.
	 */
	public static void clear() throws IOException {
		HELD.clear();
		HOLOGRAM_ENTITIES.clear();
		SEEN.clear();
		clearLoreCache();

		// The directory rather than the store's own root, so files left by an earlier session can be
		// cleared in one where capture has not started and never set it.
		int deleted = CaptureStore.clear(directory());
		LOGGER.info("SkyZH 采集记录已清空，删除文件 {} 个。", deleted);
	}

	/** Called when the connection ends: write out what is held and forget the session. */
	public static void disconnected() {
		HOLOGRAM_ENTITIES.clear();
		hologramLevel = null;
		// Drained before the context is forgotten, so the last lines of a session are still filed
		// under the area the player was standing in when the server dropped them.
		for (CaptureStore.Sighting ready : HELD.drain(placed())) {
			CaptureStore.offer(ready);
		}

		CaptureContext.reset();
		clearLoreCache();

		if (!started) {
			return;
		}

		CaptureStore.finishPending();
		SEEN.clear();
		LOGGER.info("SkyZH 采集本次会话结果：{}", CaptureStore.summary());
	}
}
