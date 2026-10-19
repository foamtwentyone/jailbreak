package cellbreak.net;

import cellbreak.CbContent;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * The routine arrow (as in The Escapists): where to walk next on the way to the place the routine
 * wants you in, the place itself and what it is called. An empty label: no arrow.
 */
public record GuidePayload(String label, float nextX, float nextY, float nextZ, float goalX, float goalY, float goalZ)
		implements CustomPayload {
	public static final CustomPayload.Id<GuidePayload> ID = new CustomPayload.Id<>(CbContent.id("guide"));
	public static final PacketCodec<RegistryByteBuf, GuidePayload> CODEC = PacketCodec.of(GuidePayload::write, GuidePayload::read);
	public static final GuidePayload NONE = new GuidePayload("", 0, 0, 0, 0, 0, 0);

	private void write(RegistryByteBuf buf) {
		buf.writeString(label);
		buf.writeFloat(nextX);
		buf.writeFloat(nextY);
		buf.writeFloat(nextZ);
		buf.writeFloat(goalX);
		buf.writeFloat(goalY);
		buf.writeFloat(goalZ);
	}

	private static GuidePayload read(RegistryByteBuf buf) {
		return new GuidePayload(buf.readString(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
				buf.readFloat());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
