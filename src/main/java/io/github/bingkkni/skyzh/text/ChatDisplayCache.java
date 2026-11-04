package io.github.bingkkni.skyzh.text;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.util.FormattedCharSequence;

/** Bounded, per-chat display-only cache; the original GuiMessages are never replaced or edited. */
public final class ChatDisplayCache {
	private static final int LIMIT = 256;
	private final Map<GuiMessage, Lines> messages = new IdentityHashMap<>();
	private final ArrayDeque<GuiMessage> insertionOrder = new ArrayDeque<>();
	private Object font;
	private Object corpus;
	private int width = -1;
	private boolean skyBlockName;
	private int names;
	private boolean displayedTranslation;
	private boolean displayedName;
	private int displayedNames;
	private Object displayedCorpus;
	private boolean policyInvalidated;

	private static final class Lines {
		StyledText source;
		List<FormattedCharSequence> original;
		List<FormattedCharSequence> translated;
	}

	/** Both chat display paths translate server system messages, never player/client notices. */
	public static boolean isServerMessage(GuiMessage message) {
		return message.source() == GuiMessageSource.SYSTEM_SERVER;
	}

	/** Another display view temporarily owned the queue; refresh once when returning to this one. */
	public void invalidatePolicy() { this.policyInvalidated = true; }

	/** Effective render policy, not config-save generation: a hint/capture switch needs no rewrap. */
	public boolean needsRefresh(boolean translated, boolean name, Object index) {
		int names = ItemNamePolicy.state();
		boolean changed = this.policyInvalidated || this.displayedTranslation != translated
			|| translated && (this.displayedName != name || this.displayedNames != names || this.displayedCorpus != index);
		this.policyInvalidated = false;
		this.displayedTranslation = translated;
		this.displayedName = name;
		this.displayedNames = names;
		this.displayedCorpus = index;
		return changed;
	}

	public List<FormattedCharSequence> get(GuiMessage message, Object font, int width, boolean translated,
		boolean name, Object index, Supplier<List<FormattedCharSequence>> split) {
		int names = ItemNamePolicy.state();
		if (this.font != font || this.width != width || this.corpus != index || this.skyBlockName != name || this.names != names) {
			clear();
			this.font = font;
			this.width = width;
			this.corpus = index;
			this.skyBlockName = name;
			this.names = names;
		}
		Lines lines = this.messages.get(message);
		if (lines == null) {
			if (this.messages.size() >= LIMIT) this.messages.remove(this.insertionOrder.removeFirst());
			lines = new Lines();
			this.messages.put(message, lines);
			this.insertionOrder.addLast(message);
		}
		// Components are mutable even though GuiMessage is a record. A chat mod may restyle or
		// edit an existing component before requesting another wrap; do not serve its old spelling.
		StyledText source = StyledText.of(message.content());
		if (!source.equals(lines.source)) {
			lines.source = source;
			lines.original = null;
			lines.translated = null;
		}
		List<FormattedCharSequence> cached = translated ? lines.translated : lines.original;
		if (cached != null) return cached;
		List<FormattedCharSequence> result = List.copyOf(split.get());
		if (translated) lines.translated = result;
		else lines.original = result;
		return result;
	}

	/** Vanilla rescaling/resource reload and history clear invalidate pixels, not source history. */
	public void clear() {
		this.messages.clear();
		this.insertionOrder.clear();
	}
}
