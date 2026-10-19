package cellbreak.net;

import cellbreak.CbContent;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

/**
 * The round's status line (phase, suspicion, money, day). Drawn by the client on a line of its own
 * above the action bar, so the action bar stays free for training, work and other messages.
 * An empty text: no line.
 */
public record HudPayload(Text text) implements CustomPayload {
	public static final CustomPayload.Id<HudPayload> ID = new CustomPayload.Id<>(CbContent.id("hud"));
	public static final PacketCodec<RegistryByteBuf, HudPayload> CODEC = TextCodecs.REGISTRY_PACKET_CODEC.xmap(HudPayload::new,
			HudPayload::text);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
