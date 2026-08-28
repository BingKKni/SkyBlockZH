package io.github.bingkkni.skyzh.capture;

import io.github.bingkkni.skyzh.SkyZHConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * The two surfaces that arrive as state rather than as a message: the sidebar and the player list.
 *
 * <p>Neither has a packet carrying a finished line. A sidebar row is assembled from a score entry and
 * its team's prefix and suffix, which arrive in separate packets in either order; a tab-list row is a
 * display name hung off a player entry. The first moment a whole line exists is when something reads
 * the assembled state — so that is what this does, on a timer, exactly the way vanilla's own renderer
 * does it.
 *
 * <p><b>Why this is the right hook and not a lazy one.</b> Reading the {@code Scoreboard} rather than
 * the drawn sidebar means capture keeps working when another mod has replaced the sidebar's rendering
 * entirely — SkyHanni's Custom Scoreboard does exactly that — because the state it re-renders from is
 * the state read here. A line only visible through another mod's renderer is still Hypixel's line,
 * and it is still the line a record has to match.
 *
 * <p>Ten ticks, not one. Nothing on either surface appears for less than half a second, the text is
 * deduplicated anyway, and the point of a capture feature is to be free when it is not finding
 * anything. The timer covers the reading of those two surfaces and nothing else — where the player is
 * standing is re-read every tick, because that is the label everything captured in between is filed
 * under and it is wrong for as long as it is stale.
 */
public final class ScreenWatcher {
	private static final int EVERY_TICKS = 10;

	private static int countdown;
	private static boolean connected;

	private ScreenWatcher() {
	}

	/** Called from the client tick. Does nothing at all while the switch is off. */
	public static void tick() {
		if (!SkyZHConfig.get().captureUntranslated) {
			// Except finish what was started. Switching capture off in Mod Menu mid-session must not
			// strand the lines that are waiting for the sidebar to name the area: nothing new is taken,
			// but what is already held is filed under the last place that was known.
			TextCapture.tick();

			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null) {
			if (connected) {
				connected = false;
				TextCapture.disconnected();
			}

			return;
		}

		connected = true;

		// Where the player is standing is read every tick, and the two surfaces are read every tenth.
		// The throttle belongs on the reading, not on the context: the area is the label on every line
		// captured between two ticks, and a warp that lands half a second before the next refresh
		// would otherwise file an island's worth of arrival chatter under the island left behind.
		CaptureContext.refresh();
		TextCapture.tick();

		if (--countdown > 0) {
			return;
		}

		countdown = EVERY_TICKS;

		if (!CaptureContext.active()) {
			return;
		}

		sidebar(level.getScoreboard());
		tabList(minecraft.getConnection());
	}

	private static void sidebar(Scoreboard scoreboard) {
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

		if (objective == null) {
			return;
		}

		// The title shimmers — Hypixel re-sends SKYBLOCK every tick with the highlight one letter
		// further along — so it is captured by its text, which never changes, and the colours of
		// whichever frame got there first are the ones written down. Nothing better exists to record:
		// the animation is not part of the sentence.
		TextCapture.sidebar(objective.getDisplayName());

		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) {
				continue;
			}

			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			TextCapture.sidebar(PlayerTeam.formatNameForTeam(team, entry.ownerName()));
		}
	}

	private static void tabList(ClientPacketListener connection) {
		if (connection == null) {
			return;
		}

		// The header and footer are not read here: they arrive whole in ClientboundTabListPacket and
		// are captured in that handler, which is one packet rather than a walk every half second.
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();

			// A null display name is a real player under their real name, which is not text anybody
			// translates. SkyBlock's rows are all display names, so nothing is lost.
			if (name != null) {
				TextCapture.tabList(name, "行");
			}
		}
	}
}
