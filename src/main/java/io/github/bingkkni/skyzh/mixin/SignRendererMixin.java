package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.text.DisplayTextCache;
import io.github.bingkkni.skyzh.text.DocumentTranslation;
import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.platform.ServerSignData;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Never writes translated messages into SignText or its vanilla render cache. */
@Mixin(AbstractSignRenderer.class)
public abstract class SignRendererMixin {
	@Redirect(method = "submitSignText", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/entity/SignText;getRenderMessages(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;"), require = 0)
	private FormattedCharSequence[] skyzh$side(SignText sign, boolean filtered,
		Function<Component, FormattedCharSequence> prepare) {
		if (!HypixelServer.canTranslate()) return sign.getRenderMessages(filtered, prepare);
		Component original = DocumentTranslation.join(ServerSignData.lines(sign, filtered));
		Component translated = DisplayTextCache.get(original, Surface.SIGN, DisplayTextCache.Mode.BLOCK,
			text -> DocumentTranslation.available(text.slice(0, text.length()), Surface.SIGN));
		if (StyledText.of(original).equals(StyledText.of(translated))) return sign.getRenderMessages(filtered, prepare);
		List<Component> rows = DocumentTranslation.lines(translated);
		// A malformed external template must never silently erase a fifth row.
		if (rows.size() != 4) return sign.getRenderMessages(filtered, prepare);
		FormattedCharSequence[] result = new FormattedCharSequence[4];
		for (int i = 0; i < 4; i++) result[i] = prepare.apply(rows.get(i));
		return result;
	}
}
