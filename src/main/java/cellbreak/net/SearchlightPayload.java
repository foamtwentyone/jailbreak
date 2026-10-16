package cellbreak.net;

import java.util.ArrayList;
import java.util.List;

import cellbreak.CbContent;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** The snipers' searchlights at night: where each lamp is and where its light falls. Empty: lights off. */
public record SearchlightPayload(List<Beam> beams) implements CustomPayload {
	public static final CustomPayload.Id<SearchlightPayload> ID = new CustomPayload.Id<>(CbContent.id("searchlights"));
	public static final PacketCodec<RegistryByteBuf, SearchlightPayload> CODEC = PacketCodec.of(SearchlightPayload::write,
			SearchlightPayload::read);

	public record Beam(float fromX, float fromY, float fromZ, float toX, float toY, float toZ, float radius) {
	}

	private void write(RegistryByteBuf buf) {
		buf.writeVarInt(beams.size());
		for (Beam b : beams) {
			buf.writeFloat(b.fromX());
			buf.writeFloat(b.fromY());
			buf.writeFloat(b.fromZ());
			buf.writeFloat(b.toX());
			buf.writeFloat(b.toY());
			buf.writeFloat(b.toZ());
			buf.writeFloat(b.radius());
		}
	}

	private static SearchlightPayload read(RegistryByteBuf buf) {
		int n = buf.readVarInt();
		List<Beam> beams = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			beams.add(new Beam(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat()));
		}
		return new SearchlightPayload(beams);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
