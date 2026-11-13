package io.github.bingkkni.skyzh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Real Knot/MixinExtras transformation against vanilla and signature-only optional renderers. */
public final class RenderMixinHarness {
	public static void main(String[] args) throws Exception {
		System.setProperty("fabric.development", "true");
		System.setProperty("mixin.debug.export", "true");
		System.setProperty("mixin.debug.export.filter", "**");
		Path game = Files.createTempDirectory(Path.of("."), "render-mixin-check-");
		Path exports = Path.of(".mixin.out");
		try {
			Knot knot = new Knot(EnvType.CLIENT);
			ClassLoader loader = knot.init(new String[] {"--gameDir", game.toAbsolutePath().toString()});
			check(loader, "net.minecraft.client.Minecraft", "io/github/bingkkni/skyzh/hook/PriorityText", "withFrame");
			check(loader, "net.minecraft.client.gui.Font", "io/github/bingkkni/skyzh/hook/PriorityText", "mayResolve");
			check(loader, "net.minecraft.client.gui.GuiGraphicsExtractor", "io/github/bingkkni/skyzh/hook/PriorityText", "mayResolve");
			check(loader, "net.minecraft.client.renderer.blockentity.AbstractSignRenderer",
				"io/github/bingkkni/skyzh/text/SignDisplayCache$Owner", "skyzh$displayCache");
			Class<?> sign = Class.forName("net.minecraft.world.level.block.entity.SignText", false, loader);
			if (!loader.loadClass("io.github.bingkkni.skyzh.text.SignDisplayCache$Owner").isAssignableFrom(sign))
				throw new AssertionError("SignText instance cache mixin was not applied");
			check(loader, "me.owdding.customscoreboard.core.CustomScoreboardRenderer",
				"io/github/bingkkni/skyzh/hook/ForceSidebar", "withRenderer");
			check(loader, "at.hannibal2.skyhanni.features.gui.customscoreboard.CustomScoreboard",
				"io/github/bingkkni/skyzh/hook/ForceSidebar", "withRenderer");
			check(loader, "com.github.noamm9.features.impl.visual.Scoreboard$hud$1",
				"io/github/bingkkni/skyzh/hook/ForceSidebar", "withRenderer", "withRendererResult");
			System.out.println("Render mixin composition passed (vanilla + optional renderer signatures)");
		} finally {
			removeTree(game);
			removeTree(exports);
		}
	}

	private static void check(ClassLoader loader, String target, String hook, String... methods) throws Exception {
		Class.forName(target, false, loader);
		Path exported = Path.of(".mixin.out/class/" + target.replace('.', '/') + ".class");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(exported)).accept(node, 0);
		for (String expected : methods) {
			boolean found = false;
			for (var method : node.methods) for (var instruction : method.instructions)
				if (instruction instanceof MethodInsnNode call && call.owner.equals(hook) && call.name.equals(expected))
					found = true;
			if (!found) throw new AssertionError(target + " did not receive " + hook + "." + expected);
		}
		System.out.println("Woven render hook: " + target);
	}

	private static void removeTree(Path root) throws Exception {
		if (!Files.exists(root)) return;
		try (var walk = Files.walk(root)) {
			for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
		}
	}
}
