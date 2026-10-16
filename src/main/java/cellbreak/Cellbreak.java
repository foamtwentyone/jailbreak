package cellbreak;

import cellbreak.entity.GuardEntity;
import cellbreak.entity.PrisonerNpcEntity;
import cellbreak.game.CellbreakGame;
import cellbreak.game.CellbreakGame.Role;
import cellbreak.game.MapCommands;
import cellbreak.game.PrisonLayout;
import cellbreak.game.PrisonState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import cellbreak.block.FakeBlock;
import net.minecraft.util.TypedActionResult;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public class Cellbreak implements ModInitializer {
	public static final String MOD_ID = "cellbreak";

	@Override
	public void onInitialize() {
		CbContent.init();
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(cellbreak.net.SearchlightPayload.ID,
				cellbreak.net.SearchlightPayload.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> registerCommands(dispatcher));
		registerEvents();
	}

	private static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> CellbreakGame.get(server).tick());
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
				!CellbreakGame.get(sender.getServer()).extras().radio(sender, message.getSignedContent()));
		ServerLifecycleEvents.SERVER_STOPPED.register(CellbreakGame::remove);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				CellbreakGame.get(server).onJoin(handler.getPlayer()));

		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayerEntity p) {
				return !CellbreakGame.get(p.getServer()).onFatalDamage(p, source);
			}
			if (entity instanceof PrisonerNpcEntity npc && npc.getServer() != null) {
				return !CellbreakGame.get(npc.getServer()).onNpcKnockedOut(npc, source);
			}
			if (entity instanceof GuardEntity && source.getAttacker() instanceof ServerPlayerEntity p) {
				CellbreakGame game = CellbreakGame.get(p.getServer());
				if (game.isRunning() && game.isPrisoner(p)) {
					// A guard down: the whole prison goes into lock-down.
					game.extras().lockdown("нападение на охранника");
				}
			}
			return true;
		});

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
			if (player instanceof ServerPlayerEntity p && CbContent.WORK_TOOLS.contains(p.getMainHandStack().getItem())
					&& !p.isCreative() && !CellbreakGame.get(p.getServer()).canWorkAt(pos)
					&& !(p.getMainHandStack().isOf(CbContent.WORK_PICKAXE) && isWall(state))) {
				p.sendMessage(Text.literal("Рабочим инструментом можно работать только на рабочем поле во время работы.")
						.formatted(Formatting.RED), true);
				return false;
			}
			if (player instanceof ServerPlayerEntity p && (isMakeshiftTool(p) || p.getMainHandStack().isOf(CbContent.WORK_PICKAXE))) {
				PrisonLayout layout = PrisonState.get(p.getServer()).getLayout();
				if (layout != null && !layout.canBreakAt(pos)) {
					p.sendMessage(Text.literal("Здесь ломать нельзя.").formatted(Formatting.RED), true);
					return false;
				}
			}
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (player instanceof ServerPlayerEntity p && CbContent.WORK_TOOLS.contains(p.getMainHandStack().getItem())
					&& !isWall(state)) {
				CellbreakGame game = CellbreakGame.get(p.getServer());
				if (game.canWorkAt(pos)) {
					game.onWorkBlockBroken(p, pos, state);
				}
				return;
			}
			if (!(player instanceof ServerPlayerEntity p) || !(isMakeshiftTool(p) || p.getMainHandStack().isOf(CbContent.WORK_PICKAXE))) {
				return;
			}
			// Vanilla stone bricks crack first, like our own prison walls.
			if (state.isIn(BlockTags.STONE_BRICKS) && !state.isOf(Blocks.CRACKED_STONE_BRICKS)
					&& (p.getMainHandStack().isOf(CbContent.MAKESHIFT_PICKAXE) || p.getMainHandStack().isOf(CbContent.WORK_PICKAXE))) {
				world.setBlockState(pos, Blocks.CRACKED_STONE_BRICKS.getDefaultState());
			}
			CellbreakGame.get(p.getServer()).onBlockBroken(p, pos, state);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			// Nobody beats up a prisoner lying in the infirmary or sitting in solitary.
			if (entity instanceof ServerPlayerEntity t && source.getAttacker() != null
					&& CellbreakGame.get(t.getServer()).isPrisoner(t) && CellbreakGame.get(t.getServer()).isOutOfPlay(t)) {
				return false;
			}
			if (source.getAttacker() instanceof ServerPlayerEntity p) {
				CellbreakGame game = CellbreakGame.get(p.getServer());
				if (entity instanceof GuardEntity || entity instanceof ServerPlayerEntity t && game.isGuard(t)) {
					game.onGuardAttacked(p);
				} else if (entity instanceof PrisonerNpcEntity || entity instanceof ServerPlayerEntity t && game.isPrisoner(t)) {
					game.onPrisonerFight(p, entity);
				}
			}
			return true;
		});

		UseEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}
			// The snitch whispers to a guard (sneaking, right click).
			if (player instanceof ServerPlayerEntity s && player.isSneaking()
					&& (target instanceof GuardEntity || target instanceof ServerPlayerEntity tg && CellbreakGame.get(s.getServer()).isGuard(tg))
					&& CellbreakGame.get(s.getServer()).isRunning() && CellbreakGame.get(s.getServer()).extras().reportToGuard(s)) {
				return ActionResult.SUCCESS;
			}
			if (!player.getMainHandStack().isOf(CbContent.HANDCUFFS)) {
				return ActionResult.PASS;
			}
			if (player instanceof ServerPlayerEntity guard && target instanceof ServerPlayerEntity prisoner) {
				CellbreakGame game = CellbreakGame.get(guard.getServer());
				if (game.isGuard(guard) && game.isPrisoner(prisoner)
						&& !guard.getItemCooldownManager().isCoolingDown(CbContent.HANDCUFFS)) {
					guard.getItemCooldownManager().set(CbContent.HANDCUFFS, 60);
					game.handcuff(guard, prisoner);
					return ActionResult.SUCCESS;
				}
			}
			return ActionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand != Hand.MAIN_HAND) {
				return ActionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState state = world.getBlockState(pos);
			var stack = player.getMainHandStack();
			ServerPlayerEntity sp = player instanceof ServerPlayerEntity s ? s : null;
			CellbreakGame game = sp != null ? CellbreakGame.get(sp.getServer()) : null;

			// A dummy in the bed (or taking it back).
			if (state.getBlock() instanceof BedBlock && sp != null && game.isRunning()) {
				BlockPos head = state.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD ? pos
						: pos.offset(state.get(BedBlock.FACING));
				if (game.extras().useBed(sp, head)) {
					return ActionResult.SUCCESS;
				}
			}
			// No sleeping through the routine.
			if (state.getBlock() instanceof BedBlock) {
				if (sp != null && game.isRunning() && game.phase() != CellbreakGame.Phase.LIGHTS_OUT) {
					sp.sendMessage(Text.literal("Спать — только после отбоя.").formatted(Formatting.GRAY), true);
					return ActionResult.FAIL;
				}
				return ActionResult.PASS;
			}
			// The map's own door buttons and levers are dead during a round: the routine (and keys) run the doors.
			if ((state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock) && sp != null
					&& game.isRunning() && !sp.isCreative() && nearIronDoor(world, pos)) {
				sp.sendMessage(Text.literal("Не работает. Двери открывает только охрана.").formatted(Formatting.GRAY), true);
				return ActionResult.FAIL;
			}
			if (sp != null && game.isRunning()) {
				var ex = game.extras();
				// CCTV: a rag over the lens.
				if (stack.isOf(CbContent.RAG) && ex.cameraAt(pos) >= 0 && game.isPrisoner(sp)) {
					return ex.blindCamera(sp, pos) ? ActionResult.SUCCESS : ActionResult.FAIL;
				}
				if (ex.cameraAt(pos) >= 0) {
					return ActionResult.FAIL;
				}
				if (ex.useMonitor(sp, pos)) {
					return ActionResult.SUCCESS;
				}
				if (ex.jobAt(pos) >= 0) {
					return ex.work(sp, pos) ? ActionResult.SUCCESS : ActionResult.FAIL;
				}
				if (stack.isOf(CbContent.GATE_KEY) && state.isOf(Blocks.IRON_DOOR)) {
					if (!ex.useGateKey(sp, pos)) {
						sp.sendMessage(Text.literal("Этот ключ — от ворот.").formatted(Formatting.GRAY), true);
					}
					return ActionResult.SUCCESS;
				}
				if (stack.isOf(CbContent.CELL_KEY) && state.isOf(Blocks.IRON_DOOR) && ex.isGateDoor(pos) && !game.isGuard(sp)) {
					sp.sendMessage(Text.literal("Ключ от камер к воротам не подходит.").formatted(Formatting.GRAY), true);
					return ActionResult.FAIL;
				}
				if (stack.isOf(CbContent.ROPE_HOOK) && ex.useRope(sp, hit)) {
					return ActionResult.SUCCESS;
				}
			}
			// Gym machines and punching bags: one rep per click.
			if (sp != null && game.useGym(sp, pos)) {
				return ActionResult.SUCCESS;
			}
			// The kitchen chest: anybody may open it, but not unnoticed.
			if (sp != null && game.isFoodChest(pos)) {
				game.onFoodChestOpened(sp, pos);
				return ActionResult.PASS;
			}
			// Chalk drawings: prisoners draw, guards wipe them off.
			if (state.isOf(CbContent.GRAFFITI) && sp != null && game.isGuard(sp)) {
				game.wipeGraffiti(sp, pos);
				return ActionResult.SUCCESS;
			}
			if (state.isOf(CbContent.GRAFFITI) && stack.isOf(CbContent.RAG)) {
				if (sp != null && game.isRunning()) {
					game.washGraffiti(sp, pos);
				}
				return ActionResult.SUCCESS;
			}
			if (stack.isOf(CbContent.CHALK)) {
				return sp != null && game.drawGraffiti(sp, pos, hit.getSide()) ? ActionResult.SUCCESS : ActionResult.PASS;
			}
			// Cell key opens iron doors.
			if (stack.isOf(CbContent.CELL_KEY) && state.isOf(Blocks.IRON_DOOR)) {
				if (sp != null) {
					((DoorBlock) state.getBlock()).setOpen(player, world, state, pos, !state.get(DoorBlock.OPEN));
				}
				return ActionResult.SUCCESS;
			}
			// Fake walls and vent covers.
			if (state.getBlock() instanceof FakeBlock fake) {
				if (stack.isOf(CbContent.BATON)) {
					if (sp != null && game.isGuard(sp)) {
						game.revealFake(sp, pos);
					}
					return ActionResult.SUCCESS;
				}
				if (stack.isEmpty() && player.isSneaking()) {
					if (sp != null) {
						game.takeFake(sp, pos, fake);
					}
					return ActionResult.SUCCESS;
				}
			}
			if (stack.isOf(CbContent.FAKE_WALL) || stack.isOf(CbContent.FAKE_VENT)) {
				return sp != null && game.placeFake(sp) ? ActionResult.SUCCESS : ActionResult.PASS;
			}
			// Hand in work.
			if (state.isOf(CbContent.JOB_DESK)) {
				if (sp != null) {
					game.useJobDesk(sp);
				}
				return ActionResult.SUCCESS;
			}
			// Squeeze into a vent or a crawl space.
			if (sp != null && stack.isEmpty() && !player.isSneaking() && CellbreakGame.tryEnterCrawlSpace(sp, hit)) {
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, world, hand) -> {
			var stack = player.getStackInHand(hand);
			if (hand == Hand.MAIN_HAND && player instanceof ServerPlayerEntity sp && CellbreakGame.get(sp.getServer()).isRunning()) {
				var ex = CellbreakGame.get(sp.getServer()).extras();
				if (ex.nextCamera(sp)) {
					return TypedActionResult.success(stack);
				}
				if (stack.isOf(CbContent.TABLET) && ex.useTablet(sp)) {
					sp.getItemCooldownManager().set(CbContent.TABLET, 40);
					return TypedActionResult.success(stack);
				}
				if (stack.isOf(CbContent.PARCEL) && ex.openParcel(sp)) {
					return TypedActionResult.success(stack);
				}
			}
			if (hand == Hand.MAIN_HAND && player instanceof ServerPlayerEntity sp
					&& (stack.isOf(CbContent.FAKE_WALL) || stack.isOf(CbContent.FAKE_VENT))
					&& CellbreakGame.get(sp.getServer()).placeFake(sp)) {
				return TypedActionResult.success(stack);
			}
			return TypedActionResult.pass(stack);
		});
	}

	private static boolean isWall(BlockState state) {
		return CbContent.VANILLA_WALLS.contains(state.getBlock()) || state.isOf(CbContent.PRISON_WALL)
				|| state.isOf(CbContent.CRACKED_PRISON_WALL);
	}

	private static boolean isMakeshiftTool(ServerPlayerEntity p) {
		var stack = p.getMainHandStack();
		return stack.isOf(CbContent.MAKESHIFT_PICKAXE) || stack.isOf(CbContent.MAKESHIFT_SHOVEL)
				|| stack.isOf(CbContent.FILE);
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("cellbreak")
				.then(CommandManager.literal("join")
						.then(CommandManager.literal("prisoner").executes(c -> join(c, Role.PRISONER)))
						.then(CommandManager.literal("guard").executes(c -> join(c, Role.GUARD)))
						.then(CommandManager.literal("random").executes(c -> join(c, Role.RANDOM)))
						.then(CommandManager.literal("spectator").executes(c -> join(c, Role.SPECTATOR))))
				.then(CommandManager.literal("menu").executes(c -> {
					CellbreakGame.get(c.getSource().getServer()).sendRoleMenu(c.getSource().getPlayerOrThrow());
					return 1;
				}))
				.then(CommandManager.literal("leave").executes(Cellbreak::leave))
				.then(CommandManager.literal("donos")
						.then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
							ServerPlayerEntity s = c.getSource().getPlayerOrThrow();
							CellbreakGame game = CellbreakGame.get(c.getSource().getServer());
							if (!game.isRunning()) {
								c.getSource().sendError(Text.literal("Раунд не идёт."));
								return 0;
							}
							game.extras().report(s, EntityArgumentType.getPlayer(c, "player"));
							return 1;
						})))
				.then(CommandManager.literal("role").requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("players", EntityArgumentType.players())
								.then(CommandManager.literal("prisoner").executes(c -> role(c, Role.PRISONER)))
								.then(CommandManager.literal("guard").executes(c -> role(c, Role.GUARD)))))
				.then(CommandManager.literal("start").requires(s -> s.hasPermissionLevel(2))
						.executes(c -> start(c, -1, -1))
						.then(CommandManager.argument("npcGuards", IntegerArgumentType.integer(0, 20))
								.executes(c -> start(c, IntegerArgumentType.getInteger(c, "npcGuards"), -1))
								.then(CommandManager.argument("npcPrisoners", IntegerArgumentType.integer(0, 40))
										.executes(c -> start(c, IntegerArgumentType.getInteger(c, "npcGuards"),
												IntegerArgumentType.getInteger(c, "npcPrisoners"))))))
				.then(CommandManager.literal("stop").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
					CellbreakGame game = CellbreakGame.get(c.getSource().getServer());
					if (!game.isRunning()) {
						c.getSource().sendError(Text.literal("Раунд не идёт."));
						return 0;
					}
					game.stop("остановлен командой");
					return 1;
				}))
				.then(CommandManager.literal("skip").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
					CellbreakGame.get(c.getSource().getServer()).skipPhase();
					return 1;
				}))
				.then(MapCommands.build())
				.then(CommandManager.literal("phase").requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("phase", StringArgumentType.word())
								.suggests((c, b) -> CommandSource.suggestMatching(java.util.Arrays.stream(CellbreakGame.Phase.values())
										.map(ph -> ph.name().toLowerCase(java.util.Locale.ROOT)), b))
								.executes(c -> {
									String name = StringArgumentType.getString(c, "phase").toUpperCase(java.util.Locale.ROOT);
									CellbreakGame.Phase target;
									try {
										target = CellbreakGame.Phase.valueOf(name);
									} catch (IllegalArgumentException e) {
										c.getSource().sendError(Text.literal("Нет такого этапа."));
										return 0;
									}
									if (!CellbreakGame.get(c.getSource().getServer()).jumpTo(target)) {
										c.getSource().sendError(Text.literal("Раунд не идёт или на этой карте нет места для этапа."));
										return 0;
									}
									return 1;
								})))
				.then(CommandManager.literal("status").executes(Cellbreak::status))
				.then(CommandManager.literal("heat").requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100))
										.executes(Cellbreak::setHeat))))
				.then(CommandManager.literal("tp").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
					PrisonLayout layout = PrisonState.get(c.getSource().getServer()).getLayout();
					if (layout == null) {
						c.getSource().sendError(Text.literal("Тюрьма не отмечена на карте."));
						return 0;
					}
					BlockPos p = layout.npcGuardSpawn;
					c.getSource().getPlayerOrThrow().teleport(c.getSource().getServer().getOverworld(),
							p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
					return 1;
				})));
	}

	private static int join(CommandContext<ServerCommandSource> c, Role role) throws CommandSyntaxException {
		ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
		CellbreakGame game = CellbreakGame.get(c.getSource().getServer());
		if (game.isRunning()) {
			c.getSource().sendError(Text.literal("Раунд уже идёт, дождитесь следующего."));
			return 0;
		}
		game.setRole(p, role);
		c.getSource().getServer().getPlayerManager().broadcast(
				Text.literal(p.getName().getString() + " играет за: " + role.title).formatted(Formatting.AQUA), false);
		return 1;
	}

	private static int leave(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
		if (CellbreakGame.get(c.getSource().getServer()).leave(p)) {
			c.getSource().sendFeedback(() -> Text.literal("Ты вышел из игры."), false);
			return 1;
		}
		c.getSource().sendError(Text.literal("Ты и так не участвуешь."));
		return 0;
	}

	private static int role(CommandContext<ServerCommandSource> c, Role role) throws CommandSyntaxException {
		CellbreakGame game = CellbreakGame.get(c.getSource().getServer());
		if (game.isRunning()) {
			c.getSource().sendError(Text.literal("Раунд уже идёт."));
			return 0;
		}
		var targets = EntityArgumentType.getPlayers(c, "players");
		for (ServerPlayerEntity p : targets) {
			game.setRole(p, role);
		}
		c.getSource().sendFeedback(() -> Text.literal("Роль «" + role.title + "» назначена: " + targets.size()), true);
		return targets.size();
	}

	private static int start(CommandContext<ServerCommandSource> c, int npcGuards, int npcPrisoners) {
		String error = CellbreakGame.get(c.getSource().getServer()).start(npcGuards, npcPrisoners);
		if (error != null) {
			c.getSource().sendError(Text.literal(error));
			return 0;
		}
		return 1;
	}

	private static int status(CommandContext<ServerCommandSource> c) {
		ServerCommandSource src = c.getSource();
		CellbreakGame game = CellbreakGame.get(src.getServer());
		boolean built = PrisonState.get(src.getServer()).getLayout() != null;
		StringBuilder sb = new StringBuilder();
		sb.append("Тюрьма: ").append(built ? "отмечена" : "не отмечена");
		sb.append(". Раунд: ").append(game.isRunning() ? "идёт (" + game.phase().title + ")" : "не идёт").append('.');
		game.participants().forEach((id, d) -> {
			ServerPlayerEntity p = src.getServer().getPlayerManager().getPlayer(id);
			sb.append("\n- ").append(p != null ? p.getName().getString() : id).append(": ").append(d.role.title);
			if (game.isRunning() && d.role == Role.PRISONER) {
				sb.append(d.escaped ? " (сбежал)" : ", подозрение " + (int) d.heat + "%");
			}
		});
		src.sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	private static int setHeat(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
		CellbreakGame game = CellbreakGame.get(c.getSource().getServer());
		if (!game.isPrisoner(p)) {
			c.getSource().sendError(Text.literal("Этот игрок не заключённый в текущем раунде."));
			return 0;
		}
		game.data(p).heat = 0;
		game.data(p).wanted = false;
		game.addHeat(p, IntegerArgumentType.getInteger(c, "value"));
		return 1;
	}

	private static boolean nearIronDoor(net.minecraft.world.World world, BlockPos pos) {
		for (BlockPos p : BlockPos.iterate(pos.add(-2, -2, -2), pos.add(2, 2, 2))) {
			if (world.getBlockState(p).isOf(Blocks.IRON_DOOR)) {
				return true;
			}
		}
		return false;
	}
}
