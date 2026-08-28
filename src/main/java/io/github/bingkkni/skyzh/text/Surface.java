package io.github.bingkkni.skyzh.text;

import java.util.Locale;

/**
 * Where on screen a piece of text is about to be drawn.
 *
 * <p>Matching is scoped to one surface at a time, which is the whole point of the folder layout in
 * {@code original_text/}: "Commissions" as a container title and "Commissions" said by an NPC are
 * separate records that may end up with different Chinese, and neither is allowed to answer for the
 * other. A lookup therefore never sees entries from another surface.
 */
public enum Surface {
	/** NPC dialogue and system broadcasts — {@code NPC_Message/}, {@code ChatMessage/}. */
	CHAT,
	/** Chest/menu titles — {@code GUI_Title/}. */
	GUI_TITLE,
	/** Item name and lore, in a container or in the player's own inventory — {@code GUI_Item/}, {@code _shared/}. */
	ITEM,
	/** The short prompt above the hotbar — {@code ActionBar/}. */
	ACTION_BAR,
	/** The bar at the top of the screen — {@code BossBar/}. */
	BOSS_BAR,
	/** The sidebar down the right of the screen, title and rows — {@code ScoreBoard/}. */
	SCOREBOARD,
	/**
	 * The player list behind the Tab key, header and rows — {@code TabList/}.
	 *
	 * <p>Separate from {@link #SCOREBOARD} even though SkyBlock puts related numbers in both: the two
	 * are different render surfaces with different line shapes, and the same word may well have been
	 * given room for a longer translation in one than the other.
	 */
	TABLIST,
	/**
	 * The floating text over an NPC's head — {@code Hologram/}.
	 *
	 * <p>An armour stand's name, drawn in the world rather than on the HUD, which is why it is its own
	 * surface: the same word means something else there. {@code CLICK} over an NPC is an instruction to
	 * the player and reads as 右键点击; {@code CLICK} in a chat line is part of a sentence. And the names
	 * on these stands are mostly NPC names, which stay English — only the ones that are a job rather
	 * than a person (Blacksmith, Lift Operator) have a record.
	 */
	HOLOGRAM,
	/** Title/subtitle and anything else — {@code Misc/}. */
	MISC;

	/**
	 * Maps a directory name under a gameplay category to the surface it feeds. Unknown directories
	 * are ignored rather than guessed at: a folder nobody wrote a loader rule for is more likely a
	 * new category than a misspelling of an old one.
	 */
	public static Surface fromDirectory(String directory) {
		return switch (directory.toLowerCase(Locale.ROOT)) {
			case "npc_message", "chatmessage" -> CHAT;
			case "gui_title" -> GUI_TITLE;
			case "gui_item", "_shared" -> ITEM;
			case "actionbar" -> ACTION_BAR;
			case "bossbar" -> BOSS_BAR;
			case "scoreboard" -> SCOREBOARD;
			case "tablist" -> TABLIST;
			case "hologram" -> HOLOGRAM;
			case "misc" -> MISC;
			default -> null;
		};
	}
}
