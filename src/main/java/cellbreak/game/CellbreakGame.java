package cellbreak.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cellbreak.CbContent;
import cellbreak.entity.GuardEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

/** One prison round: roles, daily routine, suspicion ("heat"), arrests and escapes. */
public class CellbreakGame {
	public enum Role {
		PRISONER("заключённый"), GUARD("охранник");

		public final String title;

		Role(String title) {
			this.title = title;
		}
	}

	public enum Phase {
		ROLL_CALL("Перекличка", "Все во двор, на жёлтый квадрат!", 1000),
		MEAL("Обед", "Все в столовую!", 6000),
		FREE_TIME("Свободное время", "Можно ходить где угодно.", 9000),
		LIGHTS_OUT("Отбой", "Все по камерам! Двери закрываются.", 18000);

		public final String title;
		public final String hint;
		public final long timeOfDay;

		Phase(String title, String hint, long timeOfDay) {
			this.title = title;
			this.hint = hint;
			this.timeOfDay = timeOfDay;
		}
	}

	/** The daily routine, in seconds. One "day" lasts ten minutes. */
	private static final Object[][] SCHEDULE = {
			{ Phase.ROLL_CALL, 60 }, { Phase.MEAL, 90 }, { Phase.FREE_TIME, 150 },
			{ Phase.ROLL_CALL, 60 }, { Phase.FREE_TIME, 120 }, { Phase.LIGHTS_OUT, 120 },
	};

	public static final double MAX_HEAT = 100;
	private static final int SOLITARY_SECONDS = 60;
	/** A wanted prisoner nobody has seen for this long gets away. */
	private static final int LOSE_PURSUIT_SECONDS = 30;
	private static final int GUARD_SIGHT = 16;
	private static final int PLAYER_GUARD_SIGHT = 24;
	private static final int PRISONER_ORANGE = 0xE6761E;
	private static final int GUARD_NAVY = 0x222E54;

	public static class PlayerData {
		public Role role;
		public double heat;
		public int cell = -1;
		public boolean attended;
		public long solitaryUntil;
		public boolean escaped;
		public boolean wanted;
		long lastSeenTick;
		public GameMode previousMode;
		boolean heatRaised;
		boolean warnedContraband;

		PlayerData(Role role) {
			this.role = role;
		}
	}

	private static final Map<MinecraftServer, CellbreakGame> GAMES = new HashMap<>();

	private final MinecraftServer server;
	private final Map<UUID, PlayerData> players = new HashMap<>();
	private boolean running;
	private long ticks;
	private long endTick;
	private int scheduleIndex;
	private int phaseTicksLeft;
	private int npcGuardCount;

	private CellbreakGame(MinecraftServer server) {
		this.server = server;
	}

	public static CellbreakGame get(MinecraftServer server) {
		return GAMES.computeIfAbsent(server, CellbreakGame::new);
	}

	public static void remove(MinecraftServer server) {
		GAMES.remove(server);
	}

	public boolean isRunning() {
		return running;
	}

	public PrisonLayout layout() {
		return PrisonState.get(server).getLayout();
	}

	public ServerWorld world() {
		return server.getOverworld();
	}

	public PlayerData data(PlayerEntity player) {
		return players.get(player.getUuid());
	}

	public boolean isPrisoner(PlayerEntity player) {
		PlayerData d = data(player);
		return running && d != null && d.role == Role.PRISONER && !d.escaped;
	}

	public boolean isGuard(PlayerEntity player) {
		PlayerData d = data(player);
		return running && d != null && d.role == Role.GUARD;
	}

	public boolean isWanted(PlayerEntity player) {
		PlayerData d = data(player);
		return isPrisoner(player) && d.wanted && d.solitaryUntil == 0;
	}

	public Phase phase() {
		return (Phase) SCHEDULE[scheduleIndex][0];
	}

	// ------------------------------------------------------------------ lobby

	public void setRole(ServerPlayerEntity player, Role role) {
		PlayerData d = players.get(player.getUuid());
		if (d == null) {
			players.put(player.getUuid(), new PlayerData(role));
		} else {
			d.role = role;
		}
	}

	public boolean leave(ServerPlayerEntity player) {
		PlayerData d = players.remove(player.getUuid());
		if (d != null && running) {
			restore(player, d);
			PrisonState.get(server).roundModes().remove(player.getUuid());
			PrisonState.get(server).markDirty();
		}
		return d != null;
	}

	/** A player joined: if a round they were in was cut short by a restart, give them their game back. */
	public void onJoin(ServerPlayerEntity p) {
		PrisonState state = PrisonState.get(server);
		GameMode previous = state.roundModes().get(p.getUuid());
		if (previous == null || running && players.containsKey(p.getUuid())) {
			return;
		}
		state.roundModes().remove(p.getUuid());
		state.markDirty();
		p.getInventory().clear();
		p.clearStatusEffects();
		p.changeGameMode(previous);
		p.sendMessage(Text.literal("Прошлый раунд прервался, твой режим игры восстановлен.").formatted(Formatting.GRAY));
	}

	public Map<UUID, PlayerData> participants() {
		return players;
	}

	// ------------------------------------------------------------------ round lifecycle

	public String start(int minutes, int npcGuards) {
		PrisonLayout layout = layout();
		if (layout == null) {
			return "Сначала постройте тюрьму (/cellbreak build) или отметьте её на карте (/cellbreak map ...).";
		}
		if (running) {
			return "Раунд уже идёт.";
		}
		if (!layout.missing().isEmpty()) {
			return "Тюрьма отмечена не до конца. Не хватает: " + String.join(", ", layout.missing());
		}
		List<ServerPlayerEntity> prisoners = online(Role.PRISONER);
		List<ServerPlayerEntity> guards = online(Role.GUARD);
		if (prisoners.size() > layout.cells.size()) {
			return "Слишком много заключённых: камер всего " + layout.cells.size() + ".";
		}

		running = true;
		ticks = 0;
		endTick = minutes * 60L * 20L;
		npcGuardCount = npcGuards >= 0 ? npcGuards : Math.max(guards.isEmpty() ? 2 : 0, prisoners.size() - guards.size() + 1);

		ServerWorld world = world();
		world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, server);
		world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
		world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
		world.setWeather(12000, 0, false, false);

		int cell = 0;
		for (ServerPlayerEntity p : prisoners) {
			PlayerData d = players.get(p.getUuid());
			d.cell = cell++;
			d.heat = 0;
			d.escaped = false;
			d.wanted = false;
			d.solitaryUntil = 0;
			d.previousMode = p.interactionManager.getGameMode();
			prepare(p, GameMode.ADVENTURE);
			givePrisonerKit(p);
			teleport(p, layout.cells.get(d.cell).spawn);
		}
		for (ServerPlayerEntity p : guards) {
			PlayerData d = players.get(p.getUuid());
			d.previousMode = p.interactionManager.getGameMode();
			prepare(p, GameMode.ADVENTURE);
			giveGuardKit(p);
			teleport(p, layout.guardSpawn);
		}
		// Offline participants are dropped from the round.
		players.entrySet().removeIf(e -> server.getPlayerManager().getPlayer(e.getKey()) == null);
		PrisonState state = PrisonState.get(server);
		state.roundModes().clear();
		players.forEach((id, d) -> state.roundModes().put(id, d.previousMode));
		state.markDirty();

		removeNpcGuards();
		for (int i = 0; i < npcGuardCount; i++) {
			spawnNpcGuard();
		}
		for (PrisonLayout.Cell c : layout.cells) {
			fillChest(c.desk, 3);
		}
		refillScrap();
		setDoor(layout.guardRoomDoor, false);
		setDoor(layout.solitaryDoor, false);

		scheduleIndex = 0;
		beginPhase();
		broadcast(Text.literal("Раунд начался! Заключённых: " + prisoners.size() + ", охранников: "
				+ guards.size() + " (+" + npcGuardCount + " NPC). Длительность: " + minutes + " мин.")
				.formatted(Formatting.GOLD));
		return null;
	}

	public void stop(String reason) {
		if (!running) {
			return;
		}
		running = false;
		removeNpcGuards();
		for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
			if (p != null) {
				restore(p, e.getValue());
				title(p, Text.literal("Раунд окончен").formatted(Formatting.GOLD), Text.literal(reason));
			}
		}
		broadcast(Text.literal("Раунд окончен: " + reason).formatted(Formatting.GOLD));
		PrisonState.get(server).roundModes().clear();
		PrisonState.get(server).markDirty();
		world().getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
	}

	private void finishByTime() {
		List<String> escaped = new ArrayList<>();
		int prisoners = 0;
		for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
			if (e.getValue().role == Role.PRISONER) {
				prisoners++;
				if (e.getValue().escaped) {
					escaped.add(name(e.getKey()));
				}
			}
		}
		if (escaped.isEmpty()) {
			stop("время вышло, никто не сбежал. Победила охрана!");
		} else {
			stop("время вышло. Сбежали " + escaped.size() + " из " + prisoners + ": " + String.join(", ", escaped)
					+ (escaped.size() * 2 >= prisoners ? ". Победили заключённые!" : ". Победила охрана!"));
		}
	}

	// ------------------------------------------------------------------ tick

	public void tick() {
		if (!running) {
			return;
		}
		ticks++;
		if (ticks >= endTick) {
			finishByTime();
			return;
		}
		if (--phaseTicksLeft <= 0) {
			endPhase();
			scheduleIndex = (scheduleIndex + 1) % SCHEDULE.length;
			beginPhase();
		}
		if (ticks % 10 == 0) {
			updatePlayers();
		}
		if (ticks % 20 == 0) {
			updateHud();
		}
		if (ticks % (30 * 20) == 0) {
			replaceMissingGuards();
		}
	}

	/** Ends the current part of the routine right now (host command). */
	public void skipPhase() {
		if (running) {
			phaseTicksLeft = 1;
		}
	}

	private void beginPhase() {
		Phase phase = phase();
		PrisonLayout layout = layout();
		phaseTicksLeft = (Integer) SCHEDULE[scheduleIndex][1] * 20;
		world().setTimeOfDay(phase.timeOfDay);
		for (PrisonLayout.Cell c : layout.cells) {
			setDoor(c.door, phase != Phase.LIGHTS_OUT);
		}
		for (BlockPos door : layout.dayDoors) {
			setDoor(door, phase != Phase.LIGHTS_OUT);
		}
		for (PlayerData d : players.values()) {
			d.attended = false;
		}
		if (phase == Phase.MEAL) {
			refillScrap();
		}
		if (scheduleIndex == 0 && ticks > 1) {
			for (PrisonLayout.Cell c : layout.cells) {
				fillChest(c.desk, 2);
			}
		}
		for (ServerPlayerEntity p : allOnline()) {
			title(p, Text.literal(phase.title).formatted(Formatting.YELLOW), Text.literal(phase.hint));
			p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1.0F, 1.0F);
		}
	}

	private void endPhase() {
		Phase phase = phase();
		if (phase != Phase.ROLL_CALL && phase != Phase.MEAL) {
			return;
		}
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.escaped || d.solitaryUntil != 0 || d.attended) {
				continue;
			}
			addHeat(p, 40);
			p.sendMessage(Text.literal("Ты пропустил: " + phase.title + ". Подозрение +40").formatted(Formatting.RED));
			for (ServerPlayerEntity g : online(Role.GUARD)) {
				g.sendMessage(Text.literal(p.getName().getString() + " пропустил: " + phase.title)
						.formatted(Formatting.RED));
			}
		}
	}

	private void updatePlayers() {
		PrisonLayout layout = layout();
		Phase phase = phase();
		ServerWorld world = world();
		List<GuardEntity> npcs = npcGuards();
		List<ServerPlayerEntity> playerGuards = online(Role.GUARD);
		int phaseElapsed = (Integer) SCHEDULE[scheduleIndex][1] * 20 - phaseTicksLeft;

		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.escaped) {
				continue;
			}
			if (p.getServerWorld() == world && layout.isEscaped(p.getBlockPos())) {
				escape(p, d);
				continue;
			}
			if (d.solitaryUntil != 0) {
				if (ticks >= d.solitaryUntil) {
					d.solitaryUntil = 0;
					teleport(p, layout.cells.get(d.cell).spawn);
					p.sendMessage(Text.literal("Тебя выпустили из карцера.").formatted(Formatting.GREEN));
				}
				continue;
			}

			BlockPos pos = p.getBlockPos();
			if (phase == Phase.ROLL_CALL && layout.rollCallZone.contains(pos)
					|| phase == Phase.MEAL && layout.canteenZone.contains(pos)) {
				if (!d.attended && phase == Phase.MEAL) {
					p.giveItemStack(new ItemStack(Items.BREAD, 2));
				}
				d.attended = true;
			}

			boolean seen = isSeen(p, npcs, playerGuards);
			d.heatRaised = false;
			if (seen && holdsContraband(p)) {
				addHeat(p, 5);
				if (!d.warnedContraband) {
					d.warnedContraband = true;
					p.sendMessage(Text.literal("Охрана видит у тебя запрещённый предмет!").formatted(Formatting.RED));
				}
			} else {
				d.warnedContraband = false;
			}
			if (phase == Phase.LIGHTS_OUT && phaseElapsed > 15 * 20 && seen
					&& !layout.cells.get(d.cell).interior.contains(pos)) {
				addHeat(p, 4);
			}
			if (d.wanted) {
				if (seen) {
					d.lastSeenTick = ticks;
				} else if (ticks - d.lastSeenTick > LOSE_PURSUIT_SECONDS * 20L) {
					d.wanted = false;
					d.heat = 60;
					p.sendMessage(Text.literal("Ты оторвался от охраны. Пока...").formatted(Formatting.GREEN));
				}
			} else if (!d.heatRaised && d.heat > 0) {
				d.heat = Math.max(0, d.heat - 0.5);
			}
			if (isWanted(p)) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
			}
		}
	}

	private void updateHud() {
		int secs = phaseTicksLeft / 20;
		long roundLeft = (endTick - ticks) / 20;
		String clock = String.format("%d:%02d", secs / 60, secs % 60);
		String round = String.format("%d:%02d", roundLeft / 60, roundLeft % 60);
		List<String> wanted = new ArrayList<>();
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			if (isWanted(p)) {
				wanted.add(p.getName().getString());
			}
		}
		for (ServerPlayerEntity p : allOnline()) {
			PlayerData d = data(p);
			Text hud;
			if (d.role == Role.PRISONER) {
				if (d.escaped) {
					hud = Text.literal("Ты на свободе! До конца раунда " + round).formatted(Formatting.GREEN);
				} else if (d.solitaryUntil != 0) {
					hud = Text.literal("Карцер: ещё " + (d.solitaryUntil - ticks) / 20 + " с").formatted(Formatting.RED);
				} else {
					hud = Text.literal(phase().title + " " + clock).formatted(Formatting.YELLOW)
							.append(Text.literal("  |  Подозрение: ").formatted(Formatting.GRAY))
							.append(heatBar(d.heat))
							.append(Text.literal("  |  Раунд " + round).formatted(Formatting.GRAY));
				}
			} else {
				hud = Text.literal(phase().title + " " + clock).formatted(Formatting.YELLOW)
						.append(Text.literal("  |  Раунд " + round).formatted(Formatting.GRAY))
						.append(wanted.isEmpty() ? Text.empty()
								: Text.literal("  |  Розыск: " + String.join(", ", wanted)).formatted(Formatting.RED));
			}
			p.sendMessage(hud, true);
		}
	}

	private static Text heatBar(double heat) {
		int filled = (int) Math.round(heat / 10);
		Formatting color = heat >= MAX_HEAT ? Formatting.DARK_RED : heat >= 60 ? Formatting.RED
				: heat >= 30 ? Formatting.GOLD : Formatting.GREEN;
		return Text.literal("|".repeat(filled)).formatted(color)
				.append(Text.literal("|".repeat(10 - filled)).formatted(Formatting.DARK_GRAY))
				.append(Text.literal(" " + (int) heat + "%").formatted(color));
	}

	// ------------------------------------------------------------------ heat and arrests

	public void addHeat(ServerPlayerEntity p, double amount) {
		PlayerData d = data(p);
		if (d == null || d.role != Role.PRISONER || d.solitaryUntil != 0) {
			return;
		}
		d.heat = MathHelper.clamp(d.heat + amount, 0, MAX_HEAT);
		d.heatRaised = true;
		if (d.wanted) {
			d.lastSeenTick = ticks;
		} else if (d.heat >= MAX_HEAT) {
			d.wanted = true;
			d.lastSeenTick = ticks;
			title(p, Text.literal("Тебя разыскивают!").formatted(Formatting.DARK_RED),
					Text.literal("Охрана идёт за тобой"));
			for (ServerPlayerEntity g : online(Role.GUARD)) {
				g.sendMessage(Text.literal("Тревога! Задержать: " + p.getName().getString()).formatted(Formatting.RED));
			}
		}
	}

	/** Called when a prisoner breaks a prison block. */
	public void onBlockBroken(ServerPlayerEntity p) {
		if (isPrisoner(p) && isSeen(p, npcGuards(), online(Role.GUARD))) {
			addHeat(p, 40);
			p.sendMessage(Text.literal("Охрана заметила, как ты ломаешь тюрьму! Подозрение +40")
					.formatted(Formatting.RED));
		}
	}

	/** Called when a prisoner hits any guard. */
	public void onGuardAttacked(ServerPlayerEntity p) {
		if (isPrisoner(p)) {
			addHeat(p, MAX_HEAT);
		}
	}

	/** Handcuffs: search the prisoner and send them to solitary if anything is found. */
	public void handcuff(ServerPlayerEntity guard, ServerPlayerEntity prisoner) {
		PlayerData d = data(prisoner);
		if (d.solitaryUntil != 0) {
			guard.sendMessage(Text.literal("Он и так в карцере."), true);
			return;
		}
		int found = countContraband(prisoner);
		if (found > 0 || d.wanted) {
			arrest(prisoner, "задержан охранником " + guard.getName().getString());
			guard.sendMessage(Text.literal("Задержан! Найдено запрещённых предметов: " + found)
					.formatted(Formatting.GREEN));
		} else {
			guard.sendMessage(Text.literal("Обыск: ничего не найдено.").formatted(Formatting.GRAY));
			prisoner.sendMessage(Text.literal("Тебя обыскали, но ничего не нашли.").formatted(Formatting.GRAY));
		}
	}

	public void arrest(ServerPlayerEntity p, String reason) {
		PlayerData d = data(p);
		if (d == null || d.role != Role.PRISONER || d.escaped) {
			return;
		}
		int removed = removeContraband(p);
		d.heat = 0;
		d.wanted = false;
		d.solitaryUntil = ticks + SOLITARY_SECONDS * 20L;
		p.setHealth(p.getMaxHealth());
		p.extinguish();
		p.removeStatusEffect(StatusEffects.GLOWING);
		teleport(p, layout().solitarySpawn);
		title(p, Text.literal("Карцер").formatted(Formatting.RED),
				Text.literal(reason + ". Изъято предметов: " + removed));
		broadcast(Text.literal(p.getName().getString() + " отправлен в карцер (" + reason + ")")
				.formatted(Formatting.GRAY));
	}

	/** A participant would die: knock them out instead. Returns true if the death was handled. */
	public boolean onFatalDamage(ServerPlayerEntity p) {
		PlayerData d = data(p);
		if (!running || d == null || d.escaped) {
			return false;
		}
		p.setHealth(p.getMaxHealth());
		if (d.role == Role.PRISONER) {
			arrest(p, "потерял сознание");
		} else {
			teleport(p, layout().guardSpawn);
			p.sendMessage(Text.literal("Тебя вырубили. Ты очнулся в комнате охраны.").formatted(Formatting.RED));
		}
		return true;
	}

	private void escape(ServerPlayerEntity p, PlayerData d) {
		d.escaped = true;
		d.heat = 0;
		d.wanted = false;
		p.changeGameMode(GameMode.SPECTATOR);
		long secs = ticks / 20;
		broadcast(Text.literal(p.getName().getString() + " СБЕЖАЛ! (" + secs / 60 + " мин " + secs % 60 + " с)")
				.formatted(Formatting.GREEN, Formatting.BOLD));
		for (ServerPlayerEntity other : allOnline()) {
			other.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0F, 1.0F);
		}
		boolean anyLeft = online(Role.PRISONER).stream().anyMatch(o -> !data(o).escaped);
		if (!anyLeft) {
			stop("все заключённые сбежали. Победили заключённые!");
		}
	}

	private boolean isSeen(ServerPlayerEntity p, List<GuardEntity> npcs, List<ServerPlayerEntity> playerGuards) {
		for (GuardEntity g : npcs) {
			if (g.squaredDistanceTo(p) < GUARD_SIGHT * GUARD_SIGHT && facing(g, p) && g.canSee(p)) {
				return true;
			}
		}
		for (ServerPlayerEntity g : playerGuards) {
			if (g.getServerWorld() == p.getServerWorld() && g.squaredDistanceTo(p) < PLAYER_GUARD_SIGHT * PLAYER_GUARD_SIGHT
					&& facing(g, p) && g.canSee(p)) {
				return true;
			}
		}
		return false;
	}

	private static boolean facing(LivingEntity watcher, Entity target) {
		Vec3d look = watcher.getRotationVec(1.0F);
		Vec3d to = target.getEyePos().subtract(watcher.getEyePos()).normalize();
		return look.dotProduct(to) > 0.25;
	}

	private static boolean holdsContraband(PlayerEntity p) {
		return CbContent.CONTRABAND.contains(p.getMainHandStack().getItem())
				|| CbContent.CONTRABAND.contains(p.getOffHandStack().getItem());
	}

	private static int countContraband(PlayerEntity p) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (CbContent.CONTRABAND.contains(inv.getStack(i).getItem())) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	private static int removeContraband(PlayerEntity p) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (CbContent.CONTRABAND.contains(inv.getStack(i).getItem())) {
				n += inv.getStack(i).getCount();
				inv.setStack(i, ItemStack.EMPTY);
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ NPC guards

	public List<GuardEntity> npcGuards() {
		PrisonLayout layout = layout();
		BlockBox b = layout.bounds();
		Box area = new Box(b.getMinX() - 16, world().getBottomY(), b.getMinZ() - 16, b.getMaxX() + 16,
				world().getTopY(), b.getMaxZ() + 16);
		return world().getEntitiesByClass(GuardEntity.class, area, Entity::isAlive);
	}

	private void spawnNpcGuard() {
		GuardEntity guard = CbContent.GUARD.create(world());
		if (guard == null) {
			return;
		}
		// The first guard starts at the guard post, the others spread over the patrol points
		// (on maps with several floors not every place is reachable on foot).
		List<BlockPos> points = layout().patrolPoints;
		BlockPos pos = npcGuards().isEmpty() || points.isEmpty() ? layout().npcGuardSpawn
				: points.get(world().random.nextInt(points.size()));
		guard.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
		guard.initialize(world(), world().getLocalDifficulty(pos), SpawnReason.EVENT, null);
		world().spawnEntity(guard);
	}

	private void removeNpcGuards() {
		if (layout() == null) {
			return;
		}
		for (GuardEntity g : npcGuards()) {
			g.discard();
		}
	}

	private void replaceMissingGuards() {
		int missing = npcGuardCount - npcGuards().size();
		for (int i = 0; i < missing; i++) {
			spawnNpcGuard();
		}
	}

	// ------------------------------------------------------------------ helpers

	private void prepare(ServerPlayerEntity p, GameMode mode) {
		p.changeGameMode(mode);
		p.getInventory().clear();
		p.clearStatusEffects();
		p.setHealth(p.getMaxHealth());
		p.getHungerManager().setFoodLevel(20);
	}

	private void restore(ServerPlayerEntity p, PlayerData d) {
		p.getInventory().clear();
		p.clearStatusEffects();
		p.setHealth(p.getMaxHealth());
		p.changeGameMode(d.previousMode != null ? d.previousMode : GameMode.SURVIVAL);
		d.escaped = false;
		d.wanted = false;
		d.solitaryUntil = 0;
		d.heat = 0;
	}

	private void givePrisonerKit(ServerPlayerEntity p) {
		p.equipStack(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, PRISONER_ORANGE));
		p.equipStack(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, PRISONER_ORANGE));
		p.giveItemStack(new ItemStack(Items.BREAD, 3));
	}

	private void giveGuardKit(ServerPlayerEntity p) {
		p.equipStack(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, GUARD_NAVY));
		p.equipStack(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, GUARD_NAVY));
		p.equipStack(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, GUARD_NAVY));
		p.equipStack(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
		p.giveItemStack(new ItemStack(CbContent.BATON));
		p.giveItemStack(new ItemStack(CbContent.HANDCUFFS));
		p.giveItemStack(new ItemStack(CbContent.CELL_KEY));
		p.giveItemStack(new ItemStack(Items.COOKED_BEEF, 8));
	}

	private static ItemStack dyed(Item item, int color) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(color, false));
		stack.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
		return stack;
	}

	private static final Item[] SCRAP = { Items.STICK, Items.STICK, Items.STICK, Items.STRING, Items.STRING,
			Items.STRING, Items.FLINT, Items.FLINT, Items.IRON_NUGGET, Items.IRON_NUGGET, Items.IRON_NUGGET,
			Items.PAPER, Items.PAPER, Items.CLAY_BALL, Items.BREAD };

	private void refillScrap() {
		for (BlockPos pos : layout().scrapChests) {
			fillChest(pos, 5);
		}
	}

	private void fillChest(BlockPos pos, int stacks) {
		if (pos == null || !(world().getBlockEntity(pos) instanceof Inventory chest)) {
			return;
		}
		Random random = world().random;
		chest.clear();
		for (int i = 0; i < stacks; i++) {
			Item item = SCRAP[random.nextInt(SCRAP.length)];
			chest.setStack(random.nextInt(chest.size()), new ItemStack(item, 1 + random.nextInt(2)));
		}
	}

	private void setDoor(BlockPos pos, boolean open) {
		if (pos == null) {
			return;
		}
		BlockState state = world().getBlockState(pos);
		if (state.isOf(Blocks.IRON_DOOR)) {
			((DoorBlock) state.getBlock()).setOpen(null, world(), state, pos, open);
		}
	}

	private void teleport(ServerPlayerEntity p, BlockPos pos) {
		p.teleport(world(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p.getYaw(), p.getPitch());
	}

	private static void title(ServerPlayerEntity p, Text title, Text subtitle) {
		p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
		p.networkHandler.sendPacket(new TitleS2CPacket(title));
	}

	private void broadcast(Text text) {
		server.getPlayerManager().broadcast(text, false);
	}

	private String name(UUID id) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
		return p != null ? p.getName().getString() : id.toString().substring(0, 8);
	}

	private List<ServerPlayerEntity> online(Role role) {
		List<ServerPlayerEntity> list = new ArrayList<>();
		for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
			if (p != null && e.getValue().role == role) {
				list.add(p);
			}
		}
		return list;
	}

	private List<ServerPlayerEntity> allOnline() {
		List<ServerPlayerEntity> list = new ArrayList<>();
		for (UUID id : players.keySet()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	public long ticks() {
		return ticks;
	}
}
