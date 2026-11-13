package io.github.bingkkni.skyzh.text;

import io.github.bingkkni.skyzh.SkyZHConfig;
import io.github.bingkkni.skyzh.platform.ServerSignData;
import java.util.function.Function;
import java.lang.ref.WeakReference;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;

/** One immutable SignText's display-only cache. Null is a cached decision to use vanilla. */
public final class SignDisplayCache {
	public interface Owner {
		SignDisplayCache skyzh$displayCache();
	}

	// Render-thread epoch: offscreen signs must not keep old corpus/language generations alive.
	private static WeakReference<TranslationIndex> corpus = new WeakReference<>(null);
	private static WeakReference<Language> language = new WeakReference<>(null);
	private static int generation = -1, names;
	private static boolean skyBlockName;
	private static long epoch;

	private long seenEpoch = -1;
	private int width;
	private boolean filtered;
	private Object font;
	private java.util.List<Component> rows;
	private FormattedCharSequence[] rendered;

	private static long currentEpoch() {
		TranslationIndex current = Translator.index();
		Language currentLanguage = Language.getInstance(); // Replaced on language/font resource reload.
		int currentGeneration = SkyZHConfig.generation(), currentNames = ItemNamePolicy.state();
		boolean currentName = SkyZHConfig.get().translateSkyBlockName;
		if (corpus.get() != current || language.get() != currentLanguage || generation != currentGeneration
			|| names != currentNames || skyBlockName != currentName) {
			corpus = new WeakReference<>(current);
			language = new WeakReference<>(currentLanguage);
			generation = currentGeneration;
			names = currentNames;
			skyBlockName = currentName;
			epoch++;
		}
		return epoch;
	}

	/** Render thread only. The caller gates translation; vanilla renderMessages stays untouched. */
	public FormattedCharSequence[] get(SignText sign, boolean filtered, int width, Object font,
		Function<Component, FormattedCharSequence> prepare) {
		long epoch = currentEpoch();
		if (this.seenEpoch != epoch || this.filtered != filtered) {
			this.seenEpoch = epoch;
			this.filtered = filtered;
			this.rows = null;
			this.rendered = null;
			Component source = DocumentTranslation.join(ServerSignData.lines(sign, filtered));
			Component translated = DocumentTranslation.available(source, Surface.SIGN);
			if (!StyledText.of(source).equals(StyledText.of(translated))) {
				var rows = DocumentTranslation.lines(translated);
				// An external template must not erase or silently truncate extra rows.
				if (rows.size() == 4) this.rows = rows;
			}
		}
		if (this.rows == null) return null;
		if (this.rendered != null && this.width == width && this.font == font) return this.rendered;
		this.width = width;
		this.font = font;
		FormattedCharSequence[] result = new FormattedCharSequence[4];
		for (int i = 0; i < result.length; i++) result[i] = prepare.apply(this.rows.get(i));
		return this.rendered = result;
	}
}
