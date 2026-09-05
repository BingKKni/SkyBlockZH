package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HoldOriginal;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe scancode-only keys even in containers; never consume an event or change other mappings. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), require = 0)
	private void skyzh$observeKey(long window, int action, KeyEvent event, CallbackInfo info) {
		HoldOriginal.keyEvent(window, action, event);
	}
}
