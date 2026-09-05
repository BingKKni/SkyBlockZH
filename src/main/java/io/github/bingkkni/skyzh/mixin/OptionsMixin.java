package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HoldOriginal;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Register before the first load, so vanilla reads and writes this mapping in options.txt. */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Shadow @Final @Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load", at = @At("HEAD"), require = 0)
	private void skyzh$registerKey(CallbackInfo info) {
		this.keyMappings = HoldOriginal.register(this.keyMappings);
	}
}
