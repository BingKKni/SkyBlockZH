package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.text.SignDisplayCache;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Attaches derived display data only; messages and the vanilla render cache stay untouched. */
@Mixin(SignText.class)
public abstract class SignTextMixin implements SignDisplayCache.Owner {
	@Unique private SignDisplayCache skyzh$display;

	@Override public SignDisplayCache skyzh$displayCache() {
		if (this.skyzh$display == null) this.skyzh$display = new SignDisplayCache();
		return this.skyzh$display;
	}
}
