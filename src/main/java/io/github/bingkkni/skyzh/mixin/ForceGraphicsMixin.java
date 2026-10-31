package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.hook.ForceSidebar;
import io.github.bingkkni.skyzh.hook.PriorityText;
import io.github.bingkkni.skyzh.text.TooltipTranslator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import io.github.bingkkni.skyzh.platform.ClientTooltip;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** High/Force protect known translations at vanilla text and tooltip entry points. */
@Mixin(value = GuiGraphicsExtractor.class, priority = Integer.MAX_VALUE)
public abstract class ForceGraphicsMixin {
	@Shadow @Final private GuiRenderState guiRenderState;
	@Shadow @Final private GuiGraphicsExtractor.ScissorStack scissorStack;
	@Shadow private Runnable deferredTooltip;
	/**
	 * The translator's cached list for the last tooltip, and whether it differed from its source.
	 * Static: vanilla builds a new extractor every frame, and only the render thread draws tooltips.
	 */
	@Unique private static List<Component> skyzh$presented;
	@Unique private static boolean skyzh$untouched;

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceText(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo info) {
		if (PriorityText.active() && skyzh$draw(font, text, x, y, color, shadow)) info.cancel();
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceComponent(Font font, Component text, int x, int y, int color, boolean shadow, CallbackInfo info) {
		if (PriorityText.active() && skyzh$draw(font, text.getVisualOrderText(), x, y, color, shadow)) info.cancel();
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceString(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfo info) {
		if (text != null && PriorityText.active()
			&& skyzh$draw(font, Component.literal(text).getVisualOrderText(), x, y, color, shadow)) info.cancel();
	}

	private boolean skyzh$draw(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		FormattedCharSequence translated = PriorityText.resolve(text);
		if (translated == null) return false;
		if (ARGB.alpha(color) != 0) {
			var graphics = (GuiGraphicsExtractor) (Object) this;
			this.guiRenderState.addText(new GuiTextRenderState(font, translated, new Matrix3x2f(graphics.pose()),
				x, y, color, 0, shadow, false, this.scissorStack.peek()));
		}
		return true;
	}

	@Inject(method = "setTooltipForNextFrame", at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$skipHiddenSidebarTooltip(CallbackInfo info) {
		if (ForceSidebar.suppressCustomDraw()) info.cancel();
	}

	@Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void skyzh$forceItem(Font font, ItemStack stack, int x, int y, CallbackInfo info) {
		if (ForceSidebar.suppressCustomDraw()) { info.cancel(); return; }
		if (!PriorityText.active()) return;
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		if (name == null) name = stack.get(DataComponents.ITEM_NAME);
		var lore = stack.get(DataComponents.LORE);
		// Only server-style items; vanilla items and unrelated mod tooltips retain their own path.
		if (name == null || lore == null) return;
		List<Component> source = new ArrayList<>();
		source.add(name);
		source.addAll(lore.lines());
		List<Component> lines = TooltipTranslator.translate(font, source);
		// The same cached list again means lines that flatten to the same text: the answer is unchanged.
		if (lines != skyzh$presented) {
			skyzh$presented = lines;
			skyzh$untouched = source.stream().map(io.github.bingkkni.skyzh.text.StyledText::of).toList()
				.equals(lines.stream().map(io.github.bingkkni.skyzh.text.StyledText::of).toList());
		}
		if (skyzh$untouched) return;
		List<ClientTooltipComponent> translated = lines.stream()
			.map(PriorityText::protect).map(ClientTooltipComponent::create).toList();
		var graphics = (GuiGraphicsExtractor) (Object) this;
		var style = stack.get(DataComponents.TOOLTIP_STYLE);
		this.deferredTooltip = () -> ClientTooltip.draw(graphics, font, translated, x, y, style);
		info.cancel();
	}
}
