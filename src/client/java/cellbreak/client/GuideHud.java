package cellbreak.client;

import cellbreak.net.GuidePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The routine arrow at the top of the screen: which way to walk, where to and how far. */
public final class GuideHud {
	private static final net.minecraft.util.Identifier TEXTURE = cellbreak.CbContent.id("textures/gui/guide_arrow.png");
	/** The arrow in 32 pre-drawn directions (pixel art, like the compass), 24 pixels each. */
	private static final int ARROW = 24;
	private static final int FRAMES = 32;
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

		double dx = guide.goalX() - eye.x;
		double dy = guide.goalY() - eye.y;
		double dz = guide.goalZ() - eye.z;
		int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		String title = guide.label();
		String sub = dist + " м" + (dy > 2.5 ? " · этажом выше" : dy < -2.5 ? " · этажом ниже" : "");

		// A small dark panel at the top: the arrow on the left, where to and how far on the right.
		var tr = mc.textRenderer;
		int textW = Math.max(tr.getWidth(title), tr.getWidth(sub));
		int w = 5 + ARROW + 5 + textW + 7;
		int h = ARROW + 6;
		int x = (ctx.getScaledWindowWidth() - w) / 2;
		int y = 6;
		ctx.fill(x + 1, y, x + w - 1, y + h, 0xC0101418);
		ctx.fill(x, y + 1, x + w, y + h - 1, 0xC0101418);
		ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0x30FFFFFF);
		ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x40000000);

		int frame = Math.floorMod(Math.round(shownAngle / (360F / FRAMES)), FRAMES);
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		ctx.drawTexture(TEXTURE, x + 5, y + 3, (frame % 8) * ARROW, (frame / 8) * ARROW, ARROW, ARROW, ARROW * 8, ARROW * 4);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		ctx.drawTextWithShadow(tr, title, x + 5 + ARROW + 5, y + 5, 0xFFFFD866);
		ctx.drawTextWithShadow(tr, sub, x + 5 + ARROW + 5, y + 16, 0xFFB8B8B8);
	}
}
