package io.github.bingkkni.skyzh.text;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Style;

/** Immutable rendered characters and complete style runs, including legacy formatting codes. */
public final class StyledText {
	private final String plain;
	private final Style[] styles;
	private final int[] ends;
	private final int offset;
	private String canonical;
	private int hash;
	private boolean hashed;

	private StyledText(String plain, Style[] styles, int[] ends, int offset) {
		this.plain = plain;
		this.styles = styles;
		this.ends = ends;
		this.offset = offset;
	}

	public static StyledText of(Component component) {
		Builder builder = new Builder();
		HoverSnapshots hovers = new HoverSnapshots();
		component.visit((style, string) -> {
			Style base = hovers.snapshot(style);
			Style current = base;
			int start = 0;
			for (int i = 0; i < string.length(); i++) {
				if (string.charAt(i) != ChatFormatting.PREFIX_CODE) continue;
				builder.append(string, start, i, current);
				if (i + 1 < string.length()) {
					ChatFormatting formatting = ChatFormatting.getByCode(string.charAt(i + 1));
					if (formatting != null) {
						current = formatting == ChatFormatting.RESET ? base : current.applyLegacyFormat(formatting);
					}
				}
				// Vanilla drops every § pair, including unknown scoreboard uniqueness markers.
				start = Math.min(string.length(), ++i + 1);
			}
			builder.append(string, start, string.length(), current);
			return Optional.empty();
		}, Style.EMPTY);
		return builder.build();
	}

	/** Style is immutable, but hover components and translatable arguments may still be mutable. */
	static Style snapshotStyle(Style style) {
		return snapshotStyle(style, new IdentityHashMap<>());
	}

	private static Style snapshotStyle(Style style, Map<Component, Boolean> active) {
		HoverEvent hover = style.getHoverEvent();
		return mutable(hover) ? withHover(style, snapshotHover(hover, active)) : style;
	}

	private static boolean mutable(HoverEvent hover) {
		return hover instanceof HoverEvent.ShowText || hover instanceof HoverEvent.ShowEntity;
	}

	/** A snapshot equals the live event, so setting it directly would keep the original instead. */
	private static Style withHover(Style style, HoverEvent hover) {
		return style.withHoverEvent(null).withHoverEvent(hover);
	}

	private static HoverEvent snapshotHover(HoverEvent hover, Map<Component, Boolean> active) {
		return switch (hover) {
			case HoverEvent.ShowText text -> new HoverEvent.ShowText(copyComponent(text.value(), active));
			case HoverEvent.ShowEntity entity -> {
				var info = entity.entity();
				yield new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(
					info.type, info.uuid, info.name.map(name -> copyComponent(name, active))));
			}
			default -> hover;
		};
	}

	/**
	 * One flatten's hover copies. Children inherit their parent's hover event, so every run of a
	 * name tag carries the same one. Consecutive runs inheriting that event share one copied hover
	 * component, which remains owned by the immutable snapshot.
	 */
	private static final class HoverSnapshots {
		private HoverEvent source;
		private HoverEvent copy;

		Style snapshot(Style style) {
			HoverEvent hover = style.getHoverEvent();
			if (!mutable(hover)) return style;
			if (hover != this.source) {
				this.source = hover;
				this.copy = snapshotHover(hover, new IdentityHashMap<>());
			}
			return withHover(style, this.copy);
		}
	}

	/** A cache-owned tree retaining original component structure, events and translation arguments. */
	public static Component copyComponent(Component source) {
		return copyComponent(source, new IdentityHashMap<>());
	}

	private static Component copyComponent(Component source, Map<Component, Boolean> active) {
		if (active.put(source, Boolean.TRUE) != null) {
			// A mod can point hover text back at its own button. Preserve that text, but stop the
			// recursive hover inside the hover; a cyclic graph cannot be a stable cache key.
			MutableComponent visible = Component.empty();
			source.visit((style, text) -> {
				visible.append(Component.literal(text).setStyle(style.withHoverEvent(null)));
				return Optional.empty();
			}, Style.EMPTY);
			return visible;
		}
		try {
			MutableComponent copy;
			if (source.getContents() instanceof TranslatableContents contents) {
				Object[] arguments = contents.getArgs().clone();
				for (int i = 0; i < arguments.length; i++) {
					if (arguments[i] instanceof Component component) arguments[i] = copyComponent(component, active);
				}
				copy = Component.translatableWithFallback(contents.getKey(), contents.getFallback(), arguments);
				for (Component sibling : source.getSiblings()) copy.append(copyComponent(sibling, active));
			} else {
				copy = source.copy();
				copy.getSiblings().replaceAll(sibling -> copyComponent(sibling, active));
			}
			return copy.setStyle(snapshotStyle(source.getStyle(), active));
		} finally {
			active.remove(source);
		}
	}

	/** Incremental joins avoid building and re-flattening a Component tree for every lore prefix. */
	static final class Builder {
		private final StringBuilder plain = new StringBuilder();
		private Style[] styles = new Style[4];
		private int[] ends = new int[4];
		private int runs;

		void append(String text, int from, int to, Style style) {
			if (from == to) return;
			this.plain.append(text, from, to);
			if (this.runs > 0 && Objects.equals(this.styles[this.runs - 1], style)) {
				this.ends[this.runs - 1] = this.plain.length();
				return;
			}
			if (this.runs == this.styles.length) {
				this.styles = Arrays.copyOf(this.styles, this.runs * 2);
				this.ends = Arrays.copyOf(this.ends, this.runs * 2);
			}
			this.styles[this.runs] = style;
			this.ends[this.runs++] = this.plain.length();
		}

		void append(StyledText text, int from, int to) {
			for (int cursor = from; cursor < to;) {
				int run = text.runAt(cursor);
				int end = Math.min(to, text.ends[run] - text.offset);
				append(text.plain, cursor, end, text.styles[run]);
				cursor = end;
			}
		}

		StyledText build() {
			return new StyledText(this.plain.toString(), Arrays.copyOf(this.styles, this.runs),
				Arrays.copyOf(this.ends, this.runs), 0);
		}
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof StyledText text) || !this.plain.equals(text.plain)) return false;
		for (int cursor = 0; cursor < length();) {
			int left = runAt(cursor);
			int right = text.runAt(cursor);
			if (!this.styles[left].equals(text.styles[right])) return false;
			cursor = Math.min(length(), Math.min(this.ends[left] - this.offset, text.ends[right] - text.offset));
		}
		return true;
	}

	@Override
	public int hashCode() {
		if (!this.hashed) {
			int value = this.plain.hashCode();
			for (int cursor = 0; cursor < length();) {
				int run = runAt(cursor);
				cursor = Math.min(length(), this.ends[run] - this.offset);
				value = 31 * (31 * value + cursor) + styleHash(this.styles[run]);
			}
			this.hash = value;
			this.hashed = true;
		}
		return this.hash;
	}

	/**
	 * What tells two styles apart, without {@code Style.hashCode()}: that boxes every field into a new
	 * array and deep-hashes the hover event, and a name tag's hover event is rebuilt every frame.
	 * Events are left to {@link #equals}; equal styles still always hash alike.
	 */
	static int styleHash(Style style) {
		TextColor color = style.getColor();
		Integer shadow = style.getShadowColor();
		String insertion = style.getInsertion();
		int flags = (style.isBold() ? 1 : 0) | (style.isItalic() ? 2 : 0) | (style.isUnderlined() ? 4 : 0)
			| (style.isStrikethrough() ? 8 : 0) | (style.isObfuscated() ? 16 : 0);
		int hash = 31 * (color == null ? -1 : color.getValue()) + (shadow == null ? 0 : shadow);
		return 31 * (31 * hash + flags) + (insertion == null ? 0 : insertion.hashCode());
	}

	public String plain() {
		return this.plain;
	}

	/** Matches vanilla: unknown § pairs and a dangling § are invisible as well. */
	public static String plainOf(String text) {
		if (text == null) return "";
		int mark = text.indexOf(ChatFormatting.PREFIX_CODE);
		if (mark < 0) return text;
		StringBuilder plain = new StringBuilder(text.length()).append(text, 0, mark);
		for (int i = mark; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == ChatFormatting.PREFIX_CODE) i++;
			else plain.append(c);
		}
		return plain.toString();
	}

	/** Icon canonicalization preserves UTF-16 offsets; drawing always uses the original characters. */
	public String canonical() {
		if (this.canonical == null) this.canonical = Glyphs.canonical(this.plain);
		return this.canonical;
	}

	public int length() {
		return this.plain.length();
	}

	private int runAt(int index) {
		int found = Arrays.binarySearch(this.ends, this.offset + index + 1);
		return found >= 0 ? found : -found - 1;
	}

	public Style styleAt(int index) {
		return index < 0 || index >= length() ? Style.EMPTY : this.styles[runAt(index)];
	}

	/** End of the complete style run containing index, relative to this view. */
	public int styleEnd(int index) {
		return Math.min(length(), this.ends[runAt(index)] - this.offset);
	}

	/** Range views share immutable style storage; no per-character arrays are copied. */
	public StyledText sub(int from, int to) {
		if (from <= 0 && to >= length()) return this;
		Objects.checkFromToIndex(from, to, length());
		return new StyledText(this.plain.substring(from, to), this.styles, this.ends, this.offset + from);
	}

	public MutableComponent slice(int from, int to) {
		MutableComponent result = Component.empty();
		for (int cursor = from; cursor < to;) {
			int run = runAt(cursor);
			int end = Math.min(to, this.ends[run] - this.offset);
			result.append(Component.literal(this.plain.substring(cursor, end)).setStyle(this.styles[run]));
			cursor = end;
		}
		return result;
	}
}
