package cellbreak.client;

import cellbreak.net.HudPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * The round's status line, one line above the action bar: the action bar itself is left to the
 * messages (training and work progress, warnings), which used to overwrite the status.
 */
public final class StatusLine {
	private static Text text = Text.empty();
	private static long received;

	private StatusLine() {
	}

	public static void receive(HudPayload payload) {
		text = payload.text();
		received = System.currentTimeMillis();
	}

	public static void render(DrawContext ctx, RenderTickCounter counter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || text.getString().isEmpty() || System.currentTimeMillis() - received > 3000) {
			return;
		}
		var tr = mc.textRenderer;
		int w = tr.getWidth(text);
		int x = (ctx.getScaledWindowWidth() - w) / 2;
		// The action bar's text sits at height - 72; this line goes right above it.
		int y = ctx.getScaledWindowHeight() - 86;
		ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0x60000000);
		ctx.drawTextWithShadow(tr, text, x, y, 0xFFFFFFFF);
	}
}
