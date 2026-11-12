package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.compat.ChatCompatibility;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A scoped visual view for custom chat splitters; getString/visit/history remain original. */
@Mixin(MutableComponent.class)
public abstract class ChatVisualTextMixin {
	@Inject(method = "getVisualOrderText", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$chatVisual(CallbackInfoReturnable<FormattedCharSequence> info) {
		Component source = (Component) (Object) this;
		Component translated = ChatCompatibility.visual(source);
		if (translated != null && translated != source) info.setReturnValue(translated.getVisualOrderText());
	}
}
