package cellbreak.test.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;

/**
 * Dev-only: a remote-controlled client. Reads commands from client_cmds.txt in the run
 * directory (one per line, appended over time) so a headless test can take screenshots.
 */
public class CellbreakTestClient implements ClientModInitializer {
	private static final Path COMMANDS = Path.of("client_cmds.txt");
	private int processed;
	private boolean connected;
	private int wait;

	@Override
	public void onInitializeClient() {
		try {
			Files.writeString(COMMANDS, "");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(MinecraftClient client) {
		if (!connected && client.currentScreen instanceof TitleScreen) {
			connected = true;
			ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse("localhost:25565"),
					new ServerInfo("test", "localhost:25565", ServerInfo.ServerType.OTHER), false, null);
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(COMMANDS);
		} catch (IOException e) {
			return;
		}
		if (processed >= lines.size()) {
			return;
		}
		String line = lines.get(processed++).trim();
		System.out.println("[testclient] " + line);
		String[] a = line.split(" ", 2);
		switch (a[0]) {
			case "shot" -> ScreenshotRecorder.saveScreenshot(client.runDirectory, a[1] + ".png", client.getFramebuffer(),
					text -> System.out.println("[testclient] " + text.getString()));
			case "look" -> {
				String[] v = a[1].split(" ");
				client.player.setYaw(Float.parseFloat(v[0]));
				client.player.setPitch(Float.parseFloat(v[1]));
			}
			case "cmd" -> client.player.networkHandler.sendChatCommand(a[1]);
			case "hud" -> client.options.hudHidden = a[1].equals("off");
			case "view" -> client.options.setPerspective(switch (a[1]) {
				case "back" -> Perspective.THIRD_PERSON_BACK;
				case "front" -> Perspective.THIRD_PERSON_FRONT;
				default -> Perspective.FIRST_PERSON;
			});
			case "slot" -> client.player.getInventory().selectedSlot = Integer.parseInt(a[1]);
			case "wait" -> wait = Integer.parseInt(a[1]);
			default -> System.out.println("[testclient] unknown command");
		}
	}
}
