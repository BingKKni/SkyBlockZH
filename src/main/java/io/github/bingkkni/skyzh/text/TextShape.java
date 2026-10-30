package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

/**
 * A component tree's literal structure, recorded so the same text arriving again is recognised
 * without flattening it.
 *
 * <p>Name tags, sidebar rows, tab rows and tooltip lines are rebuilt by vanilla every frame: a new
 * top node over the same strings in the same styles. Flattening that into a {@link StyledText} only
 * to find an existing cache entry cost more than the translation it found — a name tag carries a
 * hover event built that frame, which the flatten copied and the key hashed.
 *
 * <p>Two trees whose nodes hold the same literal strings, in equal styles, with the same number of
 * children each, flatten to equal styled text: flattening merges each node's style into its parent's
 * and reads nothing else. So a recorded shape can stand in for the flattened key. Only plain
 * literal text qualifies; translatable, keybind and score contents read the language, the bindings
 * or the scoreboard while flattening, and a tree holding one keeps using the flattened key.
 */
final class TextShape {
	/** {@link #hash} of a tree that cannot be recorded. */
	static final long NONE = -1;
	private static final int MAX_NODES = 64;

	private final String[] texts;
	private final Style[] styles;
	private final int[] children;
	private final int hash;

	private TextShape(String[] texts, Style[] styles, int[] children, int hash) {
		this.texts = texts;
		this.styles = styles;
		this.children = children;
		this.hash = hash;
	}

	/** Allocation-free; {@link #NONE} when a node is not literal text or the tree is too large. */
	static long hash(Component root) {
		long state = walk(root, 0);
		return state == NONE ? NONE : state & 0xFFFFFFFFL;
	}

	/** Node count in the high half, running hash in the low half. */
	private static long walk(Component node, long state) {
		String text = literal(node);
		int count = (int) (state >>> 32) + 1;
		if (text == null || count > MAX_NODES) return NONE;
		List<Component> siblings = node.getSiblings();
		int hash = 31 * (31 * (31 * (int) state + text.hashCode()) + StyledText.styleHash(node.getStyle()))
			+ siblings.size();
		state = (long) count << 32 | hash & 0xFFFFFFFFL;
		for (int i = 0; i < siblings.size() && state != NONE; i++) state = walk(siblings.get(i), state);
		return state;
	}

	/**
	 * Records a tree using its already computed {@link #hash}, or {@code null} for {@link #NONE}.
	 * Hover events are snapshotted the way the flattened key's are.
	 */
	static TextShape of(Component root, long hash) {
		if (hash == NONE) return null;
		List<Component> nodes = new ArrayList<>();
		collect(root, nodes);
		String[] texts = new String[nodes.size()];
		Style[] styles = new Style[nodes.size()];
		int[] children = new int[nodes.size()];
		for (int i = 0; i < texts.length; i++) {
			Component node = nodes.get(i);
			texts[i] = literal(node);
			styles[i] = StyledText.snapshotStyle(node.getStyle());
			children[i] = node.getSiblings().size();
		}
		return new TextShape(texts, styles, children, (int) hash);
	}

	private static void collect(Component node, List<Component> nodes) {
		nodes.add(node);
		for (Component sibling : node.getSiblings()) collect(sibling, nodes);
	}

	int hash() {
		return this.hash;
	}

	/** Allocation-free comparison with a live tree, node by node in flattening order. */
	boolean matches(Component root) {
		return match(root, 0) == this.texts.length;
	}

	private int match(Component node, int at) {
		if (at >= this.texts.length || !this.texts[at].equals(literal(node))
			|| this.children[at] != node.getSiblings().size() || !this.styles[at].equals(node.getStyle())) return -1;
		List<Component> siblings = node.getSiblings();
		int next = at + 1;
		for (int i = 0; i < siblings.size() && next >= 0; i++) next = match(siblings.get(i), next);
		return next;
	}

	/** Empty contents draw nothing, exactly like an empty literal. */
	private static String literal(Component node) {
		var contents = node.getContents();
		if (contents instanceof PlainTextContents.LiteralContents literal) return literal.text();
		return contents == PlainTextContents.EMPTY ? "" : null;
	}
}
