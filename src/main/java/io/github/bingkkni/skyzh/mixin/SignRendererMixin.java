package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.bingkkni.skyzh.HypixelServer;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.bingkkni.skyzh.text.SignDisplayCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import java.util.function.Function;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Never writes translated messages into SignText or its vanilla render cache. */
@Mixin(AbstractSignRenderer.class)
public abstract class SignRendererMixin {
	@Shadow @Final private Font font;

	@WrapOperation(method = "submitSignText", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/entity/SignText;getRenderMessages(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;"), require = 0)
	private FormattedCharSequence[] skyzh$side(SignText sign, boolean filtered,
		Function<Component, FormattedCharSequence> prepare, Operation<FormattedCharSequence[]> original,
		@Local(argsOnly = true) SignRenderState state) {
		if (!HypixelServer.canTranslate()) return original.call(sign, filtered, prepare);
		FormattedCharSequence[] translated = ((SignDisplayCache.Owner) sign).skyzh$displayCache()
			.get(sign, filtered, state.maxTextLineWidth, this.font, prepare);
		return translated == null ? original.call(sign, filtered, prepare) : translated;
	}
}
