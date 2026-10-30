package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.PriorityText;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The font is a second replacement point (e.g. SkyHanni Visual Words), after GUI extraction. */
@Mixin(value = Font.class, priority = Integer.MAX_VALUE)
public abstract class ForceFontMixin {
	@Shadow @Final private StringSplitter splitter;

	@Inject(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceGlyphs(FormattedCharSequence text, float x, float y, int color,
		boolean shadow, boolean includeEmpty, int background, CallbackInfoReturnable<Font.PreparedText> info) {
		if (!PriorityText.force()) return;
		FormattedCharSequence translated = PriorityText.resolve(text);
		if (translated != null) info.setReturnValue(skyzh$prepare(translated, x, y, color, shadow, includeEmpty, background));
	}

	@Inject(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceStringGlyphs(String text, float x, float y, int color,
		boolean shadow, int background, CallbackInfoReturnable<Font.PreparedText> info) {
		if (!PriorityText.force() || text == null) return;
		FormattedCharSequence translated = PriorityText.resolve(Component.literal(text).getVisualOrderText());
		if (translated != null) info.setReturnValue(skyzh$prepare(translated, x, y, color, shadow, false, background));
	}

	@Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$protectedWidth(FormattedCharSequence text, CallbackInfoReturnable<Integer> info) {
		if (PriorityText.force() && text instanceof PriorityText.ProtectedText)
			info.setReturnValue(Mth.ceil(this.splitter.stringWidth(text)));
	}

	private Font.PreparedText skyzh$prepare(FormattedCharSequence text, float x, float y, int color,
		boolean shadow, boolean includeEmpty, int background) {
		Font font = (Font) (Object) this;
		Font.PreparedTextBuilder builder = font.new PreparedTextBuilder(x, y, color, background, shadow, includeEmpty);
		text.accept(builder);
		return builder;
	}
}
