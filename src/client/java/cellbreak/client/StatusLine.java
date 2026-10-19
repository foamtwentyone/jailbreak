package cellbreak.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cellbreak.net.HudPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * The round's status line stays where it always was (the action bar's place above the hotbar);
 * passing messages (training and work progress, warnings), which used to overwrite it, are
 * caught and shown under the crosshair instead, fading out. A message with a count ("4/10")
 * gets a progress bar.
 */
public final class StatusLine {
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
	private static final long MESSAGE_MS = 2500;
	private static final long FADE_MS = 500;

	private static Text status = Text.empty();
	private static long statusAt;
	private static Text message = Text.empty();
	private static long messageAt;

	private StatusLine() {
	}

	public static void receive(HudPayload payload) {
		status = payload.text();
		statusAt = System.currentTimeMillis();
	}

	/** An action bar message from the server: while the status line is up, it goes under the crosshair. */
	public static boolean allowOverlay(Text text) {
		if (!statusShown()) {
			return true;
		}
		message = text;
		messageAt = System.currentTimeMillis();
		return false;
	}

	private static boolean statusShown() {
		return !status.getString().isEmpty() && System.currentTimeMillis() - statusAt < 3000;
	}

	public static void render(DrawContext ctx, RenderTickCounter counter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		var tr = mc.textRenderer;
		int width = ctx.getScaledWindowWidth();
		int height = ctx.getScaledWindowHeight();
		if (statusShown()) {
			// Exactly where the vanilla action bar text goes.
			ctx.drawTextWithShadow(tr, status, (width - tr.getWidth(status)) / 2, height - 72, 0xFFFFFFFF);
		}
		long age = System.currentTimeMillis() - messageAt;
		if (message.getString().isEmpty() || age > MESSAGE_MS) {
			return;
		}
		float alpha = age < MESSAGE_MS - FADE_MS ? 1F : (MESSAGE_MS - age) / (float) FADE_MS;
		int a = MathHelper.clamp((int) (alpha * 255), 8, 255);
		int y = height / 2 + 22;
		int w = tr.getWidth(message);
		ctx.fill((width - w) / 2 - 4, y - 3, (width + w) / 2 + 4, y + 11, (a * 96 / 255) << 24);
		ctx.drawTextWithShadow(tr, message, (width - w) / 2, y, 0xFFFFFF | a << 24);
		Matcher m = COUNT.matcher(message.getString());
		if (m.find()) {
			int done = Integer.parseInt(m.group(1));
			int total = Integer.parseInt(m.group(2));
			if (total > 0 && done <= total) {
				int bw = Math.max(60, w);
				int bx = (width - bw) / 2;
				int by = y + 13;
				ctx.fill(bx - 1, by - 1, bx + bw + 1, by + 4, (a * 160 / 255) << 24);
				ctx.fill(bx, by, bx + bw * done / total, by + 3, 0xFFC832 | a << 24);
			}
		}
	}
}
