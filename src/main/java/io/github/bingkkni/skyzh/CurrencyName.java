package io.github.bingkkni.skyzh;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Independently switchable server currencies; aliases are whole names, never substrings. */
public enum CurrencyName {
	COINS("Coins", "Coin", "Purse", "Piggy"),
	BITS("Bits", "Bit"),
	MOTES("Motes", "Mote"),
	SOWDUST("Sowdust"),
	COPPER("Copper"),
	MITHRIL("Mithril Powder"),
	GEMSTONE("Gemstone Powder"),
	GLACITE("Glacite Powder"),
	PELTS("Pelts", "Pelt"), FOREST("Forest Whispers", "Forest Whisper"),
	NORTH_STARS("North Stars", "North Star"), BINGO("Bingo Points", "Bingo Point"),
	CARNIVAL("Carnival Tokens", "Carnival Token"), FISHY("Fishy Treats", "Fishy Treat"),
	NOTCOINS("NotCoins", "NotCoin"), CHOCOLATE("Chocolate"), FOSSIL_DUST("Fossil Dust"),
	MOUNTAIN("Tokens of the Mountain", "Token of the Mountain"),
	WITHER("Wither Essence"), UNDEAD("Undead Essence"), CRIMSON("Crimson Essence"),
	SPIDER("Spider Essence"), DRAGON("Dragon Essence"), GOLD("Gold Essence"),
	DIAMOND("Diamond Essence"), ICE("Ice Essence"), FOREST_ESSENCE("Forest Essence"),
	FOSSIL("Fossil Essence"), SAFARI("Safari Essence");

	private static final Map<String, CurrencyName> BY_NAME = new HashMap<>();
	static {
		for (CurrencyName currency : values())
			for (String alias : currency.aliases) BY_NAME.put(alias.toLowerCase(Locale.ROOT), currency);
	}

	private final String[] aliases;
	CurrencyName(String... aliases) { this.aliases = aliases; }
	public String key() { return aliases[0].replace(" ", ""); }
	public long mask() { return 1L << ordinal(); }
	public static CurrencyName of(String text) { return BY_NAME.get(text.toLowerCase(Locale.ROOT)); }
}
