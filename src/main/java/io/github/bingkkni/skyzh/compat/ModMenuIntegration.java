package io.github.bingkkni.skyzh.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.bingkkni.skyzh.gui.SkyZHConfigScreen;

/**
 * Compiled against Mod Menu but never required by it: the entrypoint is only ever constructed when
 * Mod Menu is present to construct it, so a player without it loads a mod that never touches this
 * class. Settings for those players live in {@code config/skyzh.json}.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SkyZHConfigScreen::new;
	}
}
