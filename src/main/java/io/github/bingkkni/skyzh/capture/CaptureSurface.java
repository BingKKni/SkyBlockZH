package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.text.Surface;

/**
 * Where a captured line came from, at the granularity {@code original_text/} files are organised by.
 *
 * <p>Finer than {@link Surface} on purpose. The engine only needs to know which bucket of records may
 * answer for a line, so NPC dialogue and system broadcasts are both {@link Surface#CHAT}; a capture
 * has to land in a file a translator will later open, and those two live in different directories
 * with different naming rules — {@code NPC_Message/King.json} against
 * {@code ChatMessage/Mining_Events.json}. Keeping the split here means the written file already sits
 * where its finished version will.
 */
public enum CaptureSurface {
	/** Dialogue with a {@code [NPC] Name: } tag in front of it. */
	NPC_MESSAGE(Surface.CHAT, "NPC_Message"),
	/** Everything else the server says in chat. */
	CHAT_MESSAGE(Surface.CHAT, "ChatMessage"),
	/** The line at the top of a chest or menu. */
	GUI_TITLE(Surface.GUI_TITLE, "GUI_Title"),
	/** An item's name, and each line of its lore. */
	GUI_ITEM(Surface.ITEM, "GUI_Item"),
	/** A row of the sidebar, or the sidebar's own title. */
	SCOREBOARD(Surface.SCOREBOARD, "ScoreBoard"),
	/** A row of the player list, or its header and footer. */
	TABLIST(Surface.TABLIST, "TabList"),
	/** The bar across the top of the screen. */
	BOSS_BAR(Surface.BOSS_BAR, "BossBar"),
	/** The prompt above the hotbar. */
	ACTION_BAR(Surface.ACTION_BAR, "ActionBar"),
	/** Title and subtitle, and anything else without a home. */
	MISC(Surface.MISC, "Misc");

	private final Surface surface;
	private final String directory;

	CaptureSurface(Surface surface, String directory) {
		this.surface = surface;
		this.directory = directory;
	}

	/** The bucket of records allowed to answer for text from here. */
	public Surface surface() {
		return this.surface;
	}

	/** The directory name this text's finished record would live in under a gameplay category. */
	public String directory() {
		return this.directory;
	}
}
