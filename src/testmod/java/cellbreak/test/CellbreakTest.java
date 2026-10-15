package cellbreak.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import cellbreak.game.PrisonLayout;
import cellbreak.game.PrisonState;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Dev-only: fake players driven by commands, used to test the game on a headless server. */
public class CellbreakTest implements ModInitializer {
	private static final Map<String, Bot> BOTS = new HashMap<>();

	private record Bot(ServerPlayerEntity player, FakeConnection connection, Deque<String> messages) {
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((d, r, e) -> register(d, r));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Bot bot : BOTS.values()) {
				if (!bot.player.isRemoved()) {
					bot.player.playerTick();
				}
				drain(bot);
			}
		});
	}

	private static void drain(Bot bot) {
		Object msg;
		while ((msg = bot.connection.channel().readOutbound()) != null) {
			String text = null;
			if (msg instanceof GameMessageS2CPacket p) {
				text = (p.overlay() ? "[hud] " : "[chat] ") + p.content().getString();
			} else if (msg instanceof OverlayMessageS2CPacket p) {
				text = "[hud] " + p.text().getString();
			} else if (msg instanceof TitleS2CPacket p) {
				text = "[title] " + p.text().getString();
			} else if (msg instanceof SubtitleS2CPacket p) {
				text = "[subtitle] " + p.text().getString();
			}
			if (text != null) {
				if (text.startsWith("[hud]") && !bot.messages.isEmpty() && bot.messages.peekLast().startsWith("[hud]")) {
					bot.messages.pollLast();
				}
				bot.messages.addLast(text);
				while (bot.messages.size() > 40) {
					bot.messages.pollFirst();
				}
			}
		}
	}

	private static void register(CommandDispatcher<ServerCommandSource> d,
			net.minecraft.command.CommandRegistryAccess registries) {
		d.register(CommandManager.literal("cbtest").requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.literal("bot").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(CellbreakTest::spawn))))
				.then(CommandManager.literal("tp").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("yaw", FloatArgumentType.floatArg())
										.then(CommandManager.argument("pitch", FloatArgumentType.floatArg()).executes(c -> {
											ServerPlayerEntity p = bot(c).player;
											BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
											p.teleport(p.getServerWorld(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
													FloatArgumentType.getFloat(c, "yaw"), FloatArgumentType.getFloat(c, "pitch"));
											p.setHeadYaw(FloatArgumentType.getFloat(c, "yaw"));
											return 1;
										}))))))
				.then(CommandManager.literal("hold").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registries)).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							p.setStackInHand(Hand.MAIN_HAND, ItemStackArgumentType.getItemStackArgument(c, "item")
									.createStack(1, false));
							return 1;
						}))))
				.then(CommandManager.literal("break").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
							boolean ok = p.interactionManager.tryBreakBlock(pos);
							reply(c, "break " + pos.toShortString() + " -> " + ok + ", now "
									+ Registries.BLOCK.getId(p.getServerWorld().getBlockState(pos).getBlock()));
							return 1;
						}))))
				.then(CommandManager.literal("delta").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
							ServerWorld w = p.getServerWorld();
							reply(c, "delta=" + w.getBlockState(pos).calcBlockBreakingDelta(p, w, pos)
									+ " restricted=" + p.isBlockBreakingRestricted(w, pos, p.interactionManager.getGameMode()));
							return 1;
						}))))
				.then(CommandManager.literal("key").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
							BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false);
							var result = UseBlockCallback.EVENT.invoker().interact(p, p.getServerWorld(), Hand.MAIN_HAND, hit);
							reply(c, "use block -> " + result);
							return 1;
						}))))
				.then(CommandManager.literal("cuff").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("target", StringArgumentType.word()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							ServerPlayerEntity t = BOTS.get(StringArgumentType.getString(c, "target")).player;
							var result = UseEntityCallback.EVENT.invoker().interact(p, p.getServerWorld(), Hand.MAIN_HAND, t, null);
							reply(c, "use entity -> " + result);
							return 1;
						}))))
				.then(CommandManager.literal("attack").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("target", StringArgumentType.word()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							ServerPlayerEntity t = BOTS.get(StringArgumentType.getString(c, "target")).player;
							AttackEntityCallback.EVENT.invoker().interact(p, p.getServerWorld(), Hand.MAIN_HAND, t, null);
							p.attack(t);
							reply(c, "attacked, target health " + t.getHealth());
							return 1;
						}))))
				.then(CommandManager.literal("info").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					Bot bot = bot(c);
					ServerPlayerEntity p = bot.player;
					StringBuilder sb = new StringBuilder();
					sb.append(p.getName().getString()).append(" at ").append(p.getBlockPos().toShortString())
							.append(" mode=").append(p.interactionManager.getGameMode()).append(" hp=").append(p.getHealth())
							.append(" glowing=").append(p.isGlowing()).append("\ninventory:");
					for (int i = 0; i < p.getInventory().size(); i++) {
						if (!p.getInventory().getStack(i).isEmpty()) {
							sb.append(' ').append(p.getInventory().getStack(i));
						}
					}
					sb.append("\nmessages:");
					for (String m : bot.messages) {
						sb.append("\n  ").append(m);
					}
					reply(c, sb.toString());
					return 1;
				})))
				.then(CommandManager.literal("clearmsgs").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					bot(c).messages.clear();
					return 1;
				})))
				.then(CommandManager.literal("guards").executes(c -> {
					ServerWorld w = c.getSource().getServer().getOverworld();
					StringBuilder sb = new StringBuilder("guards:");
					for (var g : w.getEntitiesByType(cellbreak.CbContent.GUARD, e -> true)) {
						var path = g.getNavigation().getCurrentPath();
						sb.append("\n  at ").append(g.getBlockPos().toShortString())
								.append(" target=").append(g.getTarget() == null ? "-" : g.getTarget().getName().getString())
								.append(" path=").append(path == null ? "-" : path.getEnd() + " reaches=" + path.reachesTarget()
										+ " done=" + path.isFinished());
					}
					reply(c, sb.toString());
					return 1;
				}))
				.then(CommandManager.literal("probe").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					ServerPlayerEntity p = bot(c).player;
					ServerWorld w = p.getServerWorld();
					StringBuilder sb = new StringBuilder("probe " + p.getName().getString() + ": partOfGame=" + p.isPartOfGame()
							+ " canTakeDamage=" + p.canTakeDamage() + " difficulty=" + w.getDifficulty()
							+ " inWorldPlayers=" + w.getPlayers().contains(p));
					for (var g : w.getEntitiesByType(cellbreak.CbContent.GUARD, e -> true)) {
						var pred = net.minecraft.entity.ai.TargetPredicate.createAttackable().setBaseMaxDistance(64).ignoreVisibility();
						sb.append(" | guard: canTarget=").append(g.canTarget(p)).append(" pred=").append(pred.test(g, p))
								.append(" dist=").append((int) g.distanceTo(p));
					}
					reply(c, sb.toString());
					return 1;
				})))
				.then(CommandManager.literal("map").executes(CellbreakTest::map)));
	}

	private static Bot bot(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		Bot bot = BOTS.get(StringArgumentType.getString(c, "name"));
		if (bot == null) {
			throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Text.literal("no such bot")).create();
		}
		return bot;
	}

	private static void reply(CommandContext<ServerCommandSource> c, String s) {
		c.getSource().sendFeedback(() -> Text.literal(s), false);
	}

	private static int spawn(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		String name = StringArgumentType.getString(c, "name");
		MinecraftServer server = c.getSource().getServer();
		ServerWorld world = server.getOverworld();
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
		ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile, SyncedClientOptions.createDefault());
		FakeConnection connection = new FakeConnection();
		server.getPlayerManager().onPlayerConnect(connection, player, new ConnectedClientData(profile, 0, player.getClientOptions(), false));
		BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
		player.teleport(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
		BOTS.put(name, new Bot(player, connection, new ArrayDeque<>()));
		reply(c, "bot " + name + " joined");
		return 1;
	}

	/** Writes the prison's block layout (y = -1..3 around the floor) to run/prison_map.txt. */
	private static int map(CommandContext<ServerCommandSource> c) {
		PrisonLayout layout = PrisonState.get(c.getSource().getServer()).getLayout();
		ServerWorld world = c.getSource().getServer().getOverworld();
		int ox = layout.escapeBox.getMinX() + 4;
		int oy = layout.escapeBox.getMinY() + 64;
		int oz = layout.escapeBox.getMinZ() + 4;
		StringBuilder sb = new StringBuilder();
		sb.append(ox).append(' ').append(oy).append(' ').append(oz).append('\n');
		for (int z = -6; z <= 45; z++) {
			for (int x = -6; x <= 53; x++) {
				for (int y = -1; y <= 3; y++) {
					var id = Registries.BLOCK.getId(world.getBlockState(new BlockPos(ox + x, oy + y, oz + z)).getBlock());
					sb.append(id.getPath()).append(y < 3 ? "," : "");
				}
				sb.append(x < 53 ? ";" : "");
			}
			sb.append('\n');
		}
		try {
			Files.writeString(Path.of("prison_map.txt"), sb.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		reply(c, "map written");
		return 1;
	}

	/** A connection with no socket: packets pile up in an embedded channel we read in tests. */
	static class FakeConnection extends ClientConnection {
		private final EmbeddedChannel channel = new EmbeddedChannel();

		FakeConnection() {
			super(NetworkSide.SERVERBOUND);
			try {
				for (Field f : ClientConnection.class.getDeclaredFields()) {
					if (f.getType() == Channel.class) {
						f.setAccessible(true);
						f.set(this, channel);
					}
				}
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		}

		EmbeddedChannel channel() {
			return channel;
		}

		@Override
		public void tryDisableAutoRead() {
		}

		@Override
		public void handleDisconnection() {
		}

		@Override
		public void setInitialPacketListener(PacketListener listener) {
		}

		@Override
		public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T listener) {
		}

		@Override
		public void transitionOutbound(NetworkState<?> state) {
		}
	}
}
