package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chinese for the values Hypixel drops into a template, rather than for the template itself.
 *
 * <p>The commission board names a task {@code "%s Mithril"}, where the server fills in one of five
 * area names. The record translates the template and copies the value across verbatim, which is
 * right for a player's name and wrong for a place: the corpus rules say place names are translated,
 * and the result on screen was "Royal Mines钛" — half a name in each language, jammed together.
 *
 * <p>One entry here — {@code Royal Mines → 皇家矿区} — fixes that everywhere the value can turn up:
 * the commission item, the scoreboard, an NPC line, a tab-list row. That is the whole reason this is
 * a table and not five more records. A value with no entry is copied across in English exactly as
 * before, which is the behaviour every proper noun wants and the reason this can be added without
 * auditing the corpus first.
 *
 * <p><b>Only some placeholders consult it.</b> The table is applied to a placeholder whose declared
 * {@code type} is listed in {@code applies_to_types}, and the list deliberately leaves out
 * {@code item_name}, {@code npc_name} and {@code player_name}: those are the positions where the
 * project's rules say the English must survive, and the cost of a wrong entry there — an invented
 * Chinese name for somebody's weapon — is far worse than the cost of leaving a place name English.
 */
public final class TermTable {
	public static final TermTable EMPTY = new TermTable(Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

	/** Dungeon stars and Master Stars on the end of an item name; not part of the name itself. */
	private static final Pattern STARS = Pattern.compile("( [✪★☆➊➋➌➍➎]+)$");

	private final Set<String> types;
	private final Map<String, String> terms;
	/** The same entries keyed in lower case, for the surfaces that shout. */
	private final Map<String, String> folded;
	/** Terms whose meaning is safe only in one placeholder type, such as a location named "Farm". */
	private final Map<String, Map<String, String>> typed;
	private final Map<String, Map<String, String>> foldedTyped;
	/** Canonical English keyed in lower case, typed and untyped together, for {@link #canonicalEnglish}. */
	private final Map<String, String> canonicalByFolded;
	/**
	 * Chinese for whole item names, taken from the corpus's own exact {@code GUI_Item} records rather
	 * than from Terms.json. Consulted only for {@code guide_item_name} placeholders — see {@link #itemName}.
	 */
	private final Map<String, String> itemNames;
	private Function<String, String> knownItemTemplate = value -> null;

	private TermTable(
		Set<String> types, Map<String, String> terms, Map<String, String> folded,
		Map<String, Map<String, String>> typed, Map<String, Map<String, String>> foldedTyped,
		Map<String, String> canonicalByFolded, Map<String, String> itemNames
	) {
		this.types = types;
		this.terms = terms;
		this.folded = folded;
		this.typed = typed;
		this.foldedTyped = foldedTyped;
		this.canonicalByFolded = canonicalByFolded;
		this.itemNames = itemNames;
	}

	/**
	 * The same table, now also answering {@code guide_item_name} placeholders from the corpus's exact item
	 * records.
	 *
	 * <p>{@code guide_item_name} is deliberately separate from {@code item_name}: names in ordinary
	 * inventory, chat and shop placeholders stay untouched. Terms.json must never
	 * invent a Chinese name for somebody's weapon. But the SkyBlock Guide writes "Donate Zombie Hat to
	 * your Museum." one line under "✖ Zombie Hat", and the corpus already decided what that item is
	 * called on the line above. Reusing that decision keeps the two lines agreeing; a name the corpus
	 * has not written down stays English exactly as before. Keys are the record's English, values the
	 * Chinese it renders to.
	 */
	public TermTable withItemNames(Map<String, String> names, Function<String, String> knownItemTemplate) {
		Map<String, String> folded = new HashMap<>();

		for (Map.Entry<String, String> name : names.entrySet()) {
			folded.putIfAbsent(name.getKey().toLowerCase(Locale.ROOT), name.getValue());
		}

		TermTable result = new TermTable(
			this.types, this.terms, this.folded, this.typed, this.foldedTyped, this.canonicalByFolded,
			Map.copyOf(folded)
		);
		result.knownItemTemplate = knownItemTemplate;
		return result;
	}

	/** Every term written for one placeholder type, English to Chinese; empty when the type has none. */
	public Map<String, String> typed(String type) {
		Map<String, String> group = this.typed.get(type == null ? "" : type.toLowerCase(Locale.ROOT));
		return group == null ? Map.of() : group;
	}

	/**
	 * Chinese for an item name captured by a {@code guide_item_name} placeholder, or {@code null}.
	 *
	 * <p>Three shapes are tried, all of them decisions the corpus has already made elsewhere: the
	 * whole name; the name without its dungeon stars ({@code Giant's Sword ✪✪✪✪✪}), which are put
	 * back afterwards; and a reforge prefix from the {@code reforge} terms in front of a known name
	 * ({@code Fabled Giant's Sword}), the same shape the tooltip name translator accepts.
	 */
	private String itemName(String value) {
		if (this.itemNames.isEmpty() || value.isEmpty()) {
			return null;
		}

		String exact = this.itemNames.get(value.toLowerCase(Locale.ROOT));

		if (exact != null) {
			return exact;
		}

		String templated = this.knownItemTemplate.apply(value);
		if (templated != null) {
			return templated;
		}

		Matcher stars = STARS.matcher(value);

		if (stars.find()) {
			String bare = itemName(value.substring(0, stars.start()));
			return bare == null ? null : bare + stars.group(1);
		}

		int space = value.indexOf(' ');

		// A catalog item that merely begins with a reforge word (Hyper Catalyst) is not a reforged item.
		if (space > 0 && !ItemNames.isBaseName(value)) {
			for (Map.Entry<String, String> reforge : typed("reforge").entrySet()) {
				String prefix = reforge.getKey() + " ";

				if (value.startsWith(prefix) && value.length() > prefix.length()) {
					String rest = itemName(value.substring(prefix.length()));

					if (rest != null) {
						return reforge.getValue() + " " + rest;
					}
				}
			}
		}

		return null;
	}

	public static TermTable from(JsonObject json) {
		Set<String> types = new HashSet<>();
		Map<String, String> terms = new HashMap<>();
		Map<String, Map<String, String>> typed = new HashMap<>();

		if (json.has("applies_to_types") && json.get("applies_to_types").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("applies_to_types")) {
				types.add(element.getAsString().toLowerCase(Locale.ROOT));
			}
		}

		if (json.has("terms") && json.get("terms").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("terms")) {
				JsonObject term = element.getAsJsonObject();
				String en = string(term, "en");
				String zh = string(term, "zh");

				if (!en.isEmpty() && !zh.isEmpty()) {
					if (term.has("types") && term.get("types").isJsonArray()) {
						for (JsonElement type : term.getAsJsonArray("types")) {
							typed.computeIfAbsent(type.getAsString().toLowerCase(Locale.ROOT), ignored -> new HashMap<>())
								.put(en, zh);
						}
					} else {
						terms.put(en, zh);
					}
				}
			}
		}

		Map<String, String> folded = new HashMap<>();

		for (Map.Entry<String, String> term : terms.entrySet()) {
			folded.putIfAbsent(term.getKey().toLowerCase(Locale.ROOT), term.getValue());
		}

		Map<String, Map<String, String>> frozenTyped = new HashMap<>();
		Map<String, Map<String, String>> foldedTyped = new HashMap<>();

		for (Map.Entry<String, Map<String, String>> group : typed.entrySet()) {
			frozenTyped.put(group.getKey(), Map.copyOf(group.getValue()));
			Map<String, String> foldedGroup = new HashMap<>();

			for (Map.Entry<String, String> term : group.getValue().entrySet()) {
				foldedGroup.putIfAbsent(term.getKey().toLowerCase(Locale.ROOT), term.getValue());
			}

			foldedTyped.put(group.getKey(), Map.copyOf(foldedGroup));
		}

		Map<String, String> canonicalByFolded = new HashMap<>();

		for (String en : terms.keySet()) {
			canonicalByFolded.putIfAbsent(en.toLowerCase(Locale.ROOT), en);
		}

		for (Map<String, String> group : typed.values()) {
			for (String en : group.keySet()) {
				canonicalByFolded.putIfAbsent(en.toLowerCase(Locale.ROOT), en);
			}
		}

		return new TermTable(
			Set.copyOf(types), Map.copyOf(terms), Map.copyOf(folded),
			Map.copyOf(frozenTyped), Map.copyOf(foldedTyped), Map.copyOf(canonicalByFolded), Map.of()
		);
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
	}

	/**
	 * Whether this table is consulted for a placeholder of the given type at all.
	 *
	 * <p>The distinction {@link #translate} folds together — "not this kind of placeholder" and
	 * "nobody has written this term down yet" — matters to anything reporting what is still English:
	 * only the second is a gap somebody could close.
	 */
	public boolean applies(String type) {
		return this.types.contains(type == null ? "" : type.toLowerCase(Locale.ROOT));
	}

	/**
	 * The Chinese for a captured value, or {@code null} to leave it in English — which covers both
	 * "this kind of placeholder is never translated" and "nobody has written this term down yet".
	 *
	 * <p>Whole values only. Translating the {@code Amber} out of {@code Amber Gemstone} while leaving
	 * the rest would need the table to know how the two combine in Chinese, and getting that wrong
	 * produces exactly the mixed-language mess this exists to remove. A compound whose parts are both
	 * worth translating gets its own entry.
	 */
	public String translate(String type, String value) {
		if ("guide_item_name".equalsIgnoreCase(type)) {
			return itemName(value);
		}

		if (!applies(type)) {
			return null;
		}

		String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
		String exact = lookup(this.typed.get(normalizedType), value);

		if (exact == null) {
			exact = this.terms.get(value);
		}

		if (exact != null) {
			return exact;
		}

		// The same value is not spelled the same way everywhere: the boss bar shouts
		// "PASSIVE EVENT GONE WITH THE WIND", the sidebar widget writes "Mining Event: Gone with the
		// Wind", and the commission-complete broadcast shouts a task name the menu writes in title
		// case. Chinese has no case for any of that to survive, so one entry answers for all of them.
		String lower = value.toLowerCase(Locale.ROOT);
		String folded = lookup(this.foldedTyped.get(normalizedType), lower);

		if (folded == null) {
			folded = this.folded.get(lower);
		}

		return folded != null ? folded : owned(normalizedType, value);
	}

	private static String lookup(Map<String, String> terms, String value) {
		return terms == null ? null : terms.get(value);
	}

	/**
	 * Chinese for a value that belongs to somebody — {@code inkkni's Museum} — or {@code null}.
	 *
	 * <p>The one shape of value that cannot be written down. The owner is a player name, so the whole
	 * string is different for every player and listing it is not a matter of somebody getting round
	 * to it; what kind of place it is <em>can</em> be listed, and one entry for {@code Museum} then
	 * answers for everybody's. Standing in a museum otherwise left the sidebar's location row — the
	 * one line of the HUD that is always on screen — in English.
	 *
	 * <p>Tried last, so every name the table holds in full is answered by its own entry and never
	 * taken apart: {@code Rampart's Quarry} is 壁垒采石场, not "Rampart 的采石场".
	 *
	 * <p>The space before 的 is written here because nothing else will write it. The seam rule that
	 * puts one between Latin and Chinese runs where a record's text meets a captured value, and both
	 * halves of this string are the same value.
	 */
	private String owned(String type, String value) {
		Possessive.Owned owned = Possessive.split(value);

		if (owned == null) {
			return null;
		}

		String thing = lookup(this.typed.get(type), owned.thing());

		if (thing == null) {
			thing = this.terms.get(owned.thing());
		}

		if (thing == null) {
			String lower = owned.thing().toLowerCase(Locale.ROOT);
			thing = lookup(this.foldedTyped.get(type), lower);

			if (thing == null) {
				thing = this.folded.get(lower);
			}
		}

		return thing == null ? null : owned.owner() + " 的" + thing;
	}

	/**
	 * The corpus spelling of a known term, or {@code null} if this value is not in the table at all.
	 *
	 * Typed and untyped entries both count, independently of placeholder eligibility.
	 */
	public String canonicalEnglish(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}

		if (this.terms.containsKey(value)) {
			return value;
		}

		for (Map<String, String> group : this.typed.values()) {
			if (group.containsKey(value)) {
				return value;
			}
		}

		return this.canonicalByFolded.get(value.toLowerCase(Locale.ROOT));
	}

	public int size() {
		return this.terms.size() + this.typed.values().stream().mapToInt(Map::size).sum();
	}
}
