package io.github.bingkkni.skyzh.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.bingkkni.skyzh.hook.ForceChat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use one source/layout for drawing, hovering, clicking and scrolling; never replace allMessages. */
@Mixin(value = ChatComponent.class, priority = Integer.MAX_VALUE)
public abstract class ForceChatMixin {
	@Shadow @Final @Mutable private List<GuiMessage.Line> trimmedMessages;
	@Shadow private int chatScrollbarPos;
	@Shadow private boolean newMessageSinceScroll;
	@Shadow private int getWidth() { throw new AssertionError(); }
	@Shadow private double getScale() { throw new AssertionError(); }
	@Shadow public abstract int getLinesPerPage();
	@Unique private boolean skyzh$usingForceView;
	@Unique private boolean skyzh$updatingVanilla;

	@WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", require = 0)
	private void skyzh$drawSource(GuiGraphicsExtractor graphics, Font font, int tick, int mouseX, int mouseY,
		ChatComponent.DisplayMode mode, boolean hovered, Operation<Void> original) {
		skyzh$withSource(() -> original.call(graphics, font, tick, mouseX, mouseY, mode, hovered));
	}

	@WrapMethod(method = "captureClickableText", require = 0)
	private void skyzh$clickSource(ActiveTextCollector collector, int height, int tick,
		ChatComponent.DisplayMode mode, Operation<Void> original) {
		skyzh$withSource(() -> original.call(collector, height, tick, mode));
	}

	@WrapMethod(method = "scrollChat", require = 0)
	private void skyzh$scrollSource(int amount, Operation<Void> original) {
		if (this.skyzh$updatingVanilla) original.call(amount);
		else skyzh$withSource(() -> original.call(amount));
	}

	@Unique private void skyzh$withSource(Runnable draw) {
		if (this.skyzh$usingForceView || !ForceChat.ownsDisplay()) { draw.run(); return; }
		List<GuiMessage.Line> previous = this.trimmedMessages;
		int previousScroll = this.chatScrollbarPos;
		boolean previousUnread = this.newMessageSinceScroll;
		this.trimmedMessages = ForceChat.lines(Minecraft.getInstance().font,
			Math.max(1, (int) (getWidth() / Math.max(0.01, getScale()))));
		this.chatScrollbarPos = Math.min(ForceChat.scroll(), Math.max(0, this.trimmedMessages.size() - getLinesPerPage()));
		this.newMessageSinceScroll = ForceChat.unread();
		this.skyzh$usingForceView = true;
		try { draw.run(); }
		finally {
			ForceChat.scrolled(this.chatScrollbarPos, this.newMessageSinceScroll);
			this.trimmedMessages = previous;
			this.chatScrollbarPos = previousScroll;
			this.newMessageSinceScroll = previousUnread;
			this.skyzh$usingForceView = false;
		}
	}

	@Inject(method = "addMessageToQueue", at = @At("HEAD"), require = 0)
	private void skyzh$rememberLocal(GuiMessage message, CallbackInfo info) { ForceChat.message(message); }

	@Inject(method = "clearMessages", at = @At("TAIL"), require = 0)
	private void skyzh$clearSource(boolean history, CallbackInfo info) { ForceChat.clear(); }

	@WrapMethod(method = "addMessageToDisplayQueue", require = 0)
	private void skyzh$vanillaQueue(GuiMessage message, Operation<Void> original) {
		skyzh$vanillaUpdate(() -> original.call(message));
	}

	@WrapMethod(method = "rescaleChat", require = 0)
	private void skyzh$vanillaResize(Operation<Void> original) {
		ForceChat.invalidateLayout();
		skyzh$vanillaUpdate(() -> original.call());
	}

	@Unique private void skyzh$vanillaUpdate(Runnable update) {
		boolean previous = this.skyzh$updatingVanilla;
		this.skyzh$updatingVanilla = true;
		try { update.run(); }
		finally { this.skyzh$updatingVanilla = previous; }
	}

	@Inject(method = "resetChatScroll", at = @At("HEAD"), require = 0)
	private void skyzh$resetSourceScroll(CallbackInfo info) {
		if (!this.skyzh$updatingVanilla) ForceChat.resetScroll();
	}

	@Inject(method = "createDeletedMarker", at = @At("RETURN"), require = 0)
	private static void skyzh$deletedSource(GuiMessage message, CallbackInfoReturnable<GuiMessage> info) {
		ForceChat.deleted(message.signature(), info.getReturnValue());
	}
}
