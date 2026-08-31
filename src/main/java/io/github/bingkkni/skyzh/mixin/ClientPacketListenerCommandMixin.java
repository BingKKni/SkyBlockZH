package io.github.bingkkni.skyzh.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.bingkkni.skyzh.SkyZHCommand;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes {@code /skyzh} a command this client answers itself, and one Hypixel never hears about.
 *
 * <p>This mod carries no Fabric API, so there is no command-registration event to hook — and that is
 * no loss here, because the honest place for a client command is the last point before the text
 * becomes a packet. {@code ChatScreen} strips the slash and hands the rest to
 * {@code sendCommand}; this cancels there when the words are ours, so nothing is transmitted and the
 * server's command handling is not involved at all. A player on Hypixel typing {@code /skyzh} does not
 * show up in any log but their own.
 *
 * <p>The second hook is cosmetic and worth explaining. The client colours a command red while it is
 * being typed unless the word exists in the tree the server sent, and it offers no completions for it.
 * {@code handleCommands} replaces that tree wholesale every time it arrives, so the words are added
 * back on its tail — every time, because the packet may arrive more than once in a session and each
 * one discards what was there before. The nodes carry an {@code executes} that returns zero and is
 * never called: brigadier needs a node to be executable before it will draw it as valid, and by the
 * time anything could dispatch it the {@code sendCommand} hook above has already cancelled the line.
 *
 * <p>{@code require = 0} like every other hook in this mod. A Minecraft update that renames either
 * method costs the command, or costs its highlighting, and never costs a boot — and the two are
 * independent, so losing the cosmetic half leaves a command that still works.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCommandMixin {
	/**
	 * Ours or the server's, decided before anything is sent.
	 *
	 * @param command the line as typed, with the leading slash already removed
	 */
	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$runOwnCommand(String command, CallbackInfo info) {
		if (SkyZHCommand.run(command)) {
			info.cancel();
		}
	}

	/** Puts the mod's own words back into the tree the server just replaced. */
	@Inject(method = "handleCommands", at = @At("TAIL"), require = 0)
	private void skyzh$addOwnCommands(ClientboundCommandsPacket packet, CallbackInfo info) {
		CommandDispatcher<ClientSuggestionProvider> commands =
			((ClientPacketListener) (Object) this).getCommands();

		for (String alias : SkyZHCommand.ALIASES) {
			LiteralArgumentBuilder<ClientSuggestionProvider> root = skyzh$node(alias);

			for (String plain : SkyZHCommand.PLAIN) {
				root.then(skyzh$node(plain));
			}

			LiteralArgumentBuilder<ClientSuggestionProvider> flip = skyzh$node("switch");

			for (String which : SkyZHCommand.SWITCHES) {
				flip.then(skyzh$node(which));
			}

			commands.register(root.then(flip));
		}
	}

	/** One executable literal. The {@code executes} exists to make it draw as valid; see the class note. */
	private static LiteralArgumentBuilder<ClientSuggestionProvider> skyzh$node(String word) {
		return LiteralArgumentBuilder.<ClientSuggestionProvider>literal(word).executes(context -> 0);
	}
}
