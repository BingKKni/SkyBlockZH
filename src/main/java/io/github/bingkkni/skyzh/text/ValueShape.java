package io.github.bingkkni.skyzh.text;

/**
 * What kind of thing a placeholder's value is, judged against the {@code example} the corpus wrote
 * beside it.
 *
 * <p>{@link Capture} bounds a placeholder by the {@code type} a record declared, which stops a value
 * swallowing a whole sentence. It cannot stop a value swallowing <em>another kind of value</em>, and
 * that is how the worst failure this engine has produced got through:
 *
 * <pre>
 *   record  _shared/Item_Lore.json#item_gemstones_slots
 *           "Gemstones: %s"   type: raw   example: "[❥] [❥]"      -> 宝石槽: %s
 *   line    §7Gemstones: §e8-10                                    (Glacite Tunnels pity lore)
 *   drawn   宝石槽: 8-10
 * </pre>
 *
 * <p>The record is about the gemstone <em>slots</em> on a piece of equipment. The line is one row of
 * the Glacite Tunnels pity table, where {@code 8-10} is a block's pity value. The words either side of
 * the value are identical, a {@code raw} placeholder compiles to {@link Capture#PHRASE} and
 * {@code 8-10} is a perfectly good phrase — so the record answered, and drew a sentence about mining
 * ore as one about socketing jewellery.
 *
 * <p>What makes this the expensive kind of bug is the second half. The line came out fully Chinese
 * and in the right colours, so {@link io.github.bingkkni.skyzh.capture.Classifier} had nothing to
 * report: not untranslated, not mixed, not miscoloured. The line vanished from the capture entirely.
 * A wrong translation had erased the only evidence of itself, which is why the tooltip on screen had
 * one more line in it than the capture file did.
 *
 * <p>The corpus already knew enough to refuse this. Compiled {@code raw} placeholders carry an
 * {@code example}, and {@code "[❥] [❥]"} looks nothing like {@code "8-10"}. That knowledge was simply
 * thrown away at load time, where only {@code type} was read. So it is read now, folded to the one
 * question worth asking of it, and a value that answers differently from the example is not this
 * record's value. {@code checkTranslations} keeps that guarantee pinned as the corpus changes.
 *
 * <p>Deliberately coarse. The rule is not "the value resembles the example" — {@code Mithril} and
 * {@code Royal Mines Mithril} are the same kind of thing and a record has to cover both — but "the
 * value is the same kind of thing as the example", where there are three kinds and symbols are not
 * one of them. Requiring exact family equality was tested and rejected because legitimate values can
 * gain or lose a family (hex colour codes and Roman-numeral ranges are common examples); sharing one
 * family preserves those variants while still separating icon-only slots from numeric pity values.
 */
public final class ValueShape {
	private static final int DIGIT = 1;
	private static final int LATIN = 2;
	private static final int HAN = 4;

	private ValueShape() {
	}

	/**
	 * The families of character present in a value: some mixture of digits, Latin letters and Han.
	 *
	 * <p>Symbols contribute nothing. A bracket, an icon from the server's private-use font, a hyphen
	 * between two numerals and the {@code %} on a percentage are decoration <em>around</em> a value,
	 * and whether a particular row happens to carry one is not the sort of thing a record can be
	 * wrong about. Punctuation that belongs to a number is read as part of it, so {@code 1,234} and
	 * {@code 20%} and {@code -5} are all plain digits rather than digits-and-a-symbol.
	 */
	public static int of(String value) {
		int families = 0;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (Character.isWhitespace(c)) {
				continue;
			}

			if (c >= '0' && c <= '9') {
				families |= DIGIT;
			} else if (",.-+%".indexOf(c) >= 0 && besideDigit(value, i)) {
				families |= DIGIT;
			} else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
				families |= LATIN;
			} else if (c >= 0x3400 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF) {
				families |= HAN;
			}
		}

		return families;
	}

	/**
	 * Whether a value could be the same kind of thing as its record's example.
	 *
	 * <p>Sharing one family is enough: a value may vary from its example freely within a kind, and
	 * only crossing from one kind into another says the record is answering for a line it was not
	 * written about. Two values that are both pure decoration — an icon string against an icon string
	 * — agree, because there is nothing to disagree about.
	 */
	public static boolean agrees(int example, int value) {
		if (example == 0 || value == 0) {
			return example == value;
		}

		return (example & value) != 0;
	}

	private static boolean besideDigit(String value, int at) {
		boolean before = at > 0 && value.charAt(at - 1) >= '0' && value.charAt(at - 1) <= '9';
		boolean after = at + 1 < value.length()
			&& value.charAt(at + 1) >= '0' && value.charAt(at + 1) <= '9';

		return before || after;
	}
}
