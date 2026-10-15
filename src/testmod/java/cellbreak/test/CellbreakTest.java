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
				.then(CommandManager.literal("hitnpc").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("times", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
								.executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							var npcs = p.getServerWorld().getEntitiesByClass(cellbreak.entity.PrisonerNpcEntity.class,
									p.getBoundingBox().expand(6), e -> true);
							if (npcs.isEmpty()) {
								reply(c, "no npc near");
								return 0;
							}
							var npc = npcs.get(0);
							int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "times");
							for (int i = 0; i < n; i++) {
								p.resetLastAttackedTicks();
								npc.timeUntilRegen = 0;
								p.attack(npc);
							}
							reply(c, "hit " + npc.getName().getString() + " hp=" + npc.getHealth() + " at " + npc.getBlockPos().toShortString()
									+ " target=" + (npc.getTarget() == null ? "-" : npc.getTarget().getName().getString()));
							return 1;
						}))))
				.then(CommandManager.literal("talk").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("times", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
								.executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							var mobs = p.getServerWorld().getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
									p.getBoundingBox().expand(4), e -> true);
							mobs.sort(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
							if (mobs.isEmpty()) {
								reply(c, "nobody near");
								return 0;
							}
							var mob = mobs.get(0);
							int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "times");
							for (int i = 0; i < n; i++) {
								mob.interact(p, Hand.MAIN_HAND);
								if (p.currentScreenHandler != p.playerScreenHandler) {
									p.closeHandledScreen();
								}
							}
							reply(c, "talked to " + mob.getName().getString() + " x" + n + ", its target="
									+ (mob.getTarget() == null ? "-" : mob.getTarget().getName().getString()));
							return 1;
						}))))
				.then(CommandManager.literal("sleep").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							var r = p.trySleep(BlockPosArgumentType.getBlockPos(c, "pos"));
							reply(c, "sleep -> " + r.left().map(Object::toString).orElse("ok") + " sleeping=" + p.isSleeping());
							return 1;
						}))))
				.then(CommandManager.literal("drop").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					ServerPlayerEntity p = bot(c).player;
					p.dropSelectedItem(true);
					reply(c, "dropped");
					return 1;
				})))
				.then(CommandManager.literal("lights").executes(c -> {
					ServerWorld w = c.getSource().getServer().getOverworld();
					StringBuilder sb = new StringBuilder("lights:");
					for (BlockPos p : BlockPos.iterate(180, 55, 200, 310, 100, 320)) {
						if (w.getBlockState(p).isOf(net.minecraft.block.Blocks.LIGHT)) {
							sb.append(' ').append(p.toShortString()).append(';');
						}
					}
					reply(c, sb.toString());
					return 1;
				}))
				.then(CommandManager.literal("info").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					Bot bot = bot(c);
					ServerPlayerEntity p = bot.player;
					StringBuilder sb = new StringBuilder();
					sb.append(p.getName().getString()).append(" at ").append(p.getBlockPos().toShortString())
							.append(" mode=").append(p.interactionManager.getGameMode()).append(" hp=").append(p.getHealth())
							.append(" glowing=").append(p.isGlowing()).append(" pose=").append(p.getPose()).append("\ninventory:");
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
								.append(" sniper=").append(g.isSniper()).append(" duty=").append(g.hasDuty())
								.append(" v=").append(g.getVariant())
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
				.then(CommandManager.literal("dump")
						.then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(CellbreakTest::dump))))
				.then(CommandManager.literal("scan")
						.then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(CellbreakTest::scan))))
				.then(CommandManager.literal("cmdblocks").executes(c -> {
					ServerWorld world = c.getSource().getServer().getOverworld();
					StringBuilder sb = new StringBuilder();
					try {
						for (String line : Files.readAllLines(Path.of("scan_blocks.txt"))) {
							String[] f = line.split(" ");
							if (!f[3].contains("command_block") && !f[3].contains("sign")) {
								continue;
							}
							BlockPos pos = new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
							var be = world.getBlockEntity(pos);
							String text = "";
							if (be instanceof net.minecraft.block.entity.CommandBlockBlockEntity cb) {
								text = cb.getCommandExecutor().getCommand();
							} else if (be instanceof net.minecraft.block.entity.SignBlockEntity sign) {
								StringBuilder t = new StringBuilder();
								for (Text m : sign.getFrontText().getMessages(false)) {
									t.append(m.getString()).append(" | ");
								}
								text = t.toString();
							}
							sb.append(line).append(" :: ").append(text).append('\n');
						}
						Files.writeString(Path.of("scan_cmds.txt"), sb.toString());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					reply(c, "cmdblocks written");
					return 1;
				}))
				.then(CommandManager.literal("ground").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
					BlockPos p = BlockPosArgumentType.getBlockPos(c, "pos");
					ServerWorld w = c.getSource().getServer().getOverworld();
					int y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
					reply(c, "ground " + p.getX() + " " + y + " " + p.getZ() + " "
							+ Registries.BLOCK.getId(w.getBlockState(new BlockPos(p.getX(), y - 1, p.getZ())).getBlock()).getPath());
					return 1;
				})))
				.then(CommandManager.literal("path").then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(c -> {
							ServerWorld w = c.getSource().getServer().getOverworld();
							BlockPos from = BlockPosArgumentType.getBlockPos(c, "from");
							BlockPos to = BlockPosArgumentType.getBlockPos(c, "to");
							var g = cellbreak.CbContent.GUARD.create(w);
							g.refreshPositionAndAngles(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0, 0);
							w.spawnEntity(g);
							g.setOnGround(true);
							var path = g.getNavigation().findPathTo(to, 0);
							StringBuilder sb = new StringBuilder("path " + from.toShortString() + " -> " + to.toShortString() + ": ");
							if (path == null) {
								sb.append("null");
							} else {
								sb.append("end=").append(path.getEnd().getBlockPos().toShortString()).append(" reaches=")
										.append(path.reachesTarget()).append(" nodes=").append(path.getLength()).append(" via");
								for (int i = 0; i < path.getLength(); i += 4) {
									sb.append(' ').append(path.getNodePos(i).toShortString().replace(" ", ""));
								}
							}
							g.discard();
							reply(c, sb.toString());
							return 1;
						}))))
				.then(CommandManager.literal("useside").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("side", StringArgumentType.word()).executes(c -> {
									ServerPlayerEntity p = bot(c).player;
									BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
									Direction side = Direction.byName(StringArgumentType.getString(c, "side"));
									BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5)),
											side, pos, false);
									var result = UseBlockCallback.EVENT.invoker().interact(p, p.getServerWorld(), Hand.MAIN_HAND, hit);
									reply(c, "use " + pos.toShortString() + " " + side + " -> " + result + ", now at "
											+ p.getBlockPos().toShortString() + " pose=" + p.getPose());
									return 1;
								})))))
				.then(CommandManager.literal("crawl").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("side", StringArgumentType.word()).executes(c -> {
									ServerPlayerEntity p = bot(c).player;
									BlockPos pos = BlockPosArgumentType.getBlockPos(c, "pos");
									Direction side = Direction.byName(StringArgumentType.getString(c, "side"));
									BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);
									BlockPos t = pos.offset(side);
									ServerWorld w = p.getServerWorld();
									String dbg = " t=" + t.toShortString() + " t:" + w.getBlockState(t) + " up:" + w.getBlockState(t.up())
											+ " down:" + w.getBlockState(t.down()) + " swim=" + p.isInSwimmingPose();
									boolean ok = cellbreak.game.CellbreakGame.tryEnterCrawlSpace(p, hit);
									reply(c, "crawl -> " + ok + " now " + p.getPos() + dbg);
									return 1;
								})))))
				.then(CommandManager.literal("sniperview").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					ServerPlayerEntity p = bot(c).player;
					ServerWorld w = p.getServerWorld();
					StringBuilder sb = new StringBuilder("sky=" + w.isSkyVisible(p.getBlockPos().up()));
					for (var g : w.getEntitiesByType(cellbreak.CbContent.GUARD, e -> e.isSniper())) {
						var hit = w.raycast(new net.minecraft.world.RaycastContext(g.getEyePos(), p.getEyePos(),
								net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, g));
						sb.append("\n  sniper ").append(g.getBlockPos().toShortString()).append(" dist=").append((int) g.distanceTo(p))
								.append(" canSee=").append(g.canSee(p)).append(" hit=").append(hit.getType()).append(' ')
								.append(hit.getBlockPos().toShortString()).append(' ').append(w.getBlockState(hit.getBlockPos()).getBlock());
					}
					reply(c, sb.toString());
					return 1;
				})))
				.then(CommandManager.literal("useitem").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					ServerPlayerEntity p = bot(c).player;
					var result = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker()
							.interact(p, p.getServerWorld(), Hand.MAIN_HAND);
					reply(c, "use item -> " + result.getResult());
					return 1;
				})))
				.then(CommandManager.literal("sneak").then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("on", StringArgumentType.word()).executes(c -> {
							ServerPlayerEntity p = bot(c).player;
							boolean on = StringArgumentType.getString(c, "on").equals("on");
							p.setSneaking(on);
							p.setPose(on ? net.minecraft.entity.EntityPose.CROUCHING : net.minecraft.entity.EntityPose.STANDING);
							return 1;
						}))))
				.then(CommandManager.literal("trade").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> {
					ServerPlayerEntity p = bot(c).player;
					var npcs = p.getServerWorld().getEntitiesByClass(cellbreak.entity.PrisonerNpcEntity.class,
							p.getBoundingBox().expand(64), cellbreak.entity.PrisonerNpcEntity::isTrader);
					if (npcs.isEmpty()) {
						reply(c, "no trader");
						return 0;
					}
					var npc = npcs.get(0);
					var result = npc.interactMob(p, Hand.MAIN_HAND);
					StringBuilder sb = new StringBuilder("trade with " + npc.getName().getString() + " -> " + result
							+ ", screen=" + p.currentScreenHandler.getClass().getSimpleName() + ", offers:");
					for (var o : npc.getOffers()) {
						sb.append(" [").append(o.getOriginalFirstBuyItem()).append(" -> ").append(o.getSellItem()).append(']');
					}
					npc.setCustomer(null);
					p.closeHandledScreen();
					reply(c, sb.toString());
					return 1;
				})))
				.then(CommandManager.literal("npcs").executes(c -> {
					ServerWorld w = c.getSource().getServer().getOverworld();
					StringBuilder sb = new StringBuilder("npc prisoners:");
					for (var n : w.getEntitiesByType(cellbreak.CbContent.PRISONER, e -> true)) {
						sb.append("\n  ").append(n.getName().getString()).append(" at ").append(n.getBlockPos().toShortString())
								.append(" cell=").append(n.cell).append(" v=").append(n.getVariant()).append(' ').append(n.personality)
								.append(" slot=").append(n.lineupSlot == null ? "-" : n.lineupSlot.toShortString())
								.append(" inf=").append(n.infirmaryUntil).append(" hp=").append(n.getHealth())
								.append(" target=").append(n.getTarget() == null ? "-" : n.getTarget().getName().getString())
								.append(" hand=").append(n.getMainHandStack().getItem());
					}
					reply(c, sb.toString());
					return 1;
				}))
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

	/**
	 * Scans a box of the world: writes scan_top.txt (topmost block per column, below the box top)
	 * and scan_blocks.txt (every block that is not natural terrain, with its position).
	 */
	private static int scan(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		BlockPos a = BlockPosArgumentType.getBlockPos(c, "from");
		BlockPos b = BlockPosArgumentType.getBlockPos(c, "to");
		ServerWorld world = c.getSource().getServer().getOverworld();
		java.util.Set<String> natural = java.util.Set.of("air", "cave_air", "stone", "dirt", "grass_block", "water", "sand",
				"gravel", "andesite", "diorite", "granite", "coal_ore", "iron_ore", "bedrock", "short_grass", "tall_grass",
				"seagrass", "tall_seagrass", "kelp", "kelp_plant", "sandstone", "clay", "lava", "snow", "fern", "large_fern",
				"oak_leaves", "birch_leaves", "spruce_leaves", "dark_oak_leaves", "jungle_leaves", "acacia_leaves", "oak_log",
				"birch_log", "spruce_log", "dark_oak_log", "dandelion", "poppy", "gold_ore", "redstone_ore", "lapis_ore",
				"diamond_ore", "emerald_ore", "deepslate", "tuff", "copper_ore", "sugar_cane", "lily_pad", "pumpkin",
				"azure_bluet", "oxeye_daisy", "cornflower", "rose_bush", "peony", "lilac", "sunflower", "brown_mushroom",
				"red_mushroom", "dead_bush", "cactus", "vine", "podzol", "coarse_dirt", "mossy_cobblestone", "cobweb");
		StringBuilder top = new StringBuilder();
		StringBuilder list = new StringBuilder();
		int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
		top.append(minX).append(' ').append(minZ).append('\n');
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				String topId = "void";
				int topY = minY;
				for (int y = maxY; y >= minY; y--) {
					pos.set(x, y, z);
					var state = world.getBlockState(pos);
					String id = Registries.BLOCK.getId(state.getBlock()).getPath();
					if (!natural.contains(id)) {
						list.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(id).append('\n');
					}
					if (topId.equals("void") && !state.isAir()) {
						topId = id;
						topY = y;
					}
				}
				top.append(topId).append(':').append(topY).append(x < maxX ? ";" : "");
			}
			top.append('\n');
		}
		try {
			Files.writeString(Path.of("scan_top.txt"), top.toString());
			Files.writeString(Path.of("scan_blocks.txt"), list.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		reply(c, "scan written");
		return 1;
	}

	/** Writes every non-air block of a box with its full state to dump.txt ("x y z state"). */
	private static int dump(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		BlockPos a = BlockPosArgumentType.getBlockPos(c, "from");
		BlockPos b = BlockPosArgumentType.getBlockPos(c, "to");
		ServerWorld world = c.getSource().getServer().getOverworld();
		StringBuilder sb = new StringBuilder();
		for (BlockPos p : BlockPos.iterate(a, b)) {
			var state = world.getBlockState(p);
			if (!state.isAir()) {
				String s = state.toString();
				// "Block{minecraft:stone_bricks}[...]" -> "stone_bricks[...]"
				s = s.substring(s.indexOf(':') + 1).replace("}", "");
				sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append(' ').append(s).append('\n');
			}
		}
		try {
			Files.writeString(Path.of("dump.txt"), sb.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		reply(c, "dump written");
		return 1;
	}

	/** Writes the prison's block layout (y = -1..3 around the floor) to run/prison_map.txt. */
	private static int map(CommandContext<ServerCommandSource> c) {
		PrisonLayout layout = PrisonState.get(c.getSource().getServer()).getLayout();
		ServerWorld world = c.getSource().getServer().getOverworld();
		int ox = layout.areas.get(0).getMinX() + 2;
		int oy = layout.breakMinY + 6;
		int oz = layout.areas.get(0).getMinZ() + 2;
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
