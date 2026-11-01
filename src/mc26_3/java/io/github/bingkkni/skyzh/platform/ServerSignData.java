package io.github.bingkkni.skyzh.platform;

import io.github.bingkkni.skyzh.capture.TextCapture;
import java.util.List;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.BlockEntityTypes;

/** Minecraft's sign codec and chunk accessors changed in 26.3. */
public final class ServerSignData {
	private ServerSignData() {}
	public static Codec<SignText> codec() { return SignText.CODEC; }
	public static List<Component> lines(SignText text) {
		return lines(text, false);
	}
	public static List<Component> lines(SignText text, boolean filtered) {
		return text.getMessages(filtered);
	}
	public static boolean isSign(BlockEntityType<?> type) {
		return type == BlockEntityTypes.SIGN || type == BlockEntityTypes.HANGING_SIGN;
	}
	public static void chunk(ClientboundLevelChunkWithLightPacket packet) {
		packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), TextCapture::sign);
	}
}
