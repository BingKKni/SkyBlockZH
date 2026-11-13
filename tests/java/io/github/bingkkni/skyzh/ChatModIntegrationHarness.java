package io.github.bingkkni.skyzh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/** Optional real-loader check. Supply an isolated game directory containing the chat Mod JARs. */
public final class ChatModIntegrationHarness {
	public static void main(String[] args) throws Exception {
		if (args.length < 3) throw new IllegalArgumentException("game directory, harness classes and corpus required");
		System.setProperty("fabric.development", System.getProperty("fabric.development", "true"));
		System.setProperty("mixin.debug.export", "true");
		System.setProperty("mixin.debug.export.filter", "**.C*");
		Path exported = Path.of(".mixin.out/class/net/minecraft/client/gui/components/ChatComponent.class");
		Path sidebarExport = Path.of(".mixin.out/class/me/owdding/customscoreboard/core/CustomScoreboardRenderer.class");
		Files.deleteIfExists(exported);
		Files.deleteIfExists(sidebarExport);
		Knot knot = new Knot(EnvType.CLIENT);
		ClassLoader loader = knot.init(new String[] {"--gameDir", args[0]});
		try {
			Class.forName("me.owdding.customscoreboard.core.CustomScoreboardRenderer", false, loader);
			byte[] transformed = Files.readAllBytes(sidebarExport);
			verifyScope(transformed, "updateDisplay()V", "io/github/bingkkni/skyzh/hook/SidebarCompatibility");
			verifyScope(transformed, "onRender(Ltech/thatgravyboat/skyblockapi/api/events/render/RenderHudEvent;)V",
				"io/github/bingkkni/skyzh/hook/SidebarCompatibility");
			Files.deleteIfExists(sidebarExport);
		} catch (ClassNotFoundException absent) { /* Optional Mod not installed. */ }
		knot.addToClassPath(Path.of(args[1]));
		for (String name : new String[] {"net.minecraft.client.gui.components.ChatComponent",
			"net.minecraft.client.multiplayer.chat.GuiMessage", "net.minecraft.network.chat.MutableComponent",
			"net.minecraft.world.level.block.entity.SignText", "net.minecraft.client.renderer.blockentity.AbstractSignRenderer",
			"net.minecraft.client.Minecraft", "net.minecraft.client.gui.Font", "net.minecraft.client.gui.GuiGraphicsExtractor"}) {
			Class.forName(name, false, loader);
			System.out.println("Mixin composition loaded: " + name);
		}
		if (!loader.loadClass("io.github.bingkkni.skyzh.text.SignDisplayCache$Owner")
			.isAssignableFrom(loader.loadClass("net.minecraft.world.level.block.entity.SignText")))
			throw new AssertionError("SignText instance cache mixin was not applied");
		try { verifyScope(Files.readAllBytes(exported),
			"addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
			"io/github/bingkkni/skyzh/compat/ChatCompatibility"); }
		finally { Files.deleteIfExists(exported); }
		Class<?> origin = loader.loadClass("io.github.bingkkni.skyzh.compat.ChatCompatibility$Origin");
		if (!origin.isAssignableFrom(loader.loadClass("net.minecraft.client.multiplayer.chat.GuiMessage")))
			throw new AssertionError("SkyZH mixins were not applied");
		if (args.length < 4 || !args[3].equals("composition-only"))
			loader.loadClass("io.github.bingkkni.skyzh.ChatCompatibilityHarness").getMethod("woven", String.class).invoke(null, args[2]);
		System.exit(0);
	}

	/** Follow real transformed wrapper calls, including MixinExtras' invokedynamic operation handles. */
	private static void verifyScope(byte[] transformed, String entry, String scopeOwner) {
		ClassNode node = new ClassNode();
		new ClassReader(transformed).accept(node, 0);
		ArrayDeque<String> todo = new ArrayDeque<>();
		Set<String> visited = new HashSet<>();
		todo.add(entry);
		while (!todo.isEmpty()) {
			String key = todo.removeFirst();
			if (!visited.add(key)) continue;
			for (var method : node.methods) {
				if (!(method.name + method.desc).equals(key)) continue;
				for (var instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode call) {
						if (call.owner.equals(scopeOwner) && call.name.equals("display")) {
							System.out.println("Transformed " + entry + " reaches SkyZH display scope");
							return;
						}
						if (call.owner.equals(node.name)) todo.add(call.name + call.desc);
					} else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
						for (Object arg : dynamic.bsmArgs)
							if (arg instanceof Handle handle && handle.getOwner().equals(node.name))
								todo.add(handle.getName() + handle.getDesc());
					}
				}
			}
		}
		throw new AssertionError(entry + " does not reach the SkyZH display scope after Mod mixin composition");
	}
}
