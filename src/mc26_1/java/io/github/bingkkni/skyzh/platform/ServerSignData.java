package io.github.bingkkni.skyzh.platform;

import io.github.bingkkni.skyzh.capture.TextCapture;
import java.util.List;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignText;

/** Minecraft's sign codec and chunk accessors changed in 26.3. */
public final class ServerSignData {
	private ServerSignData() {}
	public static Codec<SignText> codec() { return SignText.DIRECT_CODEC; }
	public static List<Component> lines(SignText text) {
		return lines(text, false);
	}
	public static List<Component> lines(SignText text, boolean filtered) {
		return List.of(text.getMessages(filtered));
	}
	public static boolean isSign(BlockEntityType<?> type) {
		return type == BlockEntityType.SIGN || type == BlockEntityType.HANGING_SIGN;
	}
	public static void chunk(ClientboundLevelChunkWithLightPacket packet) {
		packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept(TextCapture::sign);
	}
}
