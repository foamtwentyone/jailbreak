package cellbreak.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import cellbreak.net.SearchlightPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Draws the snipers' searchlights as soft see-through cones of light: from the lamp on the tower
 * down to a round pool of light on the ground. They glide smoothly between server updates.
 */
public final class SearchlightRenderer {
	private static final int SEGMENTS = 32;
	/** Server updates come every 4 ticks: each one is reached over that time. */
	private static final float GLIDE_TICKS = 4.0F;

	private static final class State {
		SearchlightPayload.Beam from;
		SearchlightPayload.Beam to;
		long since;
	}

	private static final List<State> beams = new ArrayList<>();
	private static long lastPacket;

	private SearchlightRenderer() {
	}

	public static void receive(SearchlightPayload payload) {
		MinecraftClient client = MinecraftClient.getInstance();
		long now = client.world == null ? 0 : client.world.getTime();
		lastPacket = now;
		List<SearchlightPayload.Beam> in = payload.beams();
		while (beams.size() > in.size()) {
			beams.remove(beams.size() - 1);
		}
		for (int i = 0; i < in.size(); i++) {
			State s;
			if (i < beams.size()) {
				s = beams.get(i);
				s.from = current(s, now, 0);
			} else {
				s = new State();
				s.from = in.get(i);
				beams.add(s);
			}
			s.to = in.get(i);
			s.since = now;
		}
	}

	private static SearchlightPayload.Beam current(State s, long now, float tickDelta) {
		float t = MathHelper.clamp((now - s.since + tickDelta) / GLIDE_TICKS, 0, 1);
		SearchlightPayload.Beam a = s.from;
		SearchlightPayload.Beam b = s.to;
		return new SearchlightPayload.Beam(MathHelper.lerp(t, a.fromX(), b.fromX()), MathHelper.lerp(t, a.fromY(), b.fromY()),
				MathHelper.lerp(t, a.fromZ(), b.fromZ()), MathHelper.lerp(t, a.toX(), b.toX()), MathHelper.lerp(t, a.toY(), b.toY()),
				MathHelper.lerp(t, a.toZ(), b.toZ()), MathHelper.lerp(t, a.radius(), b.radius()));
	}

	public static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (beams.isEmpty() || client.world == null) {
			return;
		}
		long now = client.world.getTime();
		if (now - lastPacket > 40 || now < lastPacket) {
			// No news for two seconds (left the server, lights went out): forget them.
			beams.clear();
			return;
		}
		float tickDelta = context.tickCounter().getTickDelta(false);
		Vec3d cam = context.camera().getPos();
		// The view rotation is already in the render system's model-view matrix: only move to the camera.
		MatrixStack matrices = new MatrixStack();
		matrices.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f m = matrices.peek().getPositionMatrix();
		VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer buf = immediate.getBuffer(RenderLayer.getLightning());
		for (State s : beams) {
			cone(buf, m, current(s, now, tickDelta));
		}
		immediate.draw(RenderLayer.getLightning());
	}

	private static void cone(VertexConsumer buf, Matrix4f m, SearchlightPayload.Beam b) {
		Vector3f apex = new Vector3f(b.fromX(), b.fromY(), b.fromZ());
		Vector3f center = new Vector3f(b.toX(), b.toY() + 0.06F, b.toZ());
		float r = b.radius();
		// Warm white light, bright at the lamp and fading towards the ground.
		float red = 1.0F, green = 0.96F, blue = 0.82F;
		float aTop = 0.22F, aBottom = 0.035F;
		for (int i = 0; i < SEGMENTS; i++) {
			double t0 = Math.PI * 2 * i / SEGMENTS;
			double t1 = Math.PI * 2 * (i + 1) / SEGMENTS;
			float x0 = center.x + (float) Math.cos(t0) * r, z0 = center.z + (float) Math.sin(t0) * r;
			float x1 = center.x + (float) Math.cos(t1) * r, z1 = center.z + (float) Math.sin(t1) * r;
			// The side of the cone, both ways round so it shows from inside and outside.
			quad(buf, m, apex.x, apex.y, apex.z, aTop, apex.x, apex.y, apex.z, aTop, x0, center.y, z0, aBottom, x1, center.y, z1,
					aBottom, red, green, blue);
			quad(buf, m, apex.x, apex.y, apex.z, aTop, apex.x, apex.y, apex.z, aTop, x1, center.y, z1, aBottom, x0, center.y, z0,
					aBottom, red, green, blue);
			// The pool of light on the ground: brightest in the middle.
			quad(buf, m, center.x, center.y, center.z, 0.16F, center.x, center.y, center.z, 0.16F, x1, center.y, z1, 0.02F, x0,
					center.y, z0, 0.02F, red, green, blue);
			quad(buf, m, center.x, center.y, center.z, 0.16F, center.x, center.y, center.z, 0.16F, x0, center.y, z0, 0.02F, x1,
					center.y, z1, 0.02F, red, green, blue);
		}
	}

	private static void quad(VertexConsumer buf, Matrix4f m, float x1, float y1, float z1, float a1, float x2, float y2, float z2,
			float a2, float x3, float y3, float z3, float a3, float x4, float y4, float z4, float a4, float r, float g, float b) {
		buf.vertex(m, x1, y1, z1).color(r, g, b, a1);
		buf.vertex(m, x2, y2, z2).color(r, g, b, a2);
		buf.vertex(m, x3, y3, z3).color(r, g, b, a3);
		buf.vertex(m, x4, y4, z4).color(r, g, b, a4);
	}
}
