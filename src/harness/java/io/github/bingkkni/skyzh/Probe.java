package io.github.bingkkni.skyzh;

import io.github.bingkkni.skyzh.text.StyledText;
import io.github.bingkkni.skyzh.text.Surface;
import io.github.bingkkni.skyzh.text.TranslationIndex;
import io.github.bingkkni.skyzh.text.TranslationLoader;
import io.github.bingkkni.skyzh.text.Translator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import net.minecraft.network.chat.Component;

/**
 * Prints what one hand-written SkyBlock line renders as, which record answered for it, and whether
 * that record flattens the line's colours.
 *
 * <p>A working tool rather than a check: {@code replayCapture} does this for lines a session
 * actually saw, and this does it for a line somebody is holding in their hand — the one thing you
 * need while working out where a {@code segments} array has to be split, and the one thing that is
 * otherwise a game launch away.
 *
 * <p>Reads its lines from a file, {@code <surface>\t<legacy text>} per line, because the interesting
 * lines are full of {@code §} codes and private-use glyphs and neither survives being typed at a
 * shell.
 */
public final class Probe {
	public static void main(String[] args) throws Exception {
		TranslationIndex index = TranslationLoader.compile(TranslationHarness.readCorpus(Path.of(args[0])));
		TranslationHarness.installIndex(index);

		for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}

			String[] parts = line.split("\t", 2);
			Surface surface = Surface.valueOf(parts[0]);
			Component source = Component.literal(parts[1]);
			StyledText styled = StyledText.of(source);
			Translator.Located located = Translator.locate(styled, surface);
			Component drawn = draw(source, surface);

			System.out.println("in      = " + parts[1]);

			if (!located.matched()) {
				// No whole-line record does not mean no translation: Tab rows can be answered
				// entirely by terms, action bars by widgets, and lore by an enchantment list.
				boolean changed = !StyledText.of(drawn).plain().equals(styled.plain());
				System.out.println(changed
					? "records = 渲染面专用路径（标签/数值、部件或附魔列表）"
					: "record  = (没有记录应答)");
				System.out.println("render  = " + drawn.getString());
				System.out.println();
				continue;
			}

			Matcher match = located.match();
			System.out.println("record  = " + located.entry().sourceFile() + "#" + located.entry().id());
			System.out.println("loses   = " + located.entry().losesColour(located.core(), match));
			System.out.println("render  = " + drawn.getString());
			System.out.println();
		}
	}

	/** The font-free render paths used in game, even when no single record answers the line. */
	static Component draw(Component source, Surface surface) {
		if (surface == Surface.TABLIST) {
			return Translator.translateRow(source, surface);
		}

		if (surface == Surface.ACTION_BAR) {
			return Translator.translateWidgets(source, surface);
		}

		Translator.Result line = Translator.translate(source, surface);

		if (!line.matched() && surface == Surface.ITEM) {
			Component list = Translator.translateList(source, surface);

			if (list != null) {
				return list;
			}
		}

		return line.padded();
	}
}
