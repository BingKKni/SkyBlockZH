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
			StyledText styled = StyledText.of(Component.literal(parts[1]));
			Translator.Located located = Translator.locate(styled, surface);

			System.out.println("in      = " + parts[1]);

			if (!located.matched()) {
				// An enchantment line is several records rather than one, so no single record answers
				// for it and the lookup above rightly says nothing. It is also the line most worth
				// probing — which of a weapon's five enchantments have Chinese yet is exactly the
				// question this tool gets asked.
				Component list = Translator.translateList(Component.literal(parts[1]), surface);

				System.out.println(list == null
					? "record  = (没有记录应答)"
					: "records = 附魔列表，逐段查表\nrender  = " + list.getString());
				System.out.println();
				continue;
			}

			Matcher match = located.match();
			Component source = Component.literal(parts[1]);
			// The tab list is drawn through translateRow, which puts the value half through the term
			// table after the record has had the label. Anywhere else a line is a line.
			Component drawn = surface == Surface.TABLIST
				? Translator.translateRow(source, surface)
				: Translator.translateLine(source, surface);

			System.out.println("record  = " + located.entry().sourceFile() + "#" + located.entry().id());
			System.out.println("loses   = " + located.entry().losesColour(located.core(), match));
			System.out.println("render  = " + drawn.getString());
			System.out.println();
		}
	}
}
