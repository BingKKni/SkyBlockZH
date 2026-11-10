package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.bingkkni.skyzh.HypixelServer;
import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.text.DocumentTranslation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both painting and hit-testing visit the same translated, vanilla-wrapped page. */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {
	@Shadow private int cachedPage;
	@Unique private boolean skyzh$translated;
	@Unique private int skyzh$generation = -1;

	protected BookViewScreenMixin(Component title) { super(title); }

	@Inject(method = "visitText", at = @At("HEAD"), require = 0)
	private void skyzh$invalidate(CallbackInfo ci) {
		boolean enabled = HypixelServer.canTranslate();
		int generation = SkyZHConfig.generation();
		if (enabled != this.skyzh$translated || generation != this.skyzh$generation) {
			this.cachedPage = -1;
			this.skyzh$translated = enabled;
			this.skyzh$generation = generation;
		}
	}

	@WrapOperation(method = "visitText", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/Component;"), require = 0)
	private Component skyzh$page(BookViewScreen.BookAccess access, int page, Operation<Component> original) {
		Component source = original.call(access, page);
		return HypixelServer.canTranslate()
			? DocumentTranslation.book(source, 114, this.font::width) : source;
	}
}
