package io.github.bingkkni.skyzh.text;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative literal index. A candidate still has to pass the complete regex and capture rules. */
final class TemplateCandidates {
	private static final class Node {
		final Map<Character, Node> children = new HashMap<>();
		final List<Integer> entries = new ArrayList<>();
	}

	private final Node root = new Node();
	private final BitSet fallback = new BitSet();
	private final List<TranslationEntry> entries;

	TemplateCandidates(List<TranslationEntry> source) {
		Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<TranslationEntry> ordered = new ArrayList<>();
		for (TranslationEntry entry : source) {
			if (seen.add(entry.compiledIdentity())) ordered.add(entry);
		}
		// Stable sort: equal specificity retains the original file/record order.
		ordered.sort(Comparator.comparingInt(TranslationEntry::specificity).reversed());
		this.entries = List.copyOf(ordered);
		for (int i = 0; i < this.entries.size(); i++) {
			String anchor = this.entries.get(i).anchor();
			if (anchor.isEmpty()) {
				this.fallback.set(i);
				continue;
			}
			Node node = this.root;
			for (int j = 0; j < anchor.length(); j++) {
				node = node.children.computeIfAbsent(anchor.charAt(j), ignored -> new Node());
			}
			node.entries.add(i);
		}
	}

	TranslationIndex.Match match(String plain) {
		BitSet candidates = (BitSet) this.fallback.clone();
		for (int start = 0; start < plain.length(); start++) {
			Node node = this.root;
			for (int cursor = start; cursor < plain.length(); cursor++) {
				node = node.children.get(plain.charAt(cursor));
				if (node == null) break;
				for (int entry : node.entries) candidates.set(entry);
			}
		}
		for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
			TranslationEntry entry = this.entries.get(i);
			var match = entry.match(plain);
			if (match != null) return new TranslationIndex.Match(entry, match);
		}
		return null;
	}
}
