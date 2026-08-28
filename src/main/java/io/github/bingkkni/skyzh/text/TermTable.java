package io.github.bingkkni.skyzh.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	public static final TermTable EMPTY = new TermTable(Set.of(), Map.of(), Map.of(), Map.of(), Map.of());

	private final Set<String> types;
	private final Map<String, String> terms;
	/** The same entries keyed in lower case, for the surfaces that shout. */
	private final Map<String, String> folded;
	/** Terms whose meaning is safe only in one placeholder type, such as a location named "Farm". */
	private final Map<String, Map<String, String>> typed;
	private final Map<String, Map<String, String>> foldedTyped;

	private TermTable(
		Set<String> types, Map<String, String> terms, Map<String, String> folded,
		Map<String, Map<String, String>> typed, Map<String, Map<String, String>> foldedTyped
	) {
		this.types = types;
		this.terms = terms;
		this.folded = folded;
		this.typed = typed;
		this.foldedTyped = foldedTyped;
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

		return new TermTable(
			Set.copyOf(types), Map.copyOf(terms), Map.copyOf(folded),
			Map.copyOf(frozenTyped), Map.copyOf(foldedTyped)
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

	public int size() {
		return this.terms.size() + this.typed.values().stream().mapToInt(Map::size).sum();
	}
}
