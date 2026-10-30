package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.PriorityText;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom HUDs must measure the same known server translation they will draw. */
@Mixin(value = Font.class, priority = 2000)
public abstract class PriorityFontMixin {
	@Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$stringWidth(String source, CallbackInfoReturnable<Integer> info) {
		if (source != null && PriorityText.active()) skyzh$width(Component.literal(source).getVisualOrderText(), info);
	}

	@Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$componentWidth(FormattedText source, CallbackInfoReturnable<Integer> info) {
		if (source != null && PriorityText.active()) skyzh$width(Language.getInstance().getVisualOrder(source), info);
	}

	@Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$sequenceWidth(FormattedCharSequence source, CallbackInfoReturnable<Integer> info) {
		if (!(source instanceof PriorityText.ProtectedText)) skyzh$width(source, info);
	}

	private void skyzh$width(FormattedCharSequence source, CallbackInfoReturnable<Integer> info) {
		FormattedCharSequence translated = PriorityText.resolve(source);
		if (translated != null && translated != source) info.setReturnValue(((Font) (Object) this).width(translated));
	}
}
