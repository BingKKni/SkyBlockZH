package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way in. A mixin hands over the component about to be drawn and the surface it is being
 * drawn on, and gets back either Chinese or the very same object it passed in.
 *
 * <p>Every caller sits in a render path, never in a packet path. Nothing here writes to an
 * {@code ItemStack}, a {@code GuiMessage}, a {@code BossEvent} or a screen's {@code title} field —
 * the objects other mods read stay in English, and SkyHanni or SkyBlocker parsing the same text a
 * frame earlier see exactly what Hypixel sent. Translation is a property of the pixels, not of the
 * game state.
 */
public final class Translator {
	private static final Logger LOGGER = LoggerFactory.getLogger("SkyZH");
	private static final Set<String> REPORTED_COLOUR_LOSS = ConcurrentHashMap.newKeySet();

	private static volatile TranslationIndex index = new TranslationIndex();

	private Translator() {
	}

	/**
	 * The outcome of one lookup.
	 *
	 * @param core  the translated sentence on its own, with whatever surrounded it peeled off
	 * @param head  what the sentence was preceded by — indentation, the server's centring padding,
	 *              a {@code [NPC] Bubu: } speaker tag — kept verbatim, or {@code null} if nothing was
	 * @param tail  what followed it, on the same terms
	 * @param entry the record that matched, or {@code null} if the corpus had nothing
	 */
	public record Result(MutableComponent core, Component head, Component tail, TranslationEntry entry) {
		public boolean matched() {
			return this.entry != null;
		}

		/**
		 * The line as it should normally be drawn: translated, with everything that surrounded it
		 * put back exactly as it arrived. Right for lore indentation, speaker tags and tab-list
		 * values; the two places where what surrounded the text was a hand-made centring instead —
		 * container titles and chat banners — ask for {@link #core()} and lay it out themselves.
		 */
		public MutableComponent padded() {
			if (this.head == null && this.tail == null) {
				return this.core;
			}

			MutableComponent result = Component.empty();

			if (this.head != null) {
				result.append(this.head);
			}

			result.append(this.core);

			if (this.tail != null) {
				result.append(this.tail);
			}

			return result;
		}

		/**
		 * True when what the server put in front of this line was its own centring padding — the
		 * signal a container title gives that it was meant to be centred, and that the mod should
		 * therefore recompute the centring rather than reuse spaces measured for English.
		 */
		public boolean centredByServer() {
			if (this.head == null) {
				return false;
			}

			String text = this.head.getString();

			return !text.isEmpty() && text.isBlank();
		}
	}

	public static void reload() {
		index = TranslationLoader.load();
		REPORTED_COLOUR_LOSS.clear();
	}

	public static TranslationIndex index() {
		return index;
	}

	/**
	 * Translates one line. Returns a result whose {@code entry} is {@code null} when nothing
	 * matched — in which case the text is still returned, and "SkyBlock" is swapped out only if it is
	 * the whole of the English on that line. See {@link #skyBlockNameAlone}.
	 *
	 * <p>A line is tried whole first, then with the structure around its sentence peeled off, in the
	 * order {@link LineShape} lays out. Whatever was peeled comes back untouched in the result's
	 * {@code head} and {@code tail}, still wearing the colours it arrived in: those are the server's
	 * indentation, its centring padding, a speaker's name and a tab-list value, none of which the
	 * mod has any business rewriting.
	 */
	public static Result translate(Component source, Surface surface) {
		SkyZHConfig config = SkyZHConfig.get();

		if (!config.enabled) {
			return new Result(source.copy(), null, null, null);
		}

		StyledText styled = StyledText.of(source);
		// Matching runs on the canonical spelling of the line: the same characters with SkyBlock's
		// icon font folded back onto the symbols the corpus was written with. Drawing still uses the
		// text exactly as it arrived — see Glyphs.
		String plain = styled.canonical();

		if (plain.isEmpty()) {
			return new Result(source.copy(), null, null, null);
		}

		for (LineShape.Range range : LineShape.candidates(surface, plain)) {
			TranslationEntry entry = index.lookup(surface, plain.substring(range.start(), range.end()));

			if (entry == null) {
				continue;
			}

			StyledText core = styled.sub(range.start(), range.end());
			Matcher match = entry.match(core.canonical());

			if (match == null) {
				// The index found this record by exact text, so a failure here means the pattern and
				// the text it was registered under disagree — a malformed record, not a mismatch.
				// Nothing to be gained from it; try whatever shape comes next.
				continue;
			}

			if (entry.losesColour(core, match) && REPORTED_COLOUR_LOSS.add(entry.id())) {
				LOGGER.warn(
					"记录 {}（{}）在游戏内实际有多段颜色，但数据文件只记了一整行，译文只能套用第一段颜色。"
						+ "建议给该条目补 segments 数组按颜色分段。",
					entry.id(), entry.sourceFile()
				);
			}

			MutableComponent translated = entry.render(core, match, index.terms());

			return new Result(
				skyBlockName(translated, config),
				range.start() > 0 ? styled.slice(0, range.start()) : null,
				range.end() < plain.length() ? styled.slice(range.end(), plain.length()) : null,
				entry
			);
		}

		return new Result(skyBlockNameAlone(source, styled, config), null, null, null);
	}

	/**
	 * Which record answers for a line, and where — the lookup {@link #translate} does, stopping short
	 * of rendering anything.
	 *
	 * <p>Two callers want this rather than a finished line. The runtime capture asks "is this text
	 * covered at all, and if it is, is any of it still English"; both questions are about the record
	 * and the match, not about pixels. And unlike {@link #translate} it ignores the master switch: a
	 * player who has turned translation off has not stopped the corpus from covering a line, and
	 * reporting every line on screen as missing would be a lie.
	 *
	 * @param entry the record that answered, {@code null} when the corpus had nothing
	 * @param core  the piece of the line the record answered for, with its live colours
	 * @param match that record's match against {@code core}
	 */
	public record Located(TranslationEntry entry, StyledText core, Matcher match) {
		public boolean matched() {
			return this.entry != null;
		}
	}

	public static Located locate(Component source, Surface surface) {
		return locate(StyledText.of(source), surface);
	}

	/** The same, for a caller that already flattened the line — the capture path snapshots it early. */
	public static Located locate(StyledText styled, Surface surface) {
		String plain = styled.canonical();

		if (plain.isEmpty()) {
			return new Located(null, styled, null);
		}

		for (LineShape.Range range : LineShape.candidates(surface, plain)) {
			TranslationEntry entry = index.lookup(surface, plain.substring(range.start(), range.end()));

			if (entry == null) {
				continue;
			}

			StyledText core = styled.sub(range.start(), range.end());
			Matcher match = entry.match(core.canonical());

			if (match != null) {
				return new Located(entry, core, match);
			}
		}

		return new Located(null, styled, null);
	}

	/** Convenience for the callers that only want a component back. */
	public static Component translateLine(Component source, Surface surface) {
		return translate(source, surface).padded();
	}

	/**
	 * Translates one chat line and recomputes padding for corpus records marked as centred banners.
	 * Keeping this beside the ordinary one-line entry point makes the same rule available to both a
	 * single message and every member of a newline-separated message.
	 */
	public static Component translateChatLine(Component source, Font font, int width) {
		Result result = translate(source, Surface.CHAT);

		return result.matched() && "center_chat_banner".equals(result.entry().layout())
			? TextLayout.centeredWithSpaces(font, result.core(), width)
			: result.padded();
	}

	/** The newline-preserving counterpart of {@link #translateChatLine}. */
	public static Component translateChatBlock(Component source, Font font, int width) {
		StyledText styled = StyledText.of(source);
		String plain = styled.plain();

		if (plain.indexOf('\n') < 0) {
			return translateChatLine(source, font, width);
		}

		MutableComponent result = Component.empty();
		int start = 0;

		while (true) {
			int newline = plain.indexOf('\n', start);
			int end = newline < 0 ? plain.length() : newline;

			if (end > start) {
				result.append(translateChatLine(styled.slice(start, end), font, width));
			}

			if (newline < 0) {
				return result;
			}

			result.append(Component.literal("\n"));
			start = newline + 1;
		}
	}

	/**
	 * Translates a line that is several things side by side, one thing at a time.
	 *
	 * <p>For the action bar, which is a HUD rather than a sentence — see {@link LineShape#widgets}.
	 * The whole line is tried first, because a record that spells one out wins over the shape: an
	 * action-bar message the server padded is still one message, and the corpus is allowed to say so.
	 * Only when nothing answers for the whole is it taken apart, and the gaps between the parts go
	 * back exactly as they arrived — they are SkyBlock's layout, measured in its own spaces, and the
	 * Chinese widgets sit in the same places the English ones did.
	 */
	public static Component translateWidgets(Component source, Surface surface) {
		Result whole = translate(source, surface);

		if (whole.matched()) {
			return whole.padded();
		}

		StyledText styled = StyledText.of(source);
		List<LineShape.Range> widgets = LineShape.widgets(styled.plain());

		if (widgets.size() <= 1) {
			return whole.padded();
		}

		MutableComponent result = Component.empty();
		int cursor = 0;

		for (LineShape.Range widget : widgets) {
			if (widget.start() > cursor) {
				result.append(styled.slice(cursor, widget.start()));
			}

			result.append(translateLine(styled.slice(widget.start(), widget.end()), surface));
			cursor = widget.end();
		}

		if (cursor < styled.length()) {
			result.append(styled.slice(cursor, styled.length()));
		}

		return result;
	}

	/**
	 * Translates a lore line that lists several enchantments, or returns {@code null} when the line is
	 * not one or when the corpus had nothing for any of them.
	 *
	 * <p>This exists because no record can cover such a line: it holds whichever enchantments that
	 * particular item carries, and one session produced sixty-five different combinations. A record
	 * per enchantment is writable, so the line is cut into the pieces those records are about — see
	 * {@link LineShape#enchantments} for why cutting on a comma is safe — and each piece looked up on
	 * its own. The {@code ", "} between them goes back exactly as it arrived, colours and all.
	 *
	 * <p>A piece with no record keeps its English, so an item carrying both a translated and an
	 * untranslated enchantment shows each as it stands. That is the same thing the tab list does with
	 * a row whose value is in the term table and whose label is not; the alternative, refusing the
	 * whole line unless every piece is covered, means a player sees nothing translated until the last
	 * enchantment in SkyBlock has a record.
	 */
	public static Component translateList(Component source, Surface surface) {
		StyledText styled = StyledText.of(source);
		List<LineShape.Range> items = LineShape.enchantments(styled.canonical());

		if (items.isEmpty()) {
			return null;
		}

		List<Component> translated = new ArrayList<>(items.size());
		boolean any = false;

		for (LineShape.Range item : items) {
			Result piece = translate(styled.slice(item.start(), item.end()), surface);

			translated.add(piece.matched() ? piece.padded() : styled.slice(item.start(), item.end()));
			any |= piece.matched();
		}

		if (!any) {
			// Nothing was translated, so hand back null rather than a rebuilt copy of the same line:
			// the caller then draws the component the game built, which is what should reach the
			// screen when the mod has decided not to touch a line.
			return null;
		}

		MutableComponent result = Component.empty();
		int cursor = 0;

		for (int i = 0; i < items.size(); i++) {
			LineShape.Range item = items.get(i);

			if (item.start() > cursor) {
				result.append(styled.slice(cursor, item.start()));
			}

			result.append(translated.get(i));
			cursor = item.end();
		}

		if (cursor < styled.length()) {
			result.append(styled.slice(cursor, styled.length()));
		}

		return result;
	}

	/**
	 * The placeholder type whose entry in {@code applies_to_types} means "the term table answers
	 * here". A tab-list row's halves are not placeholders at all, but they are the same kind of
	 * thing a placeholder catches — a value the server dropped into a fixed layout — and asking the
	 * table under any other name would quietly change which values it is willing to translate.
	 */
	private static final String VALUE = "raw";

	/**
	 * Translates one tab-list row, which is a label and a value rather than a sentence.
	 *
	 * <p>{@link LineShape} peels the value off so the label can be looked up on its own, and the
	 * value then goes back exactly as it arrived — which is right for {@code Bank: 12,345,678} and
	 * wrong for {@code Corpse Looter: DONE}, where the half that says how it is going is the half
	 * left in English. So the value is put through the term table on its way back: {@code DONE},
	 * {@code ✔ Found}, {@code NOT LOOTED} and {@code MAX} are a short closed list, they are values in
	 * exactly the sense the table exists for, and one entry each covers every row that ends in one.
	 *
	 * <p>The same table answers for the <em>label</em> when the corpus has no record for the row at
	 * all. The commission board fills the tab list with task names — {@code Rampart's Quarry
	 * Mithril}, {@code Onyx Gemstone Collector} — every one of which is already in the table, because
	 * every one of them also arrives as a captured value somewhere else. The alternative was a
	 * TabList record per commission saying what the table already says, and a second place to
	 * remember whenever Hypixel adds an area or a gemstone.
	 *
	 * <p>A record still wins wherever there is one. This only ever fills in halves nothing else
	 * answered for, so a row the corpus spells out in full is untouched.
	 */
	public static Component translateRow(Component source, Surface surface) {
		Result row = translate(source, surface);

		if (row.matched()) {
			Component value = row.tail() == null ? null : termed(row.tail());

			if (value == null) {
				return row.padded();
			}

			MutableComponent result = Component.empty();

			if (row.head() != null) {
				result.append(row.head());
			}

			result.append(row.core());
			result.append(value);

			return result;
		}

		StyledText styled = StyledText.of(source);
		int colon = styled.canonical().indexOf(':');

		if (colon < 0) {
			return row.padded();
		}

		Component label = termed(styled.slice(0, colon));
		Component value = termed(styled.slice(colon, styled.length()));

		if (label == null && value == null) {
			return row.padded();
		}

		return Component.empty()
			.append(label != null ? label : styled.slice(0, colon))
			.append(value != null ? value : styled.slice(colon, styled.length()));
	}

	/**
	 * Half a tab-list row with the term table applied to it, or {@code null} when the table had
	 * nothing — which is the answer for every number, bar and timer on that surface.
	 *
	 * <p>The separator and the padding around the words are found here rather than by the caller
	 * because they differ between the two halves — a label arrives with the server's leading space, a
	 * value with the colon still on it — and both have to come back exactly as they arrived. Only the
	 * words in between are looked up, and only as a whole: half a term is the mixed-language mess the
	 * table exists to remove.
	 */
	private static Component termed(Component half) {
		StyledText styled = StyledText.of(half);
		String plain = styled.canonical();
		int from = 0;
		int to = plain.length();

		while (from < to && (plain.charAt(from) == ':' || plain.charAt(from) == ' ')) {
			from++;
		}

		while (to > from && plain.charAt(to - 1) == ' ') {
			to--;
		}

		String zh = from < to ? index.terms().translate(VALUE, plain.substring(from, to)) : null;

		if (zh == null) {
			return null;
		}

		MutableComponent result = Component.empty();

		if (from > 0) {
			result.append(styled.slice(0, from));
		}

		result.append(Component.literal(zh).setStyle(styled.styleAt(from)));

		if (to < plain.length()) {
			result.append(styled.slice(to, plain.length()));
		}

		return result;
	}

	/**
	 * Translates a component that holds several lines separated by newlines, one line at a time.
	 *
	 * <p>The tab list's header and footer are built that way — one component, several lines — and a
	 * record is a line, so the whole block would match nothing. The newlines go back exactly where
	 * they were, since it is vanilla that splits and centres them afterwards.
	 */
	public static Component translateBlock(Component source, Surface surface) {
		StyledText styled = StyledText.of(source);
		String plain = styled.plain();

		if (plain.indexOf('\n') < 0) {
			return translateLine(source, surface);
		}

		MutableComponent result = Component.empty();
		int start = 0;

		while (true) {
			int newline = plain.indexOf('\n', start);
			int end = newline < 0 ? plain.length() : newline;

			if (end > start) {
				result.append(translateLine(styled.slice(start, end), surface));
			}

			if (newline < 0) {
				return result;
			}

			result.append(Component.literal("\n"));
			start = newline + 1;
		}
	}

	/**
	 * Swaps the gameplay's own name out, keeping whatever colour the word was drawn in.
	 *
	 * <p>This is the path for a line the corpus has <em>not</em> translated, and on that path the word
	 * is only swapped when it is the sole English word on the line — the shimmering SKYBLOCK at the
	 * top of the sidebar, and little else. Swapping it anywhere else produced exactly what the mod
	 * exists to avoid: "空岛生存 Level 42", one word of Chinese stranded in an English sentence, on
	 * every part of SkyBlock nobody has got to yet. Half a translation reads worse than none.
	 *
	 * <p>A line that ought to be Chinese sooner than that does not need code — it needs a record. A
	 * record's own {@code zh} may keep the word "SkyBlock" in it ("SkyBlock 菜单"), and the swap below
	 * still runs over the finished translation, so the config switch keeps working.
	 *
	 * <p>When the word is not there — which is almost every line — {@code source} is handed straight
	 * back rather than rebuilt from its characters. Most of what passes through this method is text
	 * nobody has translated, some of it several times a frame, and text the mod has decided not to
	 * touch should reach the screen as the very object the game built.
	 */
	private static MutableComponent skyBlockNameAlone(Component source, StyledText styled, SkyZHConfig config) {
		if (!config.translateSkyBlockName || !SkyBlockName.isTheOnlyEnglishWord(styled.plain())) {
			return source.copy();
		}

		MutableComponent replaced = index.skyBlockName().apply(styled);

		return replaced != null ? replaced : source.copy();
	}

	/** The same, for a line the mod has just built and therefore has no flattened form of yet. */
	private static MutableComponent skyBlockName(MutableComponent source, SkyZHConfig config) {
		if (!config.translateSkyBlockName) {
			return source;
		}

		MutableComponent replaced = index.skyBlockName().apply(StyledText.of(source));

		return replaced != null ? replaced : source;
	}
}
