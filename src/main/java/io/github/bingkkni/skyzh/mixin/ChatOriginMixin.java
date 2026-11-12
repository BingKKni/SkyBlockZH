package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.compat.ChatCompatibility;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep packet provenance beside vanilla history without changing its message or identity. */
@Mixin(GuiMessage.class)
public abstract class ChatOriginMixin implements ChatCompatibility.Origin {
	@Unique private Component skyzh$serverOriginal;

	@Inject(method = "<init>(ILnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("RETURN"), require = 0)
	private void skyzh$rememberOrigin(int time, Component text, MessageSignature signature,
		GuiMessageSource source, GuiMessageTag tag, CallbackInfo info) {
		if (source == GuiMessageSource.SYSTEM_SERVER)
			this.skyzh$serverOriginal = ChatCompatibility.serverOriginal();
	}

	@Override public Component skyzh$serverOriginal() { return this.skyzh$serverOriginal; }
}
