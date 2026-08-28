package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.capture.ScreenWatcher;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client tick, which is where the two surfaces that have no packet of their own get read.
 *
 * <p>The mod has no Fabric API dependency — every hook it has is a mixin into vanilla — so the tick
 * is a mixin too rather than a {@code ClientTickEvents} registration. One fewer runtime dependency in
 * a SkyBlock modpack is one fewer way to collide with SkyHanni or SkyBlocker.
 *
 * <p>{@link ScreenWatcher#tick} returns on its first line while capture is switched off, which is the
 * state every ordinary player is in.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCaptureMixin {
	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void skyzh$captureTick(CallbackInfo info) {
		ScreenWatcher.tick();
	}
}
