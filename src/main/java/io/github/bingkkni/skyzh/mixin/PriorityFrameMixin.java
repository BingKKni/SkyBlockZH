package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.PriorityText;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/** One verified-server read for extraction and drawing together; never survives a frame/exception. */
@Mixin(Minecraft.class)
public abstract class PriorityFrameMixin {
	@WrapMethod(method = "renderFrame(Z)V", require = 0)
	private void skyzh$displayFrame(boolean advanceGameTime, Operation<Void> original) {
		PriorityText.withFrame(() -> original.call(advanceGameTime));
	}
}
