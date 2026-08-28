package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.capture.TextCapture;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The boss bar, captured where the server's packet has just been applied.
 *
 * <p>{@code TAIL} rather than {@code HEAD} because the packet is a visitor — add, remove, set
 * progress, set name — and unpicking which of those it is means reimplementing vanilla's dispatch.
 * Reading the map afterwards asks the same question in one line: whatever the packet meant, these are
 * the bars now showing and these are their names. Duplicate names cost a hash lookup and nothing else.
 *
 * <p>The map is written by this method and by nothing else in the game, which is what makes it server
 * text: a mod wanting a bar of its own draws one, it does not put a {@code LerpingBossEvent} in here.
 *
 * <p>SkyBlock's bars have a running timer in the name — "PASSIVE EVENT GONE WITH THE WIND RUNNING FOR
 * 12m 30s" — so each second produces a line that differs from the last only in the number. That is
 * precisely the case the capture's template merge exists for, and the timer ends up as a placeholder
 * with its observed values listed rather than as six hundred near-identical records.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayCaptureMixin {
	@Shadow
	@Final
	private Map<UUID, LerpingBossEvent> events;

	@Inject(method = "update", at = @At("TAIL"), require = 0)
	private void skyzh$captureBossBar(ClientboundBossEventPacket packet, CallbackInfo info) {
		for (LerpingBossEvent event : this.events.values()) {
			TextCapture.bossBar(event.getName());
		}
	}
}
