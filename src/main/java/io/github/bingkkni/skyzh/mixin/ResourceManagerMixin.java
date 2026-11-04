package io.github.bingkkni.skyzh.mixin;

import io.github.bingkkni.skyzh.ClientPacks;
import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds the mod's own assets and the optional legacy font to every client resource reload. */
@Mixin(ReloadableResourceManager.class)
public abstract class ResourceManagerMixin {
	@Shadow @Final private PackType type;

	@ModifyVariable(method = "createReload", at = @At("HEAD"), argsOnly = true, require = 0)
	private List<PackResources> skyzh$packs(List<PackResources> packs) {
		return this.type == PackType.CLIENT_RESOURCES ? ClientPacks.with(packs) : packs;
	}
}
