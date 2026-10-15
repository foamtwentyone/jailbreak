package cellbreak;

import cellbreak.entity.GuardEntity;
import cellbreak.game.CellbreakGame;
import cellbreak.game.CellbreakGame.Role;
import cellbreak.game.PrisonBuilder;
import cellbreak.game.PrisonLayout;
import cellbreak.game.PrisonState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.command.argument.EntityArgumentType;
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
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> registerCommands(dispatcher));
		registerEvents();
	}

	private static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> CellbreakGame.get(server).tick());
		ServerLifecycleEvents.SERVER_STOPPED.register(CellbreakGame::remove);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				CellbreakGame.get(server).onJoin(handler.getPlayer()));

		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayerEntity p) {
				return !CellbreakGame.get(p.getServer()).onFatalDamage(p);
			}
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (player instanceof ServerPlayerEntity p && isPrisonBlock(state)) {
				CellbreakGame.get(p.getServer()).onBlockBroken(p);
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (source.getAttacker() instanceof ServerPlayerEntity p) {
				CellbreakGame game = CellbreakGame.get(p.getServer());
				if (entity instanceof GuardEntity || entity instanceof ServerPlayerEntity t && game.isGuard(t)) {
					game.onGuardAttacked(p);
				}
			}
			return true;
		});

		UseEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
			if (hand != Hand.MAIN_HAND || !player.getMainHandStack().isOf(CbContent.HANDCUFFS)) {
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
			BlockPos pos = hit.getBlockPos();
			BlockState state = world.getBlockState(pos);
			if (hand != Hand.MAIN_HAND || !player.getMainHandStack().isOf(CbContent.CELL_KEY)
					|| !state.isOf(Blocks.IRON_DOOR)) {
				return ActionResult.PASS;
			}
			if (!world.isClient) {
				((DoorBlock) state.getBlock()).setOpen(player, world, state, pos, !state.get(DoorBlock.OPEN));
			}
			return ActionResult.SUCCESS;
		});
	}

	private static boolean isPrisonBlock(BlockState state) {
		return state.isOf(CbContent.PRISON_WALL) || state.isOf(CbContent.CRACKED_PRISON_WALL)
				|| state.isOf(CbContent.CELL_BARS) || state.isOf(CbContent.VENT_COVER)
				|| state.isOf(CbContent.PRISON_FLOOR);
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("cellbreak")
				.then(CommandManager.literal("build").requires(s -> s.hasPermissionLevel(2)).executes(Cellbreak::build))
				.then(CommandManager.literal("join")
						.then(CommandManager.literal("prisoner").executes(c -> join(c, Role.PRISONER)))
						.then(CommandManager.literal("guard").executes(c -> join(c, Role.GUARD))))
				.then(CommandManager.literal("leave").executes(Cellbreak::leave))
				.then(CommandManager.literal("role").requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("players", EntityArgumentType.players())
								.then(CommandManager.literal("prisoner").executes(c -> role(c, Role.PRISONER)))
								.then(CommandManager.literal("guard").executes(c -> role(c, Role.GUARD)))))
				.then(CommandManager.literal("start").requires(s -> s.hasPermissionLevel(2))
						.executes(c -> start(c, 30, -1))
						.then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 240))
								.executes(c -> start(c, IntegerArgumentType.getInteger(c, "minutes"), -1))
								.then(CommandManager.argument("npcGuards", IntegerArgumentType.integer(0, 20))
										.executes(c -> start(c, IntegerArgumentType.getInteger(c, "minutes"),
												IntegerArgumentType.getInteger(c, "npcGuards"))))))
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
				.then(CommandManager.literal("status").executes(Cellbreak::status))
				.then(CommandManager.literal("heat").requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100))
										.executes(Cellbreak::setHeat))))
				.then(CommandManager.literal("tp").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
					PrisonLayout layout = PrisonState.get(c.getSource().getServer()).getLayout();
					if (layout == null) {
						c.getSource().sendError(Text.literal("Тюрьма ещё не построена."));
						return 0;
					}
					BlockPos p = layout.npcGuardSpawn;
					c.getSource().getPlayerOrThrow().teleport(c.getSource().getServer().getOverworld(),
							p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
					return 1;
				})));
	}

	private static int build(CommandContext<ServerCommandSource> c) {
		ServerCommandSource src = c.getSource();
		if (CellbreakGame.get(src.getServer()).isRunning()) {
			src.sendError(Text.literal("Сначала остановите раунд: /cellbreak stop"));
			return 0;
		}
		if (src.getWorld() != src.getServer().getOverworld()) {
			src.sendError(Text.literal("Тюрьму можно построить только в обычном мире."));
			return 0;
		}
		BlockPos origin = BlockPos.ofFloored(src.getPosition());
		PrisonLayout layout = new PrisonBuilder(src.getWorld(), origin).build();
		PrisonState.get(src.getServer()).setLayout(layout);
		src.sendFeedback(() -> Text.literal("Тюрьма построена! Камер: " + layout.cells.size()
				+ ". Теперь игроки выбирают роль: /cellbreak join prisoner или /cellbreak join guard,"
				+ " затем /cellbreak start").formatted(Formatting.GREEN), true);
		return 1;
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

	private static int start(CommandContext<ServerCommandSource> c, int minutes, int npcGuards) {
		String error = CellbreakGame.get(c.getSource().getServer()).start(minutes, npcGuards);
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
		sb.append("Тюрьма: ").append(built ? "построена" : "не построена");
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
}
