package cellbreak.client;

import cellbreak.net.GuidePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/** The routine arrow at the top of the screen: which way to walk, where to and how far. */
public final class GuideHud {
	private static GuidePayload guide = GuidePayload.NONE;
	private static long received;
	/** Smoothed arrow angle (degrees, 0 = straight ahead). */
	private static float shownAngle;
	static boolean enabled = true;

	private GuideHud() {
	}

	public static void receive(GuidePayload payload) {
		guide = payload;
		received = System.currentTimeMillis();
	}

	public static void render(DrawContext ctx, RenderTickCounter counter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!enabled || guide.label().isEmpty() || mc.player == null || mc.options.hudHidden
				|| System.currentTimeMillis() - received > 3000) {
			return;
		}
		Vec3d eye = mc.player.getPos();
		double nx = guide.nextX() - eye.x;
		double nz = guide.nextZ() - eye.z;
		// Close to the step point: point at the goal until the next update.
		if (nx * nx + nz * nz < 1.5 * 1.5) {
			nx = guide.goalX() - eye.x;
			nz = guide.goalZ() - eye.z;
		}
		float targetYaw = (float) Math.toDegrees(Math.atan2(-nx, nz));
		float angle = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw(counter.getTickDelta(true)));
		shownAngle += MathHelper.wrapDegrees(angle - shownAngle) * 0.35F;

		int cx = ctx.getScaledWindowWidth() / 2;
		int cy = 36;
		MatrixStack m = ctx.getMatrices();
		m.push();
		m.translate(cx, cy, 0);
		m.scale(1.5F, 1.5F, 1);
		m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(shownAngle));
		arrow(ctx, 0xC0000000, 1);
		arrow(ctx, 0xFFFFC832, 0);
		m.pop();

		double dx = guide.goalX() - eye.x;
		double dy = guide.goalY() - eye.y;
		double dz = guide.goalZ() - eye.z;
		int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		String text = guide.label() + "  " + dist + " м" + (dy > 2.5 ? "  ▲ выше" : dy < -2.5 ? "  ▼ ниже" : "");
		ctx.drawCenteredTextWithShadow(mc.textRenderer, text, cx, cy + 20, 0xFFFFE08A);
	}

	/** An arrow pointing up, centred on the origin; grow: outline thickness. */
	private static void arrow(DrawContext ctx, int color, int grow) {
		// Shaft.
		ctx.fill(-2 - grow, -2, 2 + grow, 10 + grow, color);
		// Head: rows getting wider downwards.
		for (int i = 0; i <= 7; i++) {
			int y = -11 + i;
			int w = i + grow;
			ctx.fill(-w, y - grow, w + 1, y + 1, color);
		}
	}
}
