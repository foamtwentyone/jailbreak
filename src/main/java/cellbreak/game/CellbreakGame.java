package cellbreak.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cellbreak.CbContent;
import cellbreak.block.DetectorBlock;
import cellbreak.block.FakeBlock;
import cellbreak.block.GraffitiBlock;
import cellbreak.entity.CookEntity;
import cellbreak.entity.GuardEntity;
import cellbreak.entity.PrisonerNpcEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;

/**
 * One prison round: roles, the daily routine, suspicion ("heat"), searches, work and money,
 * snipers, damaged walls the guards notice, arrests and escapes.
 */
public class CellbreakGame {
	public enum Role {
		PRISONER("заключённый"), GUARD("охранник"), RANDOM("случайная роль"), SPECTATOR("зритель");

		public final String title;

		Role(String title) {
			this.title = title;
		}
	}

	/** Where prisoners must be during a part of the routine. */
	public enum Zone {
		ROLL_CALL, CANTEEN, WORK, YARD, SHOWER, CELLS, ANYWHERE
	}

	public enum Phase {
		WAKE_UP("Подъём", "Просыпайтесь! Через несколько секунд утренняя перекличка.", 23500, Zone.ANYWHERE),
		ROLL_CALL("Утренняя перекличка", "Все в зону переклички! Охрана будет обыскивать.", 0, Zone.ROLL_CALL),
		BREAKFAST("Завтрак", "Все в столовую!", 1000, Zone.CANTEEN),
		WORK("Работа", "На рабочее поле! Добывай руду и брёвна, сдавай на столе в мастерской.", 3000, Zone.WORK),
		FREE_TIME("Свободное время", "Можно ходить где угодно (кроме улицы ночью).", 6000, Zone.ANYWHERE),
		EXERCISE("Прогулка", "Все на прогулочный двор!", 7000, Zone.YARD),
		SHOWER("Душ", "Все в душевую!", 9000, Zone.SHOWER),
		DINNER("Ужин", "Все в столовую!", 11000, Zone.CANTEEN),
		EVENING_FREE("Свободное время", "Можно ходить где угодно.", 12000, Zone.ANYWHERE),
		EVENING_ROLL_CALL("Вечерняя перекличка", "Все в зону переклички! Охрана будет обыскивать.", 12800, Zone.ROLL_CALL),
		LIGHTS_OUT("Отбой", "Все по камерам! Через 25 секунд двери запрут. На улице стреляют снайперы.", 18000, Zone.CELLS);

		public final String title;
		public final String hint;
		public final long timeOfDay;
		public final Zone zone;

		Phase(String title, String hint, long timeOfDay, Zone zone) {
			this.title = title;
			this.hint = hint;
			this.timeOfDay = timeOfDay;
			this.zone = zone;
		}

		boolean isRollCall() {
			return zone == Zone.ROLL_CALL;
		}
	}

	/** The daily routine, in seconds: about 12.5 minutes per day. */
	private static final Object[][] SCHEDULE = {
			{ Phase.WAKE_UP, 12 }, { Phase.ROLL_CALL, 50 }, { Phase.BREAKFAST, 60 }, { Phase.WORK, 120 }, { Phase.FREE_TIME, 90 },
			{ Phase.EXERCISE, 60 }, { Phase.SHOWER, 45 }, { Phase.DINNER, 60 }, { Phase.EVENING_FREE, 60 },
			{ Phase.EVENING_ROLL_CALL, 50 }, { Phase.LIGHTS_OUT, 150 },
	};

	public static final double MAX_HEAT = 100;
	private static final int SOLITARY_SECONDS = 60;
	/** A wanted prisoner nobody has seen for this long gets away. */
	private static final int LOSE_PURSUIT_SECONDS = 20;
	/** Time to get to the right place after a bell before snipers and checks care. */
	private static final int GRACE_SECONDS = 25;
	private static final int GUARD_SIGHT = 16;
	private static final int PLAYER_GUARD_SIGHT = 24;
	private static final int SNIPER_RANGE = 96;
	private static final int SNIPER_AIM_TICKS = 40;
	private static final int SEARCHED_AT_ROLL_CALL = 4;
	/** Roll call: the counting guards start walking the line-up, then announce the count and the search. */
	private static final int COUNT_START_SECONDS = 20;
	private static final int COUNT_END_SECONDS = 36;
	private static final int SEARCH_SECONDS = 42;
	private static final int INFIRMARY_SECONDS = 30;
	/** How long a suspicious act outdoors (breaking, placing a fake, graffiti) keeps a day sniper's eye on you. */
	private static final int SUSPICIOUS_TICKS = 60;
	private static final double SEARCHLIGHT_RADIUS = 3.5;
	private static final int ORE_REGEN_SECONDS = 60;
	private static final int LOG_REGEN_SECONDS = 90;
	private static final int MAX_TRADERS = 3;
	private static final int MAX_NPC_PRISONERS = 16;
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
		long lastSeenTick = -10000;
		BlockPos lastSeenPos;
		long sniperSpotTick;
		long detectorQuietUntil;
		/** The HUD line waits while a more important action bar message is shown. */
		long hudQuietUntil;
		public GameMode previousMode;
		boolean heatRaised;
		boolean warnedContraband;
		public long infirmaryUntil;
		long lastScoldTick = -10000;
		/** Caught in somebody else's cell: since when, and which guard waits for them to leave. */
		long foreignCellSince;
		GuardEntity foreignCellGuard;
		long suspiciousTick = -10000;
		long lastFightTick = -10000;
		long lastTalkTick = -10000;
		BlockPos lineupSlot;

		PlayerData(Role role) {
			this.role = role;
		}
	}

	private static final Map<MinecraftServer, CellbreakGame> GAMES = new HashMap<>();

	private final MinecraftServer server;
	private final Map<UUID, PlayerData> players = new HashMap<>();
	/** Blocks broken by prisoners and what was there before; guards repair what they notice. */
	private final Map<BlockPos, BlockState> damage = new HashMap<>();
	private boolean running;
	private long ticks;
	private long endTick;
	private int scheduleIndex;
	private int phaseTicksLeft;
	private boolean searchDone;
	private boolean countDone;
	/** What happened since the last roll call, for the guards' news. */
	private int holesFound;
	private int graffitiFound;
	private int fightsSeen;
	private int scannerAlarms;
	private int npcGuardCount;
	private List<String> nicknames = List.of(PrisonerNpcEntity.NICKNAMES);
	/** Ore and logs taken on the work field, and when they grow back. */
	private final Map<BlockPos, Regrowth> regrowth = new HashMap<>();
	/** Chalk drawings and who drew them. */
	private final Map<BlockPos, UUID> graffiti = new HashMap<>();
	/** Night searchlights of the snipers, by sniper. */
	private final Map<UUID, Searchlight> searchlights = new HashMap<>();
	private boolean countStarted;
	private boolean doorsLocked;
	private boolean cleanedLeftovers;
	private List<BlockPos> lineup = List.of();
	private BlockPos lineupLook;
	private GuardEntity countingGuard;

	private record Regrowth(BlockState state, long at) {
	}

	private static class Searchlight {
		BlockPos light;
		Vec3d spot;
		Vec3d goal;
		UUID lockedOn;
		/** When the current goal was chosen: a new one comes after a few seconds anyway. */
		long goalSince;
		/** Updates left at double speed (rushing to a noise). */
		int rush;
	}

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

	private int phaseElapsed() {
		return (Integer) SCHEDULE[scheduleIndex][1] * 20 - phaseTicksLeft;
	}

	private BlockBox zoneBox(Zone zone) {
		PrisonLayout l = layout();
		return switch (zone) {
			case ROLL_CALL -> l.rollCallZone;
			case CANTEEN -> l.canteenZone;
			case WORK -> l.workZone;
			case YARD -> l.yardZone;
			case SHOWER -> l.showerZone;
			default -> null;
		};
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

	/** Clickable role choice in chat. */
	public void sendRoleMenu(ServerPlayerEntity p) {
		MutableText msg = Text.literal("Выбери сторону: ").formatted(Formatting.YELLOW)
				.append(button("[Заключённый]", "/cellbreak join prisoner", Formatting.GOLD))
				.append(Text.literal(" "))
				.append(button("[Охранник]", "/cellbreak join guard", Formatting.BLUE))
				.append(Text.literal(" "))
				.append(button("[Случайно]", "/cellbreak join random", Formatting.GREEN))
				.append(Text.literal(" "))
				.append(button("[Смотреть]", "/cellbreak join spectator", Formatting.GRAY));
		p.sendMessage(msg, false);
	}

	private static Text button(String label, String command, Formatting color) {
		return Text.literal(label).styled(s -> s.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}

	/** A player joined: if a round they were in was cut short by a restart, give them their game back. */
	public void onJoin(ServerPlayerEntity p) {
		PrisonState state = PrisonState.get(server);
		GameMode previous = state.roundModes().get(p.getUuid());
		if (previous != null && !(running && players.containsKey(p.getUuid()))) {
			state.roundModes().remove(p.getUuid());
			state.markDirty();
			p.getInventory().clear();
			p.clearStatusEffects();
			p.changeGameMode(previous);
			p.sendMessage(Text.literal("Прошлый раунд прервался, твой режим игры восстановлен.").formatted(Formatting.GRAY));
		}
		// The side-choice buttons come on the first visit only (and after every round); later the
		// player can bring them back with /cellbreak menu.
		if (!running && layout() != null && state.menuShown().add(p.getUuid())) {
			state.markDirty();
			sendRoleMenu(p);
		}
	}

	public Map<UUID, PlayerData> participants() {
		return players;
	}

	/**
	 * Everyone online who did not pick a side gets a random one, like those who picked "random":
	 * about one guard for every three prisoners (a guard is always there if two or more play).
	 */
	private void assignRandomRoles() {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			players.putIfAbsent(p.getUuid(), new PlayerData(Role.RANDOM));
		}
		List<PlayerData> random = new ArrayList<>();
		int total = 0;
		int guards = 0;
		for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
			if (server.getPlayerManager().getPlayer(e.getKey()) == null || e.getValue().role == Role.SPECTATOR) {
				continue;
			}
			total++;
			if (e.getValue().role == Role.RANDOM) {
				random.add(e.getValue());
			} else if (e.getValue().role == Role.GUARD) {
				guards++;
			}
		}
		int wantedGuards = total < 2 ? 0 : Math.max(1, Math.round(total / 4.0F));
		Collections.shuffle(random);
		for (PlayerData d : random) {
			d.role = guards < wantedGuards ? Role.GUARD : Role.PRISONER;
			if (d.role == Role.GUARD) {
				guards++;
			}
		}
	}

	// ------------------------------------------------------------------ round lifecycle

	public String start(int minutes, int npcGuards, int npcPrisoners) {
		PrisonLayout layout = layout();
		if (layout == null) {
			return "Тюрьма не отмечена на карте. Откройте карту MPE Cellbreak или разметьте свою (/cellbreak map ...).";
		}
		if (running) {
			return "Раунд уже идёт.";
		}
		if (!layout.missing().isEmpty()) {
			return "Тюрьма отмечена не до конца. Не хватает: " + String.join(", ", layout.missing());
		}
		assignRandomRoles();
		List<ServerPlayerEntity> prisoners = online(Role.PRISONER);
		List<ServerPlayerEntity> guards = online(Role.GUARD);
		if (prisoners.size() > layout.cells.size()) {
			return "Слишком много заключённых: камер всего " + layout.cells.size() + ".";
		}

		running = true;
		ticks = 0;
		endTick = minutes * 60L * 20L;
		regrowth.clear();
		graffiti.clear();
		searchlights.clear();
		cleanLeftovers();
		npcGuardCount = npcGuards >= 0 ? npcGuards : Math.max(guards.isEmpty() ? 2 : 1, (prisoners.size() + 2) / 3 - guards.size() + 1);
		damage.clear();

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
			title(p, Text.literal("Ты — заключённый").formatted(Formatting.GOLD), Text.literal("Сбеги из тюрьмы!"));
		}
		for (ServerPlayerEntity p : guards) {
			PlayerData d = players.get(p.getUuid());
			d.previousMode = p.interactionManager.getGameMode();
			prepare(p, GameMode.ADVENTURE);
			giveGuardKit(p);
			teleport(p, layout.guardSpawn);
			title(p, Text.literal("Ты — охранник").formatted(Formatting.BLUE), Text.literal("Никого не выпускай!"));
		}
		// Offline participants and spectators are dropped from the round.
		players.entrySet().removeIf(e -> server.getPlayerManager().getPlayer(e.getKey()) == null
				|| e.getValue().role == Role.SPECTATOR || e.getValue().role == Role.RANDOM);
		PrisonState state = PrisonState.get(server);
		state.roundModes().clear();
		players.forEach((id, d) -> state.roundModes().put(id, d.previousMode));
		state.markDirty();

		removeNpcs();
		for (int i = 0; i < npcGuardCount; i++) {
			spawnNpcGuard();
		}
		for (BlockPos post : layout.sniperPosts) {
			spawnSniper(post);
		}
		nicknames = new ArrayList<>(List.of(PrisonerNpcEntity.NICKNAMES));
		Collections.shuffle(nicknames);
		int npcCount = 0;
		int maxNpc = npcPrisoners >= 0 ? npcPrisoners : MAX_NPC_PRISONERS;
		for (int i = cell; i < layout.cells.size() && npcCount < maxNpc; i++, npcCount++) {
			spawnNpcPrisoner(i, npcCount);
		}
		for (PrisonLayout.Cell c : layout.cells) {
			fillChest(c.desk, 3);
		}
		refillScrap();
		spawnCooks();
		setDoor(layout.guardRoomDoor, false);
		setDoor(layout.solitaryDoor, false);

		scheduleIndex = 0;
		beginPhase();
		broadcast(Text.literal("Раунд начался (Cellbreak " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("cellbreak")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + ")! Заключённых: " + prisoners.size() + " (+" + npcCount + " NPC), охранников: "
				+ guards.size() + " (+" + npcGuardCount + " NPC, " + layout.sniperPosts.size() + " снайперов). Длительность: "
				+ minutes + " мин.").formatted(Formatting.GOLD));
		return null;
	}

	public void stop(String reason) {
		if (!running) {
			return;
		}
		running = false;
		removeNpcs();
		repairAll();
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
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			sendRoleMenu(p);
		}
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
		if (!cleanedLeftovers) {
			cleanedLeftovers = true;
			cleanLeftovers();
		}
		if (layout() != null && server.getTicks() % 3 == 0) {
			runShowers();
		}
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
			nextPhase();
			beginPhase();
		}
		if (phase().isRollCall()) {
			if (!countStarted && phaseElapsed() >= COUNT_START_SECONDS * 20) {
				countStarted = true;
				startCounting();
			}
			if (!countDone && phaseElapsed() >= COUNT_END_SECONDS * 20) {
				countDone = true;
				announceCountAndNews();
			}
			if (!searchDone && phaseElapsed() >= SEARCH_SECONDS * 20) {
				searchDone = true;
				rollCallSearch();
			}
			if (ticks % 20 == 0) {
				showLineupSlots();
			}
		}
		if (phase() == Phase.LIGHTS_OUT && ticks % 4 == 0) {
			moveSearchlights();
		}
		if (phase() == Phase.LIGHTS_OUT && ticks % 10 == 0 && everyoneAsleep()) {
			// Every prisoner is asleep: the night passes.
			for (ServerPlayerEntity p : online(Role.PRISONER)) {
				if (p.isSleeping()) {
					p.wakeUp(false, true);
				}
			}
			broadcast(Text.literal("Все спят... Ночь проходит быстро.").formatted(Formatting.GRAY));
			endPhase();
			nextPhase();
			beginPhase();
		}
		if (phase() == Phase.LIGHTS_OUT && !doorsLocked && phaseElapsed() >= GRACE_SECONDS * 20) {
			doorsLocked = true;
			lockUpForTheNight();
		}
		// Every tick: a running prisoner crosses an arch in a fraction of a second.
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (!d.escaped && d.solitaryUntil == 0) {
				checkDetector(p, d);
			}
		}
		checkThrownContraband();
		soundSirens();
		if (ticks % 2 == 0) {
			scanNpcs();
		}
		if (ticks % 10 == 0) {
			runEscorts();
		}
		if (ticks % 10 == 0) {
			updatePlayers();
		}
		if (phase() == Phase.SHOWER && ticks % 20 == 0) {
			washAnimations();
		}
		if (phase().zone == Zone.CANTEEN && ticks % 20 == 0) {
			serveQueue();
			eatAnimations();
		}
		if (ticks % 20 == 0) {
			updateHud();
			guardsInspectDamage();
			regrow(false);
		}
		if (ticks % (30 * 20) == 0) {
			replaceMissingGuards();
		}
		if (ticks % (10 * 20) == 0) {
			npcBrawls();
		}
	}

	/** Ends the current part of the routine right now (host command). */
	public void skipPhase() {
		if (running) {
			phaseTicksLeft = 1;
		}
	}

	/** Jumps straight to a part of the routine (host command). Returns false if it is not on this map. */
	public boolean jumpTo(Phase target) {
		if (!running) {
			return false;
		}
		for (int i = 0; i < SCHEDULE.length; i++) {
			if (SCHEDULE[i][0] == target && (target.zone == Zone.ANYWHERE || target.zone == Zone.CELLS
					|| zoneBox(target.zone) != null)) {
				scheduleIndex = i;
				beginPhase();
				return true;
			}
		}
		return false;
	}

	/** Moves to the next part of the routine, skipping parts whose place is not marked on this map. */
	private void nextPhase() {
		for (int i = 0; i < SCHEDULE.length; i++) {
			scheduleIndex = (scheduleIndex + 1) % SCHEDULE.length;
			Zone zone = phase().zone;
			if (zone == Zone.ANYWHERE || zone == Zone.CELLS || zoneBox(zone) != null) {
				return;
			}
		}
	}

	private void beginPhase() {
		Phase phase = phase();
		PrisonLayout layout = layout();
		phaseTicksLeft = (Integer) SCHEDULE[scheduleIndex][1] * 20;
		searchDone = false;
		countDone = false;
		countStarted = false;
		clearSearchlights();
		if (phase != Phase.LIGHTS_OUT) {
			for (PrisonerNpcEntity npc : npcPrisoners()) {
				if (npc.isSleeping()) {
					npc.wakeUp();
				}
			}
		}
		allAsleepSince = 0;
		assignGuardDuties(phase);
		assignLineup(phase);
		startMeal(phase);
		startShower(phase);
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			npc.skipsMeal = phase.zone == Zone.CANTEEN && world().random.nextInt(5) == 0;
		}
		world().setTimeOfDay(phase.timeOfDay);
		// At lights out the doors stay open a little longer: everybody walks to their cell first.
		// At wake-up everything is still locked; doors to the outside open from the walk until the
		// evening roll call.
		boolean outdoors = phase == Phase.EXERCISE || phase == Phase.SHOWER || phase == Phase.DINNER
				|| phase == Phase.EVENING_FREE;
		setDoors(phase != Phase.WAKE_UP, outdoors);
		doorsLocked = false;
		lockServiceDoors();
		for (PlayerData d : players.values()) {
			d.attended = false;
		}
		if (phase == Phase.BREAKFAST || phase == Phase.DINNER) {
			refillScrap();
		}
		if (phase == Phase.WORK) {
			refillSupplies();
		}
		if (phase == Phase.ROLL_CALL && ticks > 1) {
			for (PrisonLayout.Cell c : layout.cells) {
				fillChest(c.desk, 2);
			}
			for (PrisonerNpcEntity npc : npcPrisoners()) {
				npc.restock();
			}
		}
		for (ServerPlayerEntity p : allOnline()) {
			title(p, Text.literal(phase.title).formatted(Formatting.YELLOW), Text.literal(phase.hint));
			p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 1.0F, 1.0F);
		}
	}

	private void endPhase() {
		Phase phase = phase();
		if (phase == Phase.WORK) {
			collectWorkTools();
			// Now and then an inmate "forgets" to hand the work pickaxe back and hides it.
			for (PrisonerNpcEntity npc : npcPrisoners()) {
				if (layout().workZone != null && layout().workZone.contains(npc.getBlockPos()) && world().random.nextInt(10) == 0) {
					npc.smugglingTool = true;
				}
			}
		}
		BlockBox zone = zoneBox(phase.zone);
		if (zone == null) {
			return;
		}
		int penalty = phase.isRollCall() ? 40 : 25;
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0 || d.attended) {
				continue;
			}
			addHeat(p, penalty);
			p.sendMessage(Text.literal("Ты пропустил: " + phase.title + ". Подозрение +" + penalty).formatted(Formatting.RED));
			for (ServerPlayerEntity g : online(Role.GUARD)) {
				g.sendMessage(Text.literal(p.getName().getString() + " пропустил: " + phase.title).formatted(Formatting.RED));
			}
		}
	}

	private void updatePlayers() {
		PrisonLayout layout = layout();
		Phase phase = phase();
		ServerWorld world = world();
		List<GuardEntity> npcs = npcGuards();
		List<GuardEntity> snipers = snipers();
		List<ServerPlayerEntity> playerGuards = online(Role.GUARD);

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
			if (d.infirmaryUntil != 0) {
				if (ticks >= d.infirmaryUntil) {
					d.infirmaryUntil = 0;
					p.sendMessage(Text.literal("Тебя выписали из медпункта.").formatted(Formatting.GREEN));
				} else {
					if (layout.infirmaryZone != null && !layout.infirmaryZone.expand(1).contains(p.getBlockPos())) {
						teleport(p, layout.infirmarySpawn);
						p.sendMessage(Text.literal("Лежи, тебе ещё лечиться.").formatted(Formatting.RED), true);
					}
					continue;
				}
			}

			BlockPos pos = p.getBlockPos();
			boolean inPlace = isInPlace(p, d, phase);
			if (inPlace && phase.zone != Zone.ANYWHERE && phase.zone != Zone.CELLS) {
				if (!d.attended) {
					rewardAttendance(p, phase);
				}
				d.attended = true;
			}

			LivingEntity watcher = seenBy(p, npcs, playerGuards);
			boolean seen = watcher != null;
			if (seen) {
				d.lastSeenTick = ticks;
				d.lastSeenPos = pos;
			}
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
			if (phase == Phase.LIGHTS_OUT && doorsLocked && seen && !d.wanted
					&& !layout.cells.get(d.cell).interior.contains(pos)) {
				// Out of the cell after lights out: no warnings, straight to the wanted list.
				if (watcher instanceof GuardEntity g) {
					guardSays(g, "Стоять, " + p.getName().getString() + "! Нарушение отбоя! Тревога!");
				}
				addHeat(p, MAX_HEAT);
			}
			checkForeignCell(p, d, watcher, phase);
			if (seen && !inPlace && !d.wanted && phase.zone != Zone.ANYWHERE && phaseElapsed() > GRACE_SECONDS * 20
					&& ticks - d.lastScoldTick > 10 * 20) {
				scold(watcher, p, d, phase);
			}
			checkSnipers(p, d, snipers, phase);
			if (d.wanted) {
				if (ticks - d.lastSeenTick > LOSE_PURSUIT_SECONDS * 20L) {
					d.wanted = false;
					d.heat = 60;
					p.sendMessage(Text.literal("Ты оторвался от охраны. Пока...").formatted(Formatting.GREEN));
				}
			} else if (!d.heatRaised && d.heat > 0) {
				d.heat = Math.max(0, d.heat - 0.15);
			}
			if (isWanted(p)) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
			}
		}
	}

	private long allAsleepSince;

	/** True once every prisoner player who can sleep has been asleep in bed for three seconds. */
	private boolean everyoneAsleep() {
		List<ServerPlayerEntity> sleepers = new ArrayList<>();
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (!d.escaped && d.solitaryUntil == 0 && d.infirmaryUntil == 0) {
				sleepers.add(p);
			}
		}
		if (sleepers.isEmpty() || !sleepers.stream().allMatch(PlayerEntity::isSleeping)) {
			allAsleepSince = 0;
			return false;
		}
		if (allAsleepSince == 0) {
			allAsleepSince = ticks;
		}
		return ticks - allAsleepSince >= 60;
	}

	/** Beds in the cells (head halves), by cell number. */
	private Map<Integer, BlockPos> cellBeds;

	private BlockPos bedOf(int cell) {
		if (cellBeds == null) {
			cellBeds = new HashMap<>();
			List<PrisonLayout.Cell> cells = layout().cells;
			for (int i = 0; i < cells.size(); i++) {
				BlockBox b = cells.get(i).interior;
				for (BlockPos p : BlockPos.iterate(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ())) {
					BlockState s = world().getBlockState(p);
					if (s.getBlock() instanceof net.minecraft.block.BedBlock
							&& s.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) {
						cellBeds.put(i, p.toImmutable());
						break;
					}
				}
			}
		}
		return cellBeds.get(cell);
	}

	/** An NPC inmate goes to bed (at lights out, in its cell). */
	private void putToBed(PrisonerNpcEntity npc) {
		BlockPos bed = bedOf(npc.cell);
		if (bed != null && !npc.isSleeping()) {
			npc.getNavigation().stop();
			npc.sleep(bed);
		}
	}

	/** Index of the cell (not the prisoner's own) whose inside contains this position, or -1. */
	private int foreignCell(BlockPos pos, int own) {
		List<PrisonLayout.Cell> cells = layout().cells;
		for (int i = 0; i < cells.size(); i++) {
			if (i != own && cells.get(i).interior.contains(pos)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * A guard who sees a prisoner in somebody else's cell orders them out, stands at the door and
	 * waits; every second they stay, suspicion grows.
	 */
	private void checkForeignCell(ServerPlayerEntity p, PlayerData d, LivingEntity watcher, Phase phase) {
		int cell = phase == Phase.LIGHTS_OUT ? -1 : foreignCell(p.getBlockPos(), d.cell);
		if (cell >= 0 && d.foreignCellSince == 0 && watcher == null) {
			// No guard around, but the owner may be at home.
			ownerComplains(p, cell);
		}
		if (cell < 0) {
			if (d.foreignCellSince != 0) {
				if (d.foreignCellGuard != null && d.foreignCellGuard.isAlive()) {
					guardSays(d.foreignCellGuard, "Вот так. И чтобы больше в чужие камеры ни ногой, " + p.getName().getString() + ".");
					d.foreignCellGuard.endCheck();
				}
				d.foreignCellSince = 0;
				d.foreignCellGuard = null;
			}
			return;
		}
		if (d.foreignCellSince == 0) {
			if (watcher == null) {
				return;
			}
			d.foreignCellSince = ticks;
			d.foreignCellGuard = watcher instanceof GuardEntity g ? g : null;
			if (d.foreignCellGuard != null) {
				guardSays(d.foreignCellGuard, "Эй, " + p.getName().getString() + "! Это не твоя камера. Немедленно выйди!");
				d.foreignCellGuard.goCheck(layout().cells.get(cell).door, p.getBlockPos(), 60);
			} else {
				p.sendMessage(Text.literal(watcher.getName().getString() + ": это не твоя камера, немедленно выйди!")
						.formatted(Formatting.BLUE), false);
			}
		}
		// Updated twice a second: +3 suspicion per second while they stay.
		addHeat(p, 1.5);
		ownerComplains(p, cell);
		long stayed = ticks - d.foreignCellSince;
		if (stayed > 0 && stayed % (6 * 20) == 0 && d.foreignCellGuard != null) {
			guardSays(d.foreignCellGuard, "Я жду, " + p.getName().getString() + ". Выходи из камеры!");
		}
	}

	private final Map<UUID, Long> ownerComplainedAt = new HashMap<>();

	/** The NPC whose cell it is sees the player inside: complains, and a rough one may fight. */
	private void ownerComplains(ServerPlayerEntity p, int cell) {
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			if (npc.cell != cell || npc.isSleeping() || npc.squaredDistanceTo(p) > 10 * 10 || !npc.canSee(p)) {
				continue;
			}
			long last = ownerComplainedAt.getOrDefault(npc.getUuid(), -10000L);
			if (ticks - last < 8 * 20) {
				return;
			}
			ownerComplainedAt.put(npc.getUuid(), ticks);
			String[] lines = PrisonerNpcEntity.OWNER_COMPLAINTS[npc.isRough() ? 1 : 0];
			String line = String.format(lines[world().random.nextInt(lines.length)], p.getName().getString());
			npc.getLookControl().lookAt(p);
			Text msg = Text.literal(npc.getName().getString() + ": ").formatted(Formatting.GOLD)
					.append(Text.literal(line).formatted(npc.isRough() ? Formatting.RED : Formatting.YELLOW));
			for (ServerPlayerEntity o : world().getPlayers()) {
				if (o.squaredDistanceTo(npc) < 16 * 16) {
					o.sendMessage(msg, false);
				}
			}
			// A rough owner who already asked once does not ask a third time.
			if (npc.isRough() && last > 0 && ticks - last < 30 * 20 && npc.getTarget() == null) {
				npc.setTarget(p);
			}
			return;
		}
	}

	/** Now and then two inmates get into a fight (a rough one starts it). */
	private void npcBrawls() {
		Phase phase = phase();
		if (phase.zone != Zone.ANYWHERE && phase.zone != Zone.YARD && phase.zone != Zone.CANTEEN || world().random.nextInt(6) != 0) {
			return;
		}
		List<PrisonerNpcEntity> npcs = npcPrisoners();
		Collections.shuffle(npcs);
		for (PrisonerNpcEntity a : npcs) {
			if (!a.isRough() || a.getTarget() != null || a.hasVehicle() || a.solitaryUntil != 0 || a.infirmaryUntil != 0) {
				continue;
			}
			for (PrisonerNpcEntity b : npcs) {
				if (b != a && b.squaredDistanceTo(a) < 5 * 5 && !b.hasVehicle() && b.solitaryUntil == 0 && b.infirmaryUntil == 0) {
					a.pickFightWith(b);
					Text msg = Text.literal(a.getName().getString() + ": ").formatted(Formatting.GOLD).append(Text.literal(
							BRAWL_LINES[world().random.nextInt(BRAWL_LINES.length)].formatted(b.getName().getString()))
							.formatted(Formatting.RED));
					for (ServerPlayerEntity o : world().getPlayers()) {
						if (o.squaredDistanceTo(a) < 16 * 16) {
							o.sendMessage(msg, false);
						}
					}
					return;
				}
			}
		}
	}

	private static final String[] BRAWL_LINES = { "%s, ты на кого смотришь?!", "Это ты мой хлеб взял, %s?!", "Ну всё, %s, держись!",
			"%s, ты мне ещё за вчерашнее должен!", "Отойди от моего места, %s!" };

	/** Whether the prisoner is where the routine wants them now. */
	private boolean isInPlace(ServerPlayerEntity p, PlayerData d, Phase phase) {
		BlockPos pos = p.getBlockPos();
		PrisonLayout layout = layout();
		return switch (phase.zone) {
			case ANYWHERE -> true;
			case CELLS -> layout.cells.get(d.cell).interior.contains(pos);
			case WORK -> layout.workZone.contains(pos) || layout.workshopZone != null && layout.workshopZone.contains(pos);
			default -> {
				BlockBox zone = zoneBox(phase.zone);
				yield zone == null || zone.contains(pos);
			}
		};
	}

	/** Where the prisoner should be: {"where" (в столовой), "whither" (в столовую)}. */
	private static String[] placeName(Phase phase) {
		return switch (phase.zone) {
			case ROLL_CALL -> new String[] { "на перекличке", "на перекличку" };
			case CANTEEN -> new String[] { "в столовой", "в столовую" };
			case WORK -> new String[] { "на работе", "на работу" };
			case YARD -> new String[] { "на прогулке", "на прогулку" };
			case SHOWER -> new String[] { "в душевой", "в душ" };
			case CELLS -> new String[] { "в своей камере", "в камеру" };
			default -> new String[] { "на месте", "на место" };
		};
	}

	// %1$s name, %2$s where, %3$s whither.
	private static final String[] SCOLDS = { "Эй, %1$s! Тебе положено быть %2$s. Бегом!", "%1$s, ты что тут забыл? Марш %3$s!",
			"%1$s! Я кому сказал? Живо %3$s!", "Шевелись, %1$s! Ты должен быть %2$s.",
			"%1$s, ещё раз увижу — пойдёшь в карцер. Быстро %3$s!" };

	/** A guard sees a prisoner who is not where the routine says: shouts at them and remembers it. */
	private void scold(LivingEntity guard, ServerPlayerEntity p, PlayerData d, Phase phase) {
		d.lastScoldTick = ticks;
		String[] place = placeName(phase);
		String line = String.format(SCOLDS[world().random.nextInt(SCOLDS.length)], p.getName().getString(), place[0], place[1]);
		Text msg = Text.literal(guard.getName().getString() + ": ")
				.formatted(Formatting.BLUE).append(Text.literal(line).formatted(Formatting.WHITE));
		for (ServerPlayerEntity other : allOnline()) {
			if (other == p || other.squaredDistanceTo(guard) < 16 * 16) {
				other.sendMessage(msg, false);
			}
		}
		if (guard instanceof GuardEntity g) {
			g.getLookControl().lookAt(p);
		}
		world().playSound(null, guard.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.HOSTILE, 1.0F, 0.7F);
		addHeat(p, 8);
		p.sendMessage(Text.literal("Охрана тобой недовольна: подозрение +8").formatted(Formatting.RED), true);
	}

	private void rewardAttendance(ServerPlayerEntity p, Phase phase) {
		switch (phase) {
			case SHOWER -> {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 1));
				p.sendMessage(Text.literal("Ты помылся. Здоровье восстанавливается.").formatted(Formatting.AQUA), true);
			}
			case EXERCISE -> {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 120, 0));
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 120, 0));
				p.sendMessage(Text.literal("Тренировка: ты сильнее и быстрее на 2 минуты.").formatted(Formatting.AQUA), true);
			}
			default -> {
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
			if (ticks < d.hudQuietUntil) {
				continue;
			}
			Text hud;
			if (d.role == Role.PRISONER) {
				if (d.escaped) {
					hud = Text.literal("Ты на свободе! До конца раунда " + round).formatted(Formatting.GREEN);
				} else if (d.solitaryUntil != 0) {
					hud = Text.literal("Карцер: ещё " + (d.solitaryUntil - ticks) / 20 + " с").formatted(Formatting.RED);
				} else if (d.infirmaryUntil != 0) {
					hud = Text.literal("Медпункт: ещё " + (d.infirmaryUntil - ticks) / 20 + " с").formatted(Formatting.RED);
				} else {
					hud = Text.literal(phase().title + " " + clock).formatted(Formatting.YELLOW)
							.append(Text.literal("  |  Подозрение: ").formatted(Formatting.GRAY))
							.append(heatBar(d.heat))
							.append(Text.literal("  |  Деньги: " + count(p, CbContent.MONEY)).formatted(Formatting.GREEN))
							.append(Text.literal("  |  " + round).formatted(Formatting.GRAY));
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

	// ------------------------------------------------------------------ seeing and hiding

	/**
	 * How far guards can see this prisoner. Sneaking halves it, darkness cuts it further, and a
	 * prisoner crawling in a vent can only be seen from right next to them.
	 */
	private double sightRange(PlayerEntity p, double base) {
		double r = base;
		if (p.isInSneakingPose()) {
			r *= 0.5;
		}
		if (p.getWorld().getLightLevel(p.getBlockPos().up()) < 6) {
			r *= 0.6;
		}
		if (p.isInSwimmingPose() && !p.isTouchingWater()) {
			r = Math.min(r, 3);
		}
		return r;
	}

	private boolean sees(LivingEntity watcher, PlayerEntity p, double base, boolean needFacing) {
		double r = sightRange(p, base);
		return watcher.getWorld() == p.getWorld() && watcher.squaredDistanceTo(p) < r * r
				&& (!needFacing || facing(watcher, p)) && watcher.canSee(p);
	}

	/** Used by a chasing guard: no need to face the prisoner, they are looking for them. */
	public boolean canGuardSee(GuardEntity guard, PlayerEntity p) {
		boolean seen = sees(guard, p, GUARD_SIGHT * 1.5, false);
		PlayerData d = data(p);
		if (seen && d != null) {
			d.lastSeenTick = ticks;
			d.lastSeenPos = p.getBlockPos();
		}
		return seen;
	}

	public BlockPos lastKnownPos(PlayerEntity p) {
		PlayerData d = data(p);
		return d == null ? null : d.lastSeenPos;
	}

	public long ticksSinceSeen(PlayerEntity p) {
		PlayerData d = data(p);
		return d == null ? Long.MAX_VALUE : ticks - d.lastSeenTick;
	}

	private boolean isSeen(ServerPlayerEntity p, List<GuardEntity> npcs, List<ServerPlayerEntity> playerGuards) {
		return seenBy(p, npcs, playerGuards) != null;
	}

	/** The guard (NPC or player) who sees this prisoner right now, if any. */
	private LivingEntity seenBy(ServerPlayerEntity p, List<GuardEntity> npcs, List<ServerPlayerEntity> playerGuards) {
		for (GuardEntity g : npcs) {
			if (sees(g, p, GUARD_SIGHT, true)) {
				return g;
			}
		}
		for (ServerPlayerEntity g : playerGuards) {
			if (sees(g, p, PLAYER_GUARD_SIGHT, true)) {
				return g;
			}
		}
		return null;
	}

	private static boolean facing(LivingEntity watcher, Entity target) {
		Vec3d look = watcher.getRotationVec(1.0F);
		Vec3d to = target.getEyePos().subtract(watcher.getEyePos()).normalize();
		return look.dotProduct(to) > 0.25;
	}

	// ------------------------------------------------------------------ snipers and detectors

	/**
	 * Day: a sniper only shoots at a prisoner seen doing something suspicious outdoors (breaking a
	 * wall, digging, placing blocks). Night: the snipers sweep the yard with searchlights and shoot
	 * whoever gets caught in the light.
	 */
	private void checkSnipers(ServerPlayerEntity p, PlayerData d, List<GuardEntity> snipers, Phase phase) {
		GuardEntity spotter = null;
		BlockPos pos = p.getBlockPos();
		boolean outdoors = world().isSkyVisible(pos.up());
		if (!snipers.isEmpty() && outdoors) {
			for (GuardEntity s : snipers) {
				// Right next to the post the sniper notices you even past the parapet.
				if (s.squaredDistanceTo(p) >= SNIPER_RANGE * SNIPER_RANGE || !s.canSee(p) && s.squaredDistanceTo(p) > 6 * 6) {
					continue;
				}
				boolean caught;
				double dist2 = s.squaredDistanceTo(p);
				if (phase == Phase.LIGHTS_OUT) {
					Searchlight light = searchlights.get(s.getUuid());
					// In the light, or simply right under the sniper's nose.
					caught = phaseElapsed() >= GRACE_SECONDS * 20 && (dist2 < 14 * 14 || light != null && light.spot != null
							&& (p.getUuid().equals(light.lockedOn) || horizontalDistance(p.getPos(), light.spot) < SEARCHLIGHT_RADIUS
									&& Math.abs(p.getY() - light.spot.y) < 4));
					if (caught) {
						light.lockedOn = p.getUuid();
					}
				} else {
					// By day: something suspicious, or climbing up to the sniper's own wall.
					caught = ticks - d.suspiciousTick < SUSPICIOUS_TICKS
							|| dist2 < 10 * 10 && p.getY() > s.getY() - 4;
				}
				if (caught) {
					spotter = s;
					break;
				}
			}
		}
		if (spotter == null) {
			if (d.sniperSpotTick != 0) {
				searchlights.values().forEach(l -> {
					if (p.getUuid().equals(l.lockedOn)) {
						l.lockedOn = null;
					}
				});
			}
			d.sniperSpotTick = 0;
			return;
		}
		spotter.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, p.getEyePos());
		spotter.setHeadYaw(spotter.getYaw());
		spotter.setBodyYaw(spotter.getYaw());
		if (d.sniperSpotTick == 0) {
			d.sniperSpotTick = ticks;
			addHeat(p, MAX_HEAT);
			d.lastSeenTick = ticks;
			d.lastSeenPos = p.getBlockPos();
			title(p, Text.literal("Снайпер!").formatted(Formatting.DARK_RED),
					Text.literal(phase == Phase.LIGHTS_OUT ? "Снайпер тебя видит. В укрытие!" : "Он видел, что ты делаешь. В укрытие!"));
			p.playSoundToPlayer(SoundEvents.ITEM_CROSSBOW_LOADING_END.value(), SoundCategory.HOSTILE, 1.0F, 1.0F);
		} else if (ticks - d.sniperSpotTick >= SNIPER_AIM_TICKS) {
			d.sniperSpotTick = 0;
			world().playSound(null, spotter.getBlockPos(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 3.0F, 1.0F);
			p.playSoundToPlayer(SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.HOSTILE, 1.0F, 1.0F);
			Searchlight light = searchlights.get(spotter.getUuid());
			if (light != null) {
				light.lockedOn = null;
			}
			arrest(p, "подстрелен снайпером");
		}
	}

	private static double horizontalDistance(Vec3d a, Vec3d b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Remembers that the prisoner did something a sniper would shoot for, if they are outdoors. */
	private void suspiciousOutdoors(ServerPlayerEntity p, BlockPos where) {
		PlayerData d = data(p);
		if (d != null && (world().isSkyVisible(where.up()) || world().isSkyVisible(p.getBlockPos().up()))) {
			d.suspiciousTick = ticks;
		}
	}

	/**
	 * Night: every sniper's searchlight sweeps back and forth over the prison grounds in front of
	 * them. The lit spot is a real light on the ground plus a beam of particles.
	 */
	private void moveSearchlights() {
		ServerWorld world = world();
		for (GuardEntity sniper : snipers()) {
			Searchlight light = searchlights.computeIfAbsent(sniper.getUuid(), id -> new Searchlight());
			Vec3d target;
			ServerPlayerEntity locked = light.lockedOn == null ? null : server.getPlayerManager().getPlayer(light.lockedOn);
			if (locked != null) {
				target = locked.getPos();
			} else {
				// Sweep: glide from one random visible spot of the prison grounds to the next.
				if (light.spot == null) {
					light.spot = pickSweepPoint(sniper);
				}
				if (light.goal == null || light.spot.distanceTo(light.goal) < 1.0 || ticks - light.goalSince > 8 * 20) {
					// Next stop somewhere else, not right next to where the light already is.
					Vec3d next = pickSweepPoint(sniper);
					for (int i = 0; i < 4 && next.distanceTo(light.spot) < 15; i++) {
						next = pickSweepPoint(sniper);
					}
					light.goal = next;
					light.goalSince = ticks;
				}
				double step = light.rush > 0 ? 2.2 : 1.1;
				if (light.rush > 0) {
					light.rush--;
				}
				Vec3d dir = light.goal.subtract(light.spot);
				double len = dir.horizontalLength();
				Vec3d next = len <= step ? light.goal : light.spot.add(dir.multiply(step / len));
				int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(next.x),
						MathHelper.floor(next.z));
				target = new Vec3d(next.x, y, next.z);
			}
			light.spot = target;
			sniper.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target);
			sniper.setHeadYaw(sniper.getYaw());
			sniper.setBodyYaw(sniper.getYaw());
			BlockPos lightPos = BlockPos.ofFloored(target.x, target.y + 0.5, target.z);
			if (!lightPos.equals(light.light)) {
				removeLight(light.light);
				if (world.getBlockState(lightPos).isAir()) {
					world.setBlockState(lightPos, Blocks.LIGHT.getDefaultState().with(net.minecraft.block.LightBlock.LEVEL_15, 15));
					light.light = lightPos;
					addLeftover(lightPos);
				} else {
					light.light = null;
				}
			}
		}
	}

	/** A random outdoor spot inside the prison that this sniper can see from the tower. */
	private Vec3d pickSweepPoint(GuardEntity sniper) {
		ServerWorld world = world();
		Random r = world.random;
		List<BlockBox> areas = layout().areas;
		Vec3d fallback = null;
		for (int attempt = 0; attempt < 16; attempt++) {
			BlockBox a = areas.get(r.nextInt(areas.size()));
			int x = a.getMinX() + 3 + r.nextInt(Math.max(1, a.getBlockCountX() - 6));
			int z = a.getMinZ() + 3 + r.nextInt(Math.max(1, a.getBlockCountZ() - 6));
			int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			Vec3d p = new Vec3d(x + 0.5, y, z + 0.5);
			if (fallback == null) {
				fallback = p;
			}
			if (p.distanceTo(sniper.getEyePos()) > SNIPER_RANGE * 0.8) {
				continue;
			}
			BlockHitResult hit = world.raycast(new RaycastContext(sniper.getEyePos(), p.add(0, 1.0, 0),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, sniper));
			if (hit.getType() == HitResult.Type.MISS) {
				return p;
			}
		}
		return fallback;
	}

	private void removeLight(BlockPos pos) {
		if (pos != null && world().getBlockState(pos).isOf(Blocks.LIGHT)) {
			world().setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		if (pos != null) {
			PrisonState.get(server).leftovers().remove(pos.asLong());
			PrisonState.get(server).markDirty();
		}
	}

	private void addLeftover(BlockPos pos) {
		PrisonState.get(server).leftovers().add(pos.asLong());
		PrisonState.get(server).markDirty();
	}

	/** Lights and drawings left from a round the server did not finish (it was stopped mid-round). */
	private void cleanLeftovers() {
		PrisonState state = PrisonState.get(server);
		if (state.leftovers().isEmpty()) {
			return;
		}
		for (long l : state.leftovers()) {
			BlockPos pos = BlockPos.fromLong(l);
			BlockState b = world().getBlockState(pos);
			if (b.isOf(Blocks.LIGHT) || b.isOf(CbContent.GRAFFITI)) {
				world().setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		state.leftovers().clear();
		state.markDirty();
	}

	private void clearSearchlights() {
		for (Searchlight l : searchlights.values()) {
			removeLight(l.light);
		}
		searchlights.clear();
	}

	/**
	 * The scanner arch: whoever walks through it gets scanned. Clean: green light. Contraband: red
	 * light, siren and the list of what was found.
	 */
	private void checkDetector(ServerPlayerEntity p, PlayerData d) {
		if (ticks < d.detectorQuietUntil) {
			return;
		}
		ServerWorld world = world();
		BlockPos feet = p.getBlockPos();
		BlockPos lower = null;
		Box body = p.getBoundingBox().contract(0.05);
		for (BlockPos q : BlockPos.iterate(BlockPos.ofFloored(body.minX, body.minY, body.minZ),
				BlockPos.ofFloored(body.maxX, body.maxY, body.maxZ))) {
			BlockState state = world.getBlockState(q);
			if (state.isOf(CbContent.DETECTOR)) {
				lower = (state.get(DetectorBlock.HALF) == DoubleBlockHalf.UPPER ? q.down() : q).toImmutable();
				break;
			}
		}
		if (lower == null) {
			return;
		}
		d.detectorQuietUntil = ticks + 60;
		boolean materials = layout().materialDetectors.contains(lower);
		List<Text> found = scannedNames(p, materials);
		boolean tools = carriesTool(p);
		DetectorBlock.flash(world, lower, !found.isEmpty());
		if (found.isEmpty()) {
			world.playSound(null, lower, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.BLOCKS, 1.0F, 1.6F);
			p.sendMessage(Text.literal("Сканер: чисто.").formatted(Formatting.GREEN), true);
			d.hudQuietUntil = ticks + 40;
			return;
		}
		startSiren(lower);
		alertSnipers(lower);
		d.lastSeenTick = ticks;
		d.lastSeenPos = feet;
		// A tool through the arch: full alert.
		int heat = tools ? (int) MAX_HEAT : 60;
		addHeat(p, heat);
		Text list = joined(found);
		p.sendMessage(Text.literal("Сканер сработал! Найдено: ").append(list).append(". Подозрение +" + heat).formatted(Formatting.RED));
		for (ServerPlayerEntity g : online(Role.GUARD)) {
			g.sendMessage(Text.literal("Сканер сработал на " + p.getName().getString() + ": ").append(list).formatted(Formatting.RED));
		}
	}

	/** What a scanner arch finds: contraband (an outer arch lets field nuggets and logs through), plus workshop materials at a materials arch. */
	private static boolean scannerFinds(Item item, boolean materials) {
		if (materials) {
			return CbContent.isContraband(item) || CbContent.isWorkshopMaterial(item);
		}
		return CbContent.isContraband(item) && item != Items.IRON_NUGGET && !item.getRegistryEntry().isIn(ItemTags.LOGS);
	}

	private static List<Text> scannedNames(PlayerEntity p, boolean materials) {
		List<Text> names = new ArrayList<>();
		List<Item> seen = new ArrayList<>();
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && scannerFinds(stack.getItem(), materials) && !seen.contains(stack.getItem())) {
				seen.add(stack.getItem());
				names.add(stack.getName());
			}
		}
		return names;
	}

	private static boolean carriesTool(PlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (CbContent.TOOLS.contains(inv.getStack(i).getItem())) {
				return true;
			}
		}
		return false;
	}

	/** Names of the contraband a prisoner carries (translated on each player's side). */
	/** Contraband thrown through an arch is caught too: it is confiscated and the thrower is in trouble. */
	private void checkThrownContraband() {
		ServerWorld world = world();
		for (BlockPos lower : layout().detectors) {
			Box arch = new Box(lower).stretch(0, 1, 0).expand(0.1);
			boolean materials = layout().materialDetectors.contains(lower);
			for (net.minecraft.entity.ItemEntity item : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, arch,
					i -> scannerFinds(i.getStack().getItem(), materials))) {
				Text name = item.getStack().getName();
				item.discard();
				DetectorBlock.flash(world, lower, true);
				startSiren(lower);
				Entity thrower = item.getOwner();
				if (thrower instanceof ServerPlayerEntity p && isPrisoner(p)) {
					addHeat(p, 60);
					PlayerData d = data(p);
					d.lastSeenTick = ticks;
					d.lastSeenPos = p.getBlockPos();
					p.sendMessage(Text.literal("Сканер засёк брошенную контрабанду: ").append(name)
							.append(". Её забрали. Подозрение +60").formatted(Formatting.RED));
				}
				for (ServerPlayerEntity g : online(Role.GUARD)) {
					g.sendMessage(Text.literal("Сканер: через рамку бросили ").append(name).formatted(Formatting.RED));
				}
			}
		}
	}

	/** A wailing two-tone siren at a scanner arch for three seconds. */
	private final Map<BlockPos, Integer> sirens = new HashMap<>();

	private void startSiren(BlockPos pos) {
		if (!sirens.containsKey(pos)) {
			makeNoise(pos, pos);
		}
		sirens.put(pos, 60);
		scannerAlarms++;
	}

	private void soundSirens() {
		for (Iterator<Map.Entry<BlockPos, Integer>> it = sirens.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, Integer> e = it.next();
			int left = e.getValue();
			if (left % 2 == 0) {
				// Pitch sweeps up and down twice a second, like a siren.
				double phase = (60 - left) / 10.0 * Math.PI;
				float pitch = (float) (1.05 + 0.45 * Math.sin(phase));
				world().playSound(null, e.getKey(), SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.BLOCKS, 2.5F, pitch);
				world().playSound(null, e.getKey(), SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value(), SoundCategory.BLOCKS, 1.5F, pitch);
			}
			if (--left <= 0) {
				it.remove();
			} else {
				e.setValue(left);
			}
		}
	}

	private static List<Text> contrabandNames(PlayerEntity p) {
		List<Text> names = new ArrayList<>();
		List<Item> seen = new ArrayList<>();
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (CbContent.isContraband(stack.getItem()) && !seen.contains(stack.getItem())) {
				seen.add(stack.getItem());
				names.add(stack.getName());
			}
		}
		return names;
	}

	private static Text joined(List<Text> parts) {
		return net.minecraft.text.Texts.join(parts, Text.literal(", "));
	}

	// ------------------------------------------------------------------ roll call search

	/**
	 * Line-up places in the roll call zone: two rows along its longer side, everyone facing the
	 * front line where the guards walk.
	 */
	private void assignLineup(Phase phase) {
		for (PlayerData d : players.values()) {
			d.lineupSlot = null;
		}
		List<PrisonerNpcEntity> npcs = npcPrisoners();
		for (PrisonerNpcEntity npc : npcs) {
			npc.lineupSlot = null;
			npc.lineupLook = null;
		}
		lineup = List.of();
		lineupLook = null;
		if (!phase.isRollCall()) {
			return;
		}
		BlockBox z = layout().rollCallZone;
		boolean alongX = z.getBlockCountX() >= z.getBlockCountZ();
		int y = z.getMinY();
		List<BlockPos> slots = new ArrayList<>();
		if (alongX) {
			int cz = (z.getMinZ() + z.getMaxZ()) / 2;
			for (int row = 0; row < 2; row++) {
				for (int x = z.getMinX() + 2; x <= z.getMaxX() - 2; x += 2) {
					slots.add(new BlockPos(x, y, cz + row * 2));
				}
			}
			lineupLook = new BlockPos((z.getMinX() + z.getMaxX()) / 2, y, cz - 4);
		} else {
			int cx = (z.getMinX() + z.getMaxX()) / 2;
			for (int row = 0; row < 2; row++) {
				for (int zz = z.getMinZ() + 2; zz <= z.getMaxZ() - 2; zz += 2) {
					slots.add(new BlockPos(cx + row * 2, y, zz));
				}
			}
			lineupLook = new BlockPos(cx - 4, y, (z.getMinZ() + z.getMaxZ()) / 2);
		}
		// Fill the rows from the middle out, so a small group stands together.
		int perRow = slots.size() / 2;
		List<BlockPos> ordered = new ArrayList<>();
		for (int i = 0; i < perRow; i++) {
			int k = perRow / 2 + (i % 2 == 0 ? i / 2 : -(i + 1) / 2);
			ordered.add(slots.get(k));
			ordered.add(slots.get(perRow + k));
		}
		lineup = ordered;
		int i = 0;
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (!d.escaped && i < lineup.size()) {
				d.lineupSlot = lineup.get(i++);
			}
		}
		for (PrisonerNpcEntity npc : npcs) {
			if (i < lineup.size()) {
				npc.lineupSlot = lineup.get(i++);
				npc.lineupLook = lineupLook;
			}
		}
	}

	/** Shows each prisoner player their place in the line-up. */
	private void showLineupSlots() {
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.lineupSlot != null && d.solitaryUntil == 0 && d.infirmaryUntil == 0
					&& p.getBlockPos().getSquaredDistance(d.lineupSlot) > 1) {
				world().spawnParticles(p, net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, true,
						d.lineupSlot.getX() + 0.5, d.lineupSlot.getY() + 0.3, d.lineupSlot.getZ() + 0.5, 6, 0.25, 0.1, 0.25, 0);
			}
		}
	}

	/**
	 * Roll call: every NPC guard comes to the hall. Two of them will count the line-up, the others
	 * stand by the exits. The rest of the day the guards patrol.
	 */
	private void assignGuardDuties(Phase phase) {
		List<GuardEntity> guards = new ArrayList<>(npcGuards());
		guards.sort(java.util.Comparator.comparing(Entity::getUuid));
		countingGuard = null;
		PrisonLayout layout = layout();
		BlockBox z = layout.rollCallZone;
		if (!phase.isRollCall()) {
			guards.forEach(g -> g.setDuty(null, null));
			// Where everybody has to be, some guards watch: two on the work field, one elsewhere.
			BlockBox zone = zoneBox(phase.zone);
			if (zone != null) {
				int watchers = Math.min(phase == Phase.WORK || phase.zone == Zone.CANTEEN ? 2 : 1, Math.max(0, guards.size() - 1));
				List<BlockPos> spots = watchSpots(phase, zone);
				for (int i = 0; i < watchers; i++) {
					List<BlockPos> route = new ArrayList<>();
					for (int k = 0; k < 4; k++) {
						route.add(spots.isEmpty() ? randomSpotIn(zone) : spots.get(world().random.nextInt(spots.size())));
					}
					BlockPos center = new BlockPos(zone.getCenter().getX(), zone.getMinY(), zone.getCenter().getZ());
					guards.get(guards.size() - 1 - i).setDuty(route, center, true);
				}
			}
			return;
		}
		BlockPos center = new BlockPos(z.getCenter().getX(), z.getMinY(), z.getCenter().getZ());
		List<BlockPos> posts = new ArrayList<>(layout.rollCallPosts);
		if (posts.isEmpty()) {
			posts.add(new BlockPos(z.getMinX(), z.getMinY(), z.getMinZ()));
			posts.add(new BlockPos(z.getMaxX(), z.getMinY(), z.getMaxZ()));
			posts.add(new BlockPos(z.getMinX(), z.getMinY(), z.getMaxZ()));
			posts.add(new BlockPos(z.getMaxX(), z.getMinY(), z.getMinZ()));
		}
		int post = 0;
		for (int i = 0; i < guards.size(); i++) {
			GuardEntity g = guards.get(i);
			if (i < 2) {
				// Counters wait at the front until the count starts.
				BlockPos start = countLine(i).get(0);
				g.setDuty(List.of(start), center);
				if (i == 0) {
					countingGuard = g;
				}
			} else {
				g.setDuty(List.of(posts.get(post++ % posts.size())), center);
			}
		}
	}

	/**
	 * Where guards on watch walk in a zone. In the canteen: the food queue and the aisles behind the
	 * benches (a random spot there could be in the locked kitchen).
	 */
	private List<BlockPos> watchSpots(Phase phase, BlockBox zone) {
		List<BlockPos> spots = new ArrayList<>();
		if (phase.zone == Zone.CANTEEN) {
			spots.addAll(layout().queueSpots);
			if (seats.isEmpty()) {
				seats = findSeats();
			}
			for (BlockPos seat : seats) {
				BlockState st = world().getBlockState(seat);
				if (st.getBlock() instanceof net.minecraft.block.StairsBlock) {
					BlockPos aisle = seat.offset(st.get(net.minecraft.block.StairsBlock.FACING));
					if (empty(world(), aisle) && empty(world(), aisle.up()) && !empty(world(), aisle.down())) {
						spots.add(aisle);
					}
				}
			}
		}
		return spots;
	}

	/** The line the i-th counting guard walks: in front of the first row, behind the second. */
	private List<BlockPos> countLine(int i) {
		BlockBox z = layout().rollCallZone;
		int y = z.getMinY();
		if (z.getBlockCountX() >= z.getBlockCountZ()) {
			int cz = (z.getMinZ() + z.getMaxZ()) / 2;
			int lineZ = i == 0 ? cz - 2 : cz + 4;
			return List.of(new BlockPos(z.getMinX() + 1, y, lineZ), new BlockPos((z.getMinX() + z.getMaxX()) / 2, y, lineZ),
					new BlockPos(z.getMaxX() - 1, y, lineZ));
		}
		int cx = (z.getMinX() + z.getMaxX()) / 2;
		int lineX = i == 0 ? cx - 2 : cx + 4;
		return List.of(new BlockPos(lineX, y, z.getMinZ() + 1), new BlockPos(lineX, y, (z.getMinZ() + z.getMaxZ()) / 2),
				new BlockPos(lineX, y, z.getMaxZ() - 1));
	}

	private void startCounting() {
		List<GuardEntity> guards = new ArrayList<>(npcGuards());
		guards.sort(java.util.Comparator.comparing(Entity::getUuid));
		BlockPos center = new BlockPos(layout().rollCallZone.getCenter().getX(), layout().rollCallZone.getMinY(),
				layout().rollCallZone.getCenter().getZ());
		for (int i = 0; i < Math.min(2, guards.size()); i++) {
			guards.get(i).setDuty(countLine(i), center);
		}
		guardSays(countingGuard, "Стоять смирно! Начинаем пересчёт.");
	}

	/** A guard speaks to everyone nearby (or to all, if the guard is gone). */
	private void guardSays(GuardEntity guard, String line) {
		String who = guard != null && guard.hasCustomName() ? guard.getName().getString() : "Охранник";
		Text msg = Text.literal(who + ": ").formatted(Formatting.BLUE).append(Text.literal(line).formatted(Formatting.WHITE));
		for (ServerPlayerEntity p : allOnline()) {
			if (guard == null || !guard.isAlive() || p.squaredDistanceTo(guard) < 48 * 48) {
				p.sendMessage(msg, false);
			}
		}
	}

	/** Who stands in the line-up now (players and NPCs) and who is missing. */
	private List<Object> lineUp(List<String> missing, int[] expected) {
		BlockBox area = layout().rollCallZone.expand(2);
		List<Object> pool = new ArrayList<>();
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0) {
				continue;
			}
			expected[0]++;
			if (area.contains(p.getBlockPos())) {
				pool.add(p);
			} else {
				missing.add(p.getName().getString());
			}
		}
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			if (npc.solitaryUntil != 0 || npc.infirmaryUntil != 0) {
				continue;
			}
			expected[0]++;
			if (area.contains(npc.getBlockPos())) {
				pool.add(npc);
			} else {
				missing.add(npc.getName().getString());
			}
		}
		return pool;
	}

	private static final String[] SMALL_NEWS = { "На обед сегодня макароны. Не нравится — голодайте.",
			"В душе сегодня только холодная вода.", "Кто взял ложку из столовой — верните ложку.",
			"Начальник тюрьмы в хорошем настроении. Не испортите.", "Вечером будет проверка камер.",
			"Прогулку сегодня не отменяют. Пока.", "Напоминаю: ходить по газону во дворе запрещено.",
			"Металлолом с рабочего поля сдавать на столе, а не прятать по карманам!",
			"Повар Гена просил передать: котлеты сегодня из настоящего мяса. Почти.",
			"Начальник тюрьмы купил новую дубинку. Розовую. Смеяться запрещено.",
			"Вчера над вышкой видели НЛО. Снайпер промахнулся.", "В душевой завелась лягушка. Не обижайте её, она на испытательном сроке.",
			"Библиотека получила новую книгу. Одну. Очередь с завтрашнего дня.",
			"У Степаныча украли поварёшку. Он плачет. Верните поварёшку.",
			"Голубь на стене признан заключённым месяца. Берите пример.",
			"Ночью кто-то храпел так, что снайперы подняли тревогу.",
			"Сержант Петров женится. Подарок — ваше хорошее поведение.",
			"Найден подкоп. Ведёт в столовую. Видимо, кто-то очень хотел добавки.",
			"Объявляется конкурс на лучшую заправку койки. Приз — ещё одна заправка койки.",
			"Метеорологи обещают солнце. Вам всё равно, вы внутри.",
			"Кто-то нарисовал мелом портрет начальника. Похоже. Это и пугает.",
			"Снайпер Иванов просит не махать ему руками. Он стесняется." };

	/** End of the count: how many are here and who is missing, then the news of the day. */
	private void announceCountAndNews() {
		List<String> missing = new ArrayList<>();
		int[] expected = { 0 };
		List<Object> pool = lineUp(missing, expected);
		guardSays(countingGuard, "Пересчёт окончен: на месте " + pool.size() + " из " + expected[0] + "."
				+ (missing.isEmpty() ? " Все на месте." : " Нет: " + String.join(", ", missing) + "."));
		List<String> news = new ArrayList<>();
		long escaped = players.values().stream().filter(d -> d.role == Role.PRISONER && d.escaped).count();
		if (escaped > 0) {
			news.add("Сбежавших: " + escaped + ". Их ищут. Остальным даже не думать.");
		}
		List<String> wanted = new ArrayList<>();
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			if (isWanted(p)) {
				wanted.add(p.getName().getString());
			}
		}
		if (!wanted.isEmpty()) {
			news.add("В розыске: " + String.join(", ", wanted) + ". Кто знает, где они, — сообщите.");
		}
		if (holesFound > 0) {
			news.add("Найдено проломов и подкопов: " + holesFound + ". Охрана усилена.");
		}
		if (graffitiFound > 0) {
			news.add("Стены опять разрисованы (" + graffitiFound + "). Художника найдём.");
		}
		if (fightsSeen > 0) {
			news.add("Драк: " + fightsSeen + ". Следующего драчуна — в карцер.");
		}
		if (scannerAlarms > 0) {
			news.add("Сканер сработал " + scannerAlarms + " раз. Мы всё видим.");
		}
		// Not every real event makes the news; there is always a tall tale or two to mix in.
		news.removeIf(n -> world().random.nextInt(3) == 0);
		int tales = 1 + world().random.nextInt(2);
		for (int i = 0; i < tales; i++) {
			String tale = SMALL_NEWS[world().random.nextInt(SMALL_NEWS.length)];
			if (!news.contains(tale)) {
				news.add(world().random.nextInt(news.size() + 1), tale);
			}
		}
		holesFound = 0;
		graffitiFound = 0;
		fightsSeen = 0;
		scannerAlarms = 0;
		guardSays(countingGuard, "Новости: " + String.join(" ", news));
	}

	/** After the news: four random inmates in the line-up are searched. */
	private void rollCallSearch() {
		List<Object> pool = lineUp(new ArrayList<>(), new int[] { 0 });
		if (pool.isEmpty()) {
			return;
		}
		Collections.shuffle(pool);
		List<Object> chosen = pool.subList(0, Math.min(SEARCHED_AT_ROLL_CALL, pool.size()));
		List<String> names = new ArrayList<>();
		for (Object o : chosen) {
			names.add(o instanceof ServerPlayerEntity p ? p.getName().getString() : ((PrisonerNpcEntity) o).getName().getString());
		}
		guardSays(countingGuard, "На обыск: " + String.join(", ", names) + ". Шаг вперёд!");
		for (Object o : chosen) {
			if (o instanceof ServerPlayerEntity p) {
				List<Text> found = contrabandNames(p);
				if (!found.isEmpty()) {
					p.sendMessage(Text.literal("При обыске у тебя нашли: ").append(joined(found)).formatted(Formatting.RED));
					arrest(p, "запрещёнка найдена при обыске на перекличке");
				} else {
					p.sendMessage(Text.literal("Тебя обыскали. Чисто.").formatted(Formatting.GREEN), false);
				}
			} else if (o instanceof PrisonerNpcEntity npc && world().random.nextInt(5) == 0) {
				npc.solitaryUntil = ticks + SOLITARY_SECONDS * 20L;
				npc.lineupSlot = null;
				BlockPos s = layout().solitarySpawn;
				npc.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0, 0);
				broadcast(Text.literal("У " + npc.getName().getString() + " нашли заточку. Отправлен в карцер.")
						.formatted(Formatting.GRAY));
			}
		}
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
			d.lastSeenPos = p.getBlockPos();
			title(p, Text.literal("Тебя разыскивают!").formatted(Formatting.DARK_RED),
					Text.literal("Беги и прячься: охрана ищет там, где тебя видели"));
			for (ServerPlayerEntity g : online(Role.GUARD)) {
				g.sendMessage(Text.literal("Тревога! Задержать: " + p.getName().getString()).formatted(Formatting.RED));
			}
		}
	}

	/** Called when a prisoner breaks a block with a makeshift tool. */
	public void onBlockBroken(ServerPlayerEntity p, BlockPos pos, BlockState original) {
		if (!isPrisoner(p)) {
			return;
		}
		damage.putIfAbsent(pos.toImmutable(), original);
		suspiciousOutdoors(p, pos);
		makeNoise(p, pos);
		if (seenWorking(p)) {
			addHeat(p, 40);
			p.sendMessage(Text.literal("Охрана заметила, как ты ломаешь тюрьму! Подозрение +40").formatted(Formatting.RED));
		}
	}

	/**
	 * Breaking walls is loud: a guard close by turns round and sees it (no need to be looking
	 * that way already); guards further off go and check where the noise came from.
	 */
	private boolean seenWorking(ServerPlayerEntity p) {
		for (GuardEntity g : npcGuards()) {
			if (sees(g, p, 12, false)) {
				g.getLookControl().lookAt(p);
				return true;
			}
		}
		return isSeen(p, npcGuards(), online(Role.GUARD));
	}

	/**
	 * A noise (a wall broken, a fight, the scanner) near a sniper's post: by night the searchlight
	 * rushes there, by day the sniper turns to look.
	 */
	private void alertSnipers(BlockPos pos) {
		for (GuardEntity sniper : snipers()) {
			if (sniper.getBlockPos().getSquaredDistance(pos) > 50 * 50) {
				continue;
			}
			if (phase() == Phase.LIGHTS_OUT) {
				Searchlight light = searchlights.computeIfAbsent(sniper.getUuid(), id -> new Searchlight());
				light.goal = Vec3d.ofBottomCenter(pos);
				light.goalSince = ticks;
				light.rush = 30;
			} else {
				sniper.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(pos));
				sniper.setHeadYaw(sniper.getYaw());
				sniper.setBodyYaw(sniper.getYaw());
			}
		}
	}

	private void makeNoise(ServerPlayerEntity p, BlockPos pos) {
		makeNoise(pos, p.getBlockPos());
	}

	/** A noise at pos: the snipers look, the nearest free guard comes to check the spot. */
	private void makeNoise(BlockPos pos, BlockPos checkAt) {
		alertSnipers(pos);
		npcGuards().stream().filter(g -> g.getTarget() == null && g.getBlockPos().getSquaredDistance(pos) < 24 * 24
						&& (!g.hasDuty() || g.getBlockPos().getSquaredDistance(pos) < 12 * 12))
				.min(java.util.Comparator.comparingDouble(g -> g.getBlockPos().getSquaredDistance(pos)))
				.ifPresent(g -> g.checkNoise(checkAt));
	}

	/** Called when a prisoner hits any guard. */
	public void onGuardAttacked(ServerPlayerEntity p) {
		if (isPrisoner(p)) {
			addHeat(p, MAX_HEAT);
			PlayerData d = data(p);
			d.lastSeenTick = ticks;
			d.lastSeenPos = p.getBlockPos();
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
			guard.sendMessage(Text.literal("Задержан! Найдено запрещённых предметов: " + found).formatted(Formatting.GREEN));
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
		d.sniperSpotTick = 0;
		d.infirmaryUntil = 0;
		d.solitaryUntil = ticks + SOLITARY_SECONDS * 20L;
		p.setHealth(p.getMaxHealth());
		p.extinguish();
		p.removeStatusEffect(StatusEffects.GLOWING);
		teleport(p, layout().solitarySpawn);
		title(p, Text.literal("Карцер").formatted(Formatting.RED), Text.literal(reason + ". Изъято предметов: " + removed));
		broadcast(Text.literal(p.getName().getString() + " отправлен в карцер (" + reason + ")").formatted(Formatting.GRAY));
	}

	/**
	 * A participant would die: knock them out instead. Beaten by guards: solitary; beaten by another
	 * prisoner (or fell): the infirmary. Returns true if the death was handled.
	 */
	public boolean onFatalDamage(ServerPlayerEntity p, net.minecraft.entity.damage.DamageSource source) {
		PlayerData d = data(p);
		if (!running || d == null || d.escaped) {
			return false;
		}
		p.setHealth(p.getMaxHealth());
		Entity attacker = source.getAttacker();
		boolean byGuard = attacker instanceof GuardEntity || attacker instanceof ServerPlayerEntity g && isGuard(g);
		if (d.role == Role.PRISONER) {
			if (d.infirmaryUntil != 0 || d.solitaryUntil != 0) {
				return true;
			}
			if (byGuard || layout().infirmarySpawn == null) {
				arrest(p, "потерял сознание");
			} else {
				sendToInfirmary(p, d, attacker instanceof LivingEntity l ? l.getName().getString() : null);
			}
		} else {
			teleport(p, layout().guardSpawn);
			p.sendMessage(Text.literal("Тебя вырубили. Ты очнулся в комнате охраны.").formatted(Formatting.RED));
		}
		return true;
	}

	private void sendToInfirmary(ServerPlayerEntity p, PlayerData d, String by) {
		d.infirmaryUntil = ticks + INFIRMARY_SECONDS * 20L;
		d.sniperSpotTick = 0;
		p.extinguish();
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, INFIRMARY_SECONDS * 20, 1));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, INFIRMARY_SECONDS * 20, 1));
		teleport(p, layout().infirmarySpawn);
		title(p, Text.literal("Медпункт").formatted(Formatting.RED),
				Text.literal((by != null ? "Тебя избил " + by : "Ты потерял сознание") + ". Полминуты лечишься."));
		broadcast(Text.literal(p.getName().getString() + " попал в медпункт" + (by != null ? " (избил " + by + ")" : ""))
				.formatted(Formatting.GRAY));
	}

	/** A prisoner player hit another prisoner (player or NPC): a fight. Guards who see it get suspicious. */
	/** NPC inmates who hit a player and were seen: a guard comes and takes them to solitary. */
	private final Map<UUID, UUID> escorts = new HashMap<>();
	private final Map<UUID, Long> escortSince = new HashMap<>();

	/** An NPC inmate hit a player: a guard who sees it stops the fight and takes the NPC away. */
	public void onNpcAttack(PrisonerNpcEntity npc, LivingEntity victim) {
		makeNoise(npc.getBlockPos(), npc.getBlockPos());
		if (escorts.containsKey(npc.getUuid())) {
			return;
		}
		for (GuardEntity g : npcGuards()) {
			if (g.squaredDistanceTo(npc) < GUARD_SIGHT * GUARD_SIGHT && g.canSee(npc) && facing(g, npc)) {
				fightsSeen++;
				guardSays(g, "Эй, " + npc.getName().getString() + "! Прекратить драку! Руки за спину!");
				npc.calmDown();
				g.goCheck(npc.getBlockPos(), npc.getBlockPos(), 15);
				escorts.put(npc.getUuid(), g.getUuid());
				escortSince.put(npc.getUuid(), ticks);
				return;
			}
		}
	}

	/** Guards who came for a brawling NPC take it to solitary once they reach it. */
	private void runEscorts() {
		for (Iterator<Map.Entry<UUID, UUID>> it = escorts.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, UUID> e = it.next();
			Entity npcE = world().getEntity(e.getKey());
			Entity guardE = world().getEntity(e.getValue());
			if (!(npcE instanceof PrisonerNpcEntity npc) || !(guardE instanceof GuardEntity g)) {
				it.remove();
				continue;
			}
			npc.calmDown();
			g.goCheck(npc.getBlockPos(), npc.getBlockPos(), 15);
			if (g.squaredDistanceTo(npc) < 9 || ticks - escortSince.getOrDefault(e.getKey(), ticks) > 15 * 20) {
				it.remove();
				g.endCheck();
				npc.solitaryUntil = ticks + SOLITARY_SECONDS * 20L;
				npc.lineupSlot = null;
				if (npc.hasVehicle()) {
					npc.stopRiding();
				}
				BlockPos s = layout().solitarySpawn;
				npc.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0, 0);
				npc.getNavigation().stop();
				broadcast(Text.literal(npc.getName().getString() + " уведён в карцер за драку.").formatted(Formatting.GRAY));
			}
		}
	}

	/** NPC inmates are scanned too: a tool carried out of the work field sets the arch off. */
	private void scanNpcs() {
		for (BlockPos lower : layout().detectors) {
			Box arch = new Box(lower).stretch(0, 1, 0).expand(0.3);
			for (PrisonerNpcEntity npc : world().getEntitiesByClass(PrisonerNpcEntity.class, arch,
					n -> n.isAlive() && (n.getBlockPos().equals(lower) || n.getBlockPos().equals(lower.up())))) {
				boolean caught = npc.smugglingTool || CbContent.isContraband(npc.getMainHandStack().getItem());
				if (!caught) {
					continue;
				}
				npc.smugglingTool = false;
				npc.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				DetectorBlock.flash(world(), lower, true);
				startSiren(lower);
				alertSnipers(lower);
				npc.solitaryUntil = ticks + SOLITARY_SECONDS * 20L;
				BlockPos s = layout().solitarySpawn;
				npc.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0, 0);
				npc.getNavigation().stop();
				broadcast(Text.literal("Сканер поймал " + npc.getName().getString() + " с рабочей киркой. Отправлен в карцер.")
						.formatted(Formatting.GRAY));
			}
		}
	}

	/** A prisoner washes a chalk drawing off with a rag. */
	public void washGraffiti(ServerPlayerEntity p, BlockPos pos) {
		world().setBlockState(pos, Blocks.AIR.getDefaultState());
		world().playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.PLAYERS, 0.6F, 1.4F);
		world().spawnParticles(net.minecraft.particle.ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				8, 0.3, 0.3, 0.3, 0.05);
		graffiti.remove(pos);
		PrisonState.get(server).leftovers().remove(pos.asLong());
		p.getMainHandStack().damage(1, p, EquipmentSlot.MAINHAND);
		p.sendMessage(Text.literal("Рисунок смыт.").formatted(Formatting.GRAY), true);
	}

	public void onPrisonerFight(ServerPlayerEntity attacker, Entity victim) {
		// A fight is loud: guards nearby come to see what is going on.
		makeNoise(attacker.getBlockPos(), attacker.getBlockPos());
		PlayerData d = data(attacker);
		if (!isPrisoner(attacker) || ticks - d.lastFightTick < 40) {
			return;
		}
		d.lastFightTick = ticks;
		LivingEntity watcher = seenBy(attacker, npcGuards(), online(Role.GUARD));
		if (watcher != null) {
			fightsSeen++;
			addHeat(attacker, 15);
			attacker.sendMessage(Text.literal("Охрана видит, как ты дерёшься! Подозрение +15").formatted(Formatting.RED), true);
			if (watcher instanceof GuardEntity g) {
				guardSays(g, "Эй! А ну прекратить драку, " + attacker.getName().getString() + "!");
				g.breakUpFight(attacker);
				// The nearest other guard comes to help.
				npcGuards().stream().filter(o -> o != g && o.squaredDistanceTo(attacker) < 24 * 24)
						.min(java.util.Comparator.comparingDouble(o -> o.squaredDistanceTo(attacker)))
						.ifPresent(o -> o.breakUpFight(attacker));
				if (victim instanceof PrisonerNpcEntity npc) {
					npc.calmDown();
				}
			}
			for (ServerPlayerEntity pg : online(Role.GUARD)) {
				pg.sendMessage(Text.literal("Драка: " + attacker.getName().getString() + " (" + attacker.getBlockPos().toShortString()
						+ ")").formatted(Formatting.RED), false);
			}
		}
	}

	/** An NPC inmate lost a fight: they spend a minute in the infirmary instead of dying. */
	public boolean onNpcKnockedOut(PrisonerNpcEntity npc, net.minecraft.entity.damage.DamageSource source) {
		if (!running) {
			return false;
		}
		npc.setHealth(npc.getMaxHealth());
		npc.setTarget(null);
		npc.setAttacker(null);
		BlockPos s = layout().infirmarySpawn != null ? layout().infirmarySpawn
				: layout().cells.get(Math.max(0, Math.min(npc.cell, layout().cells.size() - 1))).spawn;
		npc.infirmaryUntil = ticks + INFIRMARY_SECONDS * 20L;
		npc.lineupSlot = null;
		npc.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0, 0);
		npc.getNavigation().stop();
		String by = source.getAttacker() instanceof LivingEntity l ? " (избил " + l.getName().getString() + ")" : "";
		broadcast(Text.literal(npc.getName().getString() + " попал в медпункт" + by).formatted(Formatting.GRAY));
		return true;
	}

	/** In solitary or in the infirmary: nobody may pick a fight with them. */
	public boolean isOutOfPlay(PlayerEntity p) {
		PlayerData d = data(p);
		return d == null || d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0;
	}

	/** NPC inmates do not pester the same player every few seconds. */
	public boolean mayTalkTo(ServerPlayerEntity p) {
		PlayerData d = data(p);
		if (d == null || ticks - d.lastTalkTick < 15 * 20) {
			return false;
		}
		d.lastTalkTick = ticks;
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

	// ------------------------------------------------------------------ damage, fakes, vents

	/** NPC guards on patrol notice holes, cracks and graffiti they can see and have them repaired. */
	private void guardsInspectDamage() {
		ServerWorld world = world();
		List<GuardEntity> guards = npcGuards();
		for (Iterator<Map.Entry<BlockPos, UUID>> it = graffiti.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, UUID> e = it.next();
			BlockPos pos = e.getKey();
			if (!world.getBlockState(pos).isOf(CbContent.GRAFFITI)) {
				it.remove();
				continue;
			}
			for (GuardEntity g : guards) {
				if (g.getBlockPos().getSquaredDistance(pos) < 100 && canSeeBlock(g, pos)) {
					world.setBlockState(pos, Blocks.AIR.getDefaultState());
					it.remove();
					graffitiFound++;
					guardSays(g, "Опять кто-то стены разрисовал! Закрасить. Найду художника — пойдёт в карцер.");
					ServerPlayerEntity author = server.getPlayerManager().getPlayer(e.getValue());
					if (author != null && isPrisoner(author)) {
						addHeat(author, 15);
						author.sendMessage(Text.literal("Охрана нашла твоё граффити. Подозрение +15").formatted(Formatting.RED), true);
					}
					break;
				}
			}
		}
		if (damage.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<BlockPos, BlockState>> it = damage.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, BlockState> e = it.next();
			BlockPos pos = e.getKey();
			BlockState now = world.getBlockState(pos);
			if (now.equals(e.getValue())) {
				it.remove();
				continue;
			}
			if (now.getBlock() instanceof FakeBlock) {
				continue;
			}
			for (GuardEntity g : guards) {
				if (g.getBlockPos().getSquaredDistance(pos) < 100 && canSeeBlock(g, pos)) {
					world.setBlockState(pos, e.getValue());
					it.remove();
					holesFound++;
					broadcast(Text.literal("Охрана нашла " + describe(e.getValue()) + " (" + pos.toShortString()
							+ ") и заделала. Будут искать виновного!").formatted(Formatting.RED));
					ServerPlayerEntity owner = cellOwner(pos);
					if (owner != null) {
						addHeat(owner, 50);
					}
					break;
				}
			}
		}
	}

	private boolean canSeeBlock(LivingEntity watcher, BlockPos pos) {
		Vec3d eye = watcher.getEyePos();
		Vec3d center = Vec3d.ofCenter(pos);
		Vec3d to = center.subtract(eye).normalize();
		if (watcher.getRotationVec(1.0F).dotProduct(to) < 0.2) {
			return false;
		}
		BlockHitResult hit = watcher.getWorld().raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, watcher));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)
				|| hit.getPos().squaredDistanceTo(eye) >= center.squaredDistanceTo(eye) - 1.0;
	}

	private static String describe(BlockState original) {
		if (original.isOf(CbContent.VENT_COVER)) {
			return "снятую решётку вентиляции";
		}
		if (original.isOf(CbContent.CELL_BARS) || original.isOf(Blocks.IRON_BARS)) {
			return "перепиленную решётку";
		}
		if (original.isOf(CbContent.PRISON_FLOOR) || original.isOf(Blocks.SMOOTH_STONE)) {
			return "дыру в полу";
		}
		if (original.isIn(BlockTags.DIRT) || original.isOf(Blocks.GRAVEL) || original.isOf(Blocks.SAND)) {
			return "подкоп";
		}
		return "пролом в стене";
	}

	/** The prisoner whose cell (with its walls) contains this position, if any. */
	private ServerPlayerEntity cellOwner(BlockPos pos) {
		List<PrisonLayout.Cell> cells = layout().cells;
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerData d = data(p);
			if (d.cell >= 0 && d.cell < cells.size() && cells.get(d.cell).interior.expand(1).contains(pos)) {
				return p;
			}
		}
		return null;
	}

	/** End of round: put the prison back the way it was. */
	private void repairAll() {
		ServerWorld world = world();
		damage.forEach((pos, state) -> world.setBlockState(pos, state));
		damage.clear();
		for (BlockPos pos : graffiti.keySet()) {
			if (world.getBlockState(pos).isOf(CbContent.GRAFFITI)) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		graffiti.clear();
		PrisonState.get(server).leftovers().clear();
		PrisonState.get(server).markDirty();
		regrow(true);
		clearSearchlights();
	}

	// ------------------------------------------------------------------ graffiti

	/** A prisoner draws on a wall with chalk. Returns true if something was drawn. */
	public boolean drawGraffiti(ServerPlayerEntity p, BlockPos wall, Direction side) {
		if (!running || side.getAxis() == Direction.Axis.Y) {
			return false;
		}
		ServerWorld world = world();
		BlockPos pos = wall.offset(side);
		if (!world.getBlockState(pos).isAir() || !world.getBlockState(wall).isSideSolidFullSquare(world, wall, side)) {
			p.sendMessage(Text.literal("Рисовать можно только на ровной стене.").formatted(Formatting.GRAY), true);
			return true;
		}
		world.setBlockState(pos, CbContent.GRAFFITI.getDefaultState().with(GraffitiBlock.FACING, side)
				.with(GraffitiBlock.VARIANT, world.random.nextInt(GraffitiBlock.VARIANTS)));
		world.playSound(null, pos, SoundEvents.BLOCK_SAND_HIT, SoundCategory.PLAYERS, 1.0F, 1.4F);
		p.getMainHandStack().damage(1, p, EquipmentSlot.MAINHAND);
		graffiti.put(pos, p.getUuid());
		addLeftover(pos);
		if (isPrisoner(p)) {
			suspiciousOutdoors(p, pos);
			if (isSeen(p, npcGuards(), online(Role.GUARD))) {
				addHeat(p, 25);
				p.sendMessage(Text.literal("Охрана видела, как ты рисуешь на стене! Подозрение +25").formatted(Formatting.RED));
			}
		}
		return true;
	}

	/** A guard player wipes a drawing off. */
	public void wipeGraffiti(ServerPlayerEntity guard, BlockPos pos) {
		world().setBlockState(pos, Blocks.AIR.getDefaultState());
		world().playSound(null, pos, SoundEvents.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 1.0F, 1.0F);
		UUID author = graffiti.remove(pos);
		ServerPlayerEntity p = author == null ? null : server.getPlayerManager().getPlayer(author);
		guard.sendMessage(Text.literal("Граффити стёрто" + (p != null ? ". Рисовал: " + p.getName().getString() : "."))
				.formatted(Formatting.GRAY), true);
	}

	// ------------------------------------------------------------------ the work field

	/** Work tools only work on the work field during work. */
	public boolean canWorkAt(BlockPos pos) {
		PrisonLayout l = layout();
		return running && phase() == Phase.WORK && l.workZone != null && l.workZone.contains(pos);
	}

	/** Whether this spot counts as working right now (NPC inmates swing a pickaxe there). */
	public boolean isWorkingAt(BlockPos pos) {
		return canWorkAt(pos);
	}

	/** A prisoner mined ore or felled a log on the work field: drop the goods, it grows back later. */
	public void onWorkBlockBroken(ServerPlayerEntity p, BlockPos pos, BlockState original) {
		ServerWorld world = world();
		boolean ore = CbContent.WORK_ORES.contains(original.getBlock());
		if (ore) {
			p.giveItemStack(new ItemStack(Items.IRON_NUGGET, 1 + world.random.nextInt(3)));
			world.setBlockState(pos, Blocks.STONE.getDefaultState());
		} else {
			p.giveItemStack(new ItemStack(original.getBlock().asItem()));
		}
		regrowth.putIfAbsent(pos.toImmutable(), new Regrowth(original,
				ticks + (ore ? ORE_REGEN_SECONDS : LOG_REGEN_SECONDS) * 20L));
	}

	/** Puts mined ore and felled logs back once their time is up (or all of them, at the end of a round). */
	private void regrow(boolean all) {
		ServerWorld world = world();
		for (Iterator<Map.Entry<BlockPos, Regrowth>> it = regrowth.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, Regrowth> e = it.next();
			if (!all && ticks < e.getValue().at()) {
				continue;
			}
			BlockPos pos = e.getKey();
			BlockState now = world.getBlockState(pos);
			if (!all && !world.getOtherEntities(null, new Box(pos)).isEmpty()) {
				continue;
			}
			if (now.isAir() || now.isOf(Blocks.STONE)) {
				world.setBlockState(pos, e.getValue().state());
			}
			it.remove();
		}
	}

	/** End of work: work tools still in pockets are taken away, and that is noted. */
	private void collectWorkTools() {
		for (ServerPlayerEntity p : online(Role.PRISONER)) {
			PlayerInventory inv = p.getInventory();
			int n = 0;
			for (int i = 0; i < inv.size(); i++) {
				if (CbContent.WORK_TOOLS.contains(inv.getStack(i).getItem())) {
					inv.setStack(i, ItemStack.EMPTY);
					n++;
				}
			}
			if (n > 0 && isPrisoner(p)) {
				addHeat(p, 15);
				p.sendMessage(Text.literal("Ты не сдал рабочий инструмент, его забрали. Подозрение +15").formatted(Formatting.RED));
			}
		}
	}

	/**
	 * A prisoner holding a fake wall or vent cover looks at a hole they made: cover it. Returns true
	 * if something was placed.
	 */
	public boolean placeFake(ServerPlayerEntity p) {
		ItemStack stack = p.getMainHandStack();
		boolean ventItem = stack.isOf(CbContent.FAKE_VENT);
		if (!running || !(ventItem || stack.isOf(CbContent.FAKE_WALL))) {
			return false;
		}
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1.0F);
		for (double t = 0.5; t <= 5.0; t += 0.2) {
			BlockPos pos = BlockPos.ofFloored(eye.add(look.multiply(t)));
			BlockState original = damage.get(pos);
			if (original == null || !world().getBlockState(pos).isAir()) {
				continue;
			}
			Block fake = fakeFor(original);
			if (fake == null) {
				p.sendMessage(Text.literal("Это не замаскировать.").formatted(Formatting.RED), true);
				return true;
			}
			if (((FakeBlock) fake).vent != ventItem) {
				p.sendMessage(Text.literal(ventItem ? "Сюда нужен муляж стены или пола." : "Сюда нужен муляж решётки вентиляции.")
						.formatted(Formatting.RED), true);
				return true;
			}
			if (p.getBoundingBox().intersects(new Box(pos))) {
				p.sendMessage(Text.literal("Отойди, ты стоишь в проёме.").formatted(Formatting.RED), true);
				return true;
			}
			world().setBlockState(pos, fake.getDefaultState());
			world().playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
			suspiciousOutdoors(p, pos);
			if (!p.isCreative()) {
				stack.decrement(1);
			}
			p.sendMessage(Text.literal("Дыра замаскирована. Снять: присесть + ПКМ.").formatted(Formatting.GREEN), true);
			return true;
		}
		return false;
	}

	private static Block fakeFor(BlockState original) {
		if (original.isOf(CbContent.VENT_COVER)) {
			return CbContent.FAKE_VENT_COVER;
		}
		if (original.isOf(CbContent.PRISON_WALL) || original.isOf(CbContent.CRACKED_PRISON_WALL)) {
			return CbContent.FAKE_PRISON_WALL;
		}
		if (original.isIn(BlockTags.STONE_BRICKS)) {
			return CbContent.FAKE_STONE_BRICKS;
		}
		if (original.isOf(CbContent.PRISON_FLOOR)) {
			return CbContent.FAKE_PRISON_FLOOR;
		}
		if (original.isOf(Blocks.SMOOTH_STONE)) {
			return CbContent.FAKE_SMOOTH_STONE;
		}
		return null;
	}

	/** Sneak + right click on a fake block by a prisoner: take it off (and keep it). */
	public void takeFake(ServerPlayerEntity p, BlockPos pos, FakeBlock fake) {
		world().setBlockState(pos, Blocks.AIR.getDefaultState());
		world().playSound(null, pos, SoundEvents.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 1.0F, 1.0F);
		p.giveItemStack(new ItemStack(fake.vent ? CbContent.FAKE_VENT : CbContent.FAKE_WALL));
	}

	/** A guard taps a fake block with the baton: it falls apart and the hole is revealed. */
	public void revealFake(ServerPlayerEntity guard, BlockPos pos) {
		world().setBlockState(pos, Blocks.AIR.getDefaultState());
		world().playSound(null, pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 1.0F, 0.8F);
		broadcast(Text.literal("Охранник " + guard.getName().getString() + " нашёл муляж в " + pos.toShortString() + "!")
				.formatted(Formatting.RED));
		ServerPlayerEntity owner = cellOwner(pos);
		if (owner != null) {
			addHeat(owner, 50);
		}
	}

	/**
	 * Right click with an empty hand into a crawl space (1 block high): the prisoner squeezes in
	 * and crawls. Also works from below through a ceiling vent hole.
	 */
	public static boolean tryEnterCrawlSpace(ServerPlayerEntity p, BlockHitResult hit) {
		ServerWorld world = p.getServerWorld();
		if (p.isInSwimmingPose()) {
			return false;
		}
		BlockPos t = hit.getBlockPos().offset(hit.getSide());
		BlockPos target = null;
		if (isCrawlSpace(world, t)) {
			target = t;
		} else if (empty(world, t) && !empty(world, t.up()) && empty(world, t.down())) {
			for (Direction d : Direction.Type.HORIZONTAL) {
				if (isCrawlSpace(world, t.offset(d))) {
					target = t.offset(d);
					break;
				}
			}
		}
		if (target == null || p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > 16) {
			return false;
		}
		p.teleport(world, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, p.getYaw(), p.getPitch());
		p.setPose(EntityPose.SWIMMING);
		return true;
	}

	private static boolean isCrawlSpace(ServerWorld world, BlockPos p) {
		return empty(world, p) && !empty(world, p.up()) && !empty(world, p.down());
	}

	private static boolean empty(ServerWorld world, BlockPos p) {
		return world.getBlockState(p).getCollisionShape(world, p).isEmpty();
	}

	// ------------------------------------------------------------------ work and money

	/**
	 * A prisoner uses the job desk: hands in what they got on the work field for money. Taken during
	 * work and the free time after it.
	 */
	public void useJobDesk(ServerPlayerEntity p) {
		if (!isPrisoner(p)) {
			p.sendMessage(Text.literal("Стол сдачи работы — для заключённых.").formatted(Formatting.GRAY), true);
			return;
		}
		if (phase() != Phase.WORK && phase() != Phase.FREE_TIME) {
			p.sendMessage(Text.literal("Работу принимают во время работы и сразу после неё.").formatted(Formatting.RED), true);
			return;
		}
		ItemStack stack = p.getMainHandStack();
		int per;
		int value;
		if (stack.isOf(Items.IRON_NUGGET)) {
			per = 3;
			value = 1;
		} else if (stack.isOf(Items.IRON_INGOT)) {
			per = 1;
			value = 3;
		} else if (stack.isIn(ItemTags.LOGS)) {
			per = 2;
			value = 1;
		} else if (stack.isIn(ItemTags.PLANKS)) {
			per = 8;
			value = 1;
		} else {
			p.sendMessage(Text.literal("Сюда сдают добычу с рабочего поля: 3 самородка = 1 монета, слиток = 3, "
					+ "2 бревна = 1. Возьми в руку и нажми ПКМ.").formatted(Formatting.YELLOW), false);
			return;
		}
		int sets = stack.getCount() / per;
		if (sets == 0) {
			p.sendMessage(Text.literal("Мало: нужно хотя бы " + per + " шт.").formatted(Formatting.RED), true);
			return;
		}
		stack.decrement(sets * per);
		int pay = sets * value;
		p.giveItemStack(new ItemStack(CbContent.MONEY, pay));
		world().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 1.0F, 1.0F);
		p.sendMessage(Text.literal("Работа принята: +" + pay + " монет.").formatted(Formatting.GREEN), true);
	}

	/** Work starts: the tool boxes at the field get work pickaxes and axes. */
	private void refillSupplies() {
		for (BlockPos pos : layout().workSupplies) {
			if (world().getBlockEntity(pos) instanceof Inventory chest) {
				chest.clear();
				for (int i = 0; i < 4 && i < chest.size(); i++) {
					chest.setStack(i, new ItemStack(CbContent.WORK_PICKAXE));
				}
				for (int i = 4; i < 6 && i < chest.size(); i++) {
					chest.setStack(i, new ItemStack(CbContent.WORK_AXE));
				}
			}
		}
	}

	// ------------------------------------------------------------------ canteen

	/** NPC inmates in the food queue, in the order they came. */
	private final List<UUID> foodQueue = new ArrayList<>();
	private final java.util.Set<UUID> fedThisMeal = new java.util.HashSet<>();
	private final Map<UUID, BlockPos> seatOf = new HashMap<>();
	private final Map<UUID, Entity> seatStands = new HashMap<>();
	private List<BlockPos> seats = List.of();
	private int nextCook;
	/** Since when the inmate at the front of the queue has been waiting to get there. */
	private long queueFrontSince;
	private UUID queueFront;

	private void spawnCooks() {
		List<BlockPos> posts = layout().cookPosts;
		for (int i = 0; i < Math.min(2, posts.size()); i++) {
			CookEntity cook = CbContent.COOK.create(world());
			if (cook == null) {
				continue;
			}
			BlockPos p = posts.get(i);
			cook.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
			cook.setup(i, p);
			world().spawnEntity(cook);
		}
	}

	private List<CookEntity> cooks() {
		return world().getEntitiesByClass(CookEntity.class, npcArea(), Entity::isAlive);
	}

	/** Seats of the canteen: the stairs (benches, chairs) inside it. */
	private List<BlockPos> findSeats() {
		BlockBox z = layout().canteenZone;
		List<BlockPos> list = new ArrayList<>();
		for (BlockPos p : BlockPos.iterate(z.getMinX(), z.getMinY(), z.getMinZ(), z.getMaxX(), z.getMaxY(), z.getMaxZ())) {
			BlockState s = world().getBlockState(p);
			if (s.getBlock() instanceof net.minecraft.block.StairsBlock
					&& s.get(net.minecraft.block.StairsBlock.HALF) == net.minecraft.block.enums.BlockHalf.BOTTOM
					&& empty(world(), p.up())) {
				list.add(p.toImmutable());
			}
		}
		return list;
	}

	/** A meal begins: food in the kitchen chests, an empty queue, free seats. Any other part: everybody stands up. */
	private void startMeal(Phase phase) {
		standEveryoneUp();
		foodQueue.clear();
		fedThisMeal.clear();
		seatOf.clear();
		if (phase.zone != Zone.CANTEEN) {
			return;
		}
		if (seats.isEmpty()) {
			seats = findSeats();
		}
		for (BlockPos pos : layout().foodChests) {
			if (world().getBlockEntity(pos) instanceof Inventory chest) {
				chest.clear();
				chest.setStack(0, new ItemStack(Items.BREAD, 32));
				chest.setStack(1, new ItemStack(Items.BAKED_POTATO, 16));
				chest.setStack(2, gruel(8));
			}
		}
	}

	private static ItemStack gruel(int count) {
		ItemStack s = new ItemStack(Items.BEETROOT_SOUP, Math.min(count, 1));
		s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Баланда").styled(st -> st.withItalic(false)));
		return s;
	}

	/** Where an NPC inmate goes at a meal: the queue, then a free seat; null when there is nothing to do. */
	private BlockPos mealDestination(PrisonerNpcEntity npc) {
		PrisonLayout l = layout();
		if (l.queueSpots.isEmpty()) {
			return null;
		}
		UUID id = npc.getUuid();
		if (fedThisMeal.contains(id)) {
			BlockPos seat = seatOf.get(id);
			if (seat == null) {
				for (BlockPos s : seats) {
					if (!seatOf.containsValue(s)) {
						seatOf.put(id, s);
						seat = s;
						break;
					}
				}
			}
			return seat;
		}
		if (!foodQueue.contains(id)) {
			foodQueue.add(id);
		}
		int i = foodQueue.indexOf(id);
		BlockPos spot = l.queueSpots.get(Math.min(i, l.queueSpots.size() - 1));
		return spot;
	}

	/** Every second at meals: the inmate at the front of the queue gets food. */
	private void serveQueue() {
		PrisonLayout l = layout();
		if (foodQueue.isEmpty() || l.queueSpots.isEmpty()) {
			return;
		}
		Entity e = world().getEntity(foodQueue.get(0));
		if (!(e instanceof PrisonerNpcEntity npc) || !npc.isAlive()) {
			foodQueue.remove(0);
			return;
		}
		if (!npc.getUuid().equals(queueFront)) {
			queueFront = npc.getUuid();
			queueFrontSince = ticks;
		}
		// Served at the hatch; one stuck on the way does not hold up the whole queue.
		if (npc.getBlockPos().getSquaredDistance(l.queueSpots.get(0)) > 3 && ticks - queueFrontSince < 12 * 20) {
			return;
		}
		List<CookEntity> cooks = cooks();
		if (!cooks.isEmpty()) {
			CookEntity cook = cooks.get(nextCook++ % cooks.size());
			cook.swingHand(net.minecraft.util.Hand.MAIN_HAND);
			cook.getLookControl().lookAt(npc);
			if (world().random.nextInt(3) == 0) {
				Text line = cook.line();
				for (ServerPlayerEntity p : world().getPlayers()) {
					if (p.squaredDistanceTo(cook) < 12 * 12) {
						p.sendMessage(line, false);
					}
				}
			}
		}
		npc.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BREAD));
		fedThisMeal.add(npc.getUuid());
		foodQueue.remove(0);
	}

	/** An NPC inmate reached its seat: it sits down (on an invisible stand) and eats. */
	public void onNpcArrived(PrisonerNpcEntity npc, BlockPos at) {
		if (phase() == Phase.SHOWER && at.equals(showerSpots.get(npc.getUuid()))) {
			// Under the shower head: turn the water on.
			BlockPos lever = at.up();
			BlockState st = world().getBlockState(lever);
			if (st.isOf(Blocks.LEVER) && !st.get(net.minecraft.block.LeverBlock.POWERED)) {
				world().setBlockState(lever, st.with(net.minecraft.block.LeverBlock.POWERED, true));
				world().playSound(null, lever, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.4F, 0.6F);
				leversOn.add(lever);
			}
			return;
		}
		if (phase() == Phase.LIGHTS_OUT && npc.cell >= 0 && npc.cell < layout().cells.size()
				&& layout().cells.get(npc.cell).interior.contains(npc.getBlockPos())) {
			putToBed(npc);
			return;
		}
		BlockPos seat = seatOf.get(npc.getUuid());
		if (seat == null || !seat.equals(at) || npc.hasVehicle() || phase().zone != Zone.CANTEEN) {
			return;
		}
		net.minecraft.entity.decoration.ArmorStandEntity stand = new net.minecraft.entity.decoration.ArmorStandEntity(
				world(), seat.getX() + 0.5, seat.getY() + SEAT_HEIGHT, seat.getZ() + 0.5);
		NbtCompound nbt = new NbtCompound();
		stand.writeCustomDataToNbt(nbt);
		nbt.putBoolean("Marker", true);
		nbt.putBoolean("Invisible", true);
		nbt.putBoolean("NoGravity", true);
		nbt.putBoolean("Invulnerable", true);
		stand.readCustomDataFromNbt(nbt);
		BlockState st = world().getBlockState(seat);
		float yaw = st.getBlock() instanceof net.minecraft.block.StairsBlock
				? st.get(net.minecraft.block.StairsBlock.FACING).getOpposite().asRotation() : 0;
		stand.refreshPositionAndAngles(seat.getX() + 0.5, seat.getY() + SEAT_HEIGHT, seat.getZ() + 0.5, yaw, 0);
		world().spawnEntity(stand);
		npc.getNavigation().stop();
		npc.startRiding(stand, true);
		npc.setYaw(yaw);
		npc.setBodyYaw(yaw);
		npc.setHeadYaw(yaw);
		seatStands.put(npc.getUuid(), stand);
	}

	/** How high above the stairs block the invisible seat stands. */
	private static final double SEAT_HEIGHT = -0.25;

	private void standEveryoneUp() {
		for (Map.Entry<UUID, Entity> e : seatStands.entrySet()) {
			Entity stand = e.getValue();
			BlockPos seat = seatOf.get(e.getKey());
			for (Entity rider : new ArrayList<>(stand.getPassengerList())) {
				rider.stopRiding();
				// Up from the bench, right where it is (the game would pick any free spot, even outdoors).
				if (seat != null) {
					rider.refreshPositionAndAngles(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, rider.getYaw(), 0);
				}
			}
			stand.discard();
		}
		seatStands.clear();
		if (running) {
			for (PrisonerNpcEntity npc : npcPrisoners()) {
				if (npc.getMainHandStack().isOf(Items.BREAD)) {
					npc.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				}
			}
		}
	}

	/** A player asks a cook for food: one portion per meal. */
	public void askCookForFood(CookEntity cook, ServerPlayerEntity p) {
		Text say;
		if (!running || phase().zone != Zone.CANTEEN) {
			say = Text.literal(cook.getName().getString() + ": ").formatted(Formatting.GOLD).append(Text.literal(
					cook.isCheerful() ? "Кухня пока закрыта, дорогой! Приходи на завтрак или ужин." : "Закрыто. Еда — по распорядку.")
					.formatted(Formatting.WHITE));
		} else if (!fedThisMeal.add(p.getUuid())) {
			say = Text.literal(cook.getName().getString() + ": ").formatted(Formatting.GOLD).append(Text.literal(
					cook.isCheerful() ? "Ты уже получил свою порцию, хитрец!" : "Одна порция в руки. Проваливай.")
					.formatted(Formatting.WHITE));
		} else {
			p.giveItemStack(new ItemStack(Items.BREAD, 2));
			p.giveItemStack(gruel(1));
			world().playSound(null, cook.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 1.0F, 1.0F);
			say = cook.line();
		}
		p.sendMessage(say, false);
	}

	/** Seated inmates eat now and then. */
	private void eatAnimations() {
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			if (npc.hasVehicle() && world().random.nextInt(4) == 0) {
				npc.swingHand(net.minecraft.util.Hand.MAIN_HAND);
				world().playSound(null, npc.getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 0.5F,
						0.9F + world().random.nextFloat() * 0.2F);
				world().spawnParticles(new net.minecraft.particle.ItemStackParticleEffect(net.minecraft.particle.ParticleTypes.ITEM,
						new ItemStack(Items.BREAD)), npc.getX(), npc.getEyeY() - 0.2, npc.getZ(), 3, 0.1, 0.05, 0.1, 0.02);
			}
		}
	}

	// ------------------------------------------------------------------ showers

	/** Shower places of NPC inmates (under a shower head) and the faucets they opened. */
	private final Map<UUID, BlockPos> showerSpots = new HashMap<>();
	private final List<BlockPos> leversOn = new ArrayList<>();

	private void startShower(Phase phase) {
		for (BlockPos lever : leversOn) {
			BlockState st = world().getBlockState(lever);
			if (st.isOf(Blocks.LEVER)) {
				world().setBlockState(lever, st.with(net.minecraft.block.LeverBlock.POWERED, false));
			}
		}
		leversOn.clear();
		showerSpots.clear();
	}

	/** A free place under a shower head for this inmate (each faucet is a lever above the place). */
	private BlockPos showerSpot(PrisonerNpcEntity npc) {
		BlockPos spot = showerSpots.get(npc.getUuid());
		if (spot != null) {
			return spot;
		}
		for (BlockPos head : layout().showerHeads) {
			BlockPos s = head.down();
			if (!showerSpots.containsValue(s)) {
				showerSpots.put(npc.getUuid(), s);
				return s;
			}
		}
		return null;
	}

	/** Inmates under a running shower wash themselves. */
	private void washAnimations() {
		for (Map.Entry<UUID, BlockPos> e : showerSpots.entrySet()) {
			if (world().getEntity(e.getKey()) instanceof PrisonerNpcEntity npc && npc.getBlockPos().equals(e.getValue())
					&& world().random.nextInt(3) == 0) {
				npc.swingHand(world().random.nextBoolean() ? net.minecraft.util.Hand.MAIN_HAND : net.minecraft.util.Hand.OFF_HAND);
				world().spawnParticles(net.minecraft.particle.ParticleTypes.BUBBLE_POP, npc.getX(), npc.getY() + 1.2, npc.getZ(),
						4, 0.25, 0.4, 0.25, 0.01);
			}
		}
	}

	/** Water runs from every shower whose faucet (a lever) is on. */
	private void runShowers() {
		ServerWorld world = world();
		for (BlockPos head : layout().showerHeads) {
			BlockState state = world.getBlockState(head);
			if (!state.isOf(Blocks.LEVER) || !state.get(net.minecraft.block.LeverBlock.POWERED)
					|| world.getClosestPlayer(head.getX(), head.getY(), head.getZ(), 48, false) == null) {
				continue;
			}
			double x = head.getX() + 0.5;
			double z = head.getZ() + 0.5;
			world.spawnParticles(net.minecraft.particle.ParticleTypes.FALLING_WATER, x, head.getY() + 0.9, z, 4, 0.18, 0.02, 0.18, 0);
			int floor = head.getY();
			while (floor > head.getY() - 5 && world.getBlockState(new BlockPos(head.getX(), floor - 1, head.getZ()))
					.getCollisionShape(world, head).isEmpty()) {
				floor--;
			}
			world.spawnParticles(net.minecraft.particle.ParticleTypes.SPLASH, x, floor + 0.1, z, 3, 0.25, 0, 0.25, 0.1);
			if (server.getTicks() % 30 == 0) {
				world.playSound(null, head, SoundEvents.WEATHER_RAIN, SoundCategory.BLOCKS, 0.25F, 1.9F + world.random.nextFloat() * 0.1F);
			}
		}
	}

	// ------------------------------------------------------------------ NPCs

	private Box npcArea() {
		BlockBox b = layout().bounds();
		return new Box(b.getMinX() - 16, world().getBottomY(), b.getMinZ() - 16, b.getMaxX() + 16, world().getTopY(),
				b.getMaxZ() + 16);
	}

	private List<GuardEntity> allGuards() {
		return world().getEntitiesByClass(GuardEntity.class, npcArea(), Entity::isAlive);
	}

	/** Walking NPC guards (not snipers). */
	public List<GuardEntity> npcGuards() {
		return world().getEntitiesByClass(GuardEntity.class, npcArea(), g -> g.isAlive() && !g.isSniper());
	}

	private List<GuardEntity> snipers() {
		return world().getEntitiesByClass(GuardEntity.class, npcArea(), g -> g.isAlive() && g.isSniper());
	}

	public List<PrisonerNpcEntity> npcPrisoners() {
		return world().getEntitiesByClass(PrisonerNpcEntity.class, npcArea(), Entity::isAlive);
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

	private void spawnSniper(BlockPos post) {
		GuardEntity sniper = CbContent.GUARD.create(world());
		if (sniper == null) {
			return;
		}
		sniper.refreshPositionAndAngles(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0, 0);
		sniper.initialize(world(), world().getLocalDifficulty(post), SpawnReason.EVENT, null);
		sniper.makeSniper();
		sniper.setCustomName(Text.literal("Снайпер"));
		world().spawnEntity(sniper);
	}

	private void spawnNpcPrisoner(int cellIndex, int number) {
		PrisonerNpcEntity npc = CbContent.PRISONER.create(world());
		if (npc == null) {
			return;
		}
		BlockPos pos = layout().cells.get(cellIndex).spawn;
		npc.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
		npc.initialize(world(), world().getLocalDifficulty(pos), SpawnReason.EVENT, null);
		npc.cell = cellIndex;
		// About one inmate in six trades (at most three); the second one always does.
		boolean trader = number % 6 == 1 && number / 6 < MAX_TRADERS;
		npc.setTrader(trader);
		String nick = nicknames.get(number % nicknames.size());
		npc.setCustomName(trader ? Text.literal(nick + " [торговец]").formatted(Formatting.GREEN)
				: Text.literal(nick).formatted(Formatting.GOLD));
		npc.setCustomNameVisible(trader);
		world().spawnEntity(npc);
	}

	/** Where an NPC inmate should head now; keeps the current destination if it still fits. */
	public BlockPos npcDestination(PrisonerNpcEntity npc, BlockPos current) {
		if (!running) {
			return null;
		}
		PrisonLayout layout = layout();
		if (npc.solitaryUntil != 0) {
			if (ticks < npc.solitaryUntil) {
				return layout.solitarySpawn;
			}
			npc.solitaryUntil = 0;
			current = null;
		}
		if (npc.infirmaryUntil != 0) {
			if (ticks < npc.infirmaryUntil && layout.infirmarySpawn != null) {
				return layout.infirmarySpawn;
			}
			npc.infirmaryUntil = 0;
			current = null;
		}
		if (npc.lineupSlot != null) {
			return npc.lineupSlot;
		}
		BlockPos cellSpawn = npc.cell >= 0 && npc.cell < layout.cells.size() ? layout.cells.get(npc.cell).spawn
				: layout.npcGuardSpawn;
		Phase phase = phase();
		if (phase == Phase.LIGHTS_OUT || phase == Phase.WAKE_UP) {
			return cellSpawn;
		}
		BlockBox zone = zoneBox(phase.zone);
		if (phase == Phase.SHOWER) {
			BlockPos spot = showerSpot(npc);
			if (spot != null) {
				return spot;
			}
		}
		if (phase.zone == Zone.CANTEEN && !npc.skipsMeal) {
			BlockPos meal = mealDestination(npc);
			if (meal != null) {
				return meal;
			}
		}
		if (zone != null && !(npc.skipsMeal && phase.zone == Zone.CANTEEN)) {
			return current != null && zone.contains(current) ? current : randomSpotIn(zone);
		}
		if (current != null) {
			return current;
		}
		Random r = world().random;
		int roll = r.nextInt(4);
		if (roll < 2 && !layout.patrolPoints.isEmpty()) {
			return layout.patrolPoints.get(r.nextInt(layout.patrolPoints.size()));
		}
		if (roll == 2 && layout.yardZone != null) {
			return randomSpotIn(layout.yardZone);
		}
		return cellSpawn;
	}

	/**
	 * Known walkable places (patrol points, the middle of zones) between a position and a goal,
	 * the closest to the goal first: stepping stones for routes too long for one path.
	 */
	public List<BlockPos> waypointsToward(BlockPos from, BlockPos goal) {
		PrisonLayout l = layout();
		List<BlockPos> points = new ArrayList<>(l.patrolPoints);
		for (BlockBox z : new BlockBox[] { l.workshopZone, l.canteenZone, l.rollCallZone, l.showerZone }) {
			if (z != null) {
				points.add(randomSpotIn(z));
			}
		}
		double own = from.getSquaredDistance(goal);
		return points.stream().filter(p -> p.getSquaredDistance(goal) < own && p.getSquaredDistance(from) > 9)
				.sorted(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(goal))).limit(4).toList();
	}

	private BlockPos randomSpotIn(BlockBox box) {
		ServerWorld world = world();
		Random r = world.random;
		for (int attempt = 0; attempt < 12; attempt++) {
			int x = box.getMinX() + r.nextInt(box.getBlockCountX());
			int z = box.getMinZ() + r.nextInt(box.getBlockCountZ());
			for (int y = box.getMaxY(); y >= box.getMinY(); y--) {
				BlockPos p = new BlockPos(x, y, z);
				BlockState below = world.getBlockState(p.down());
				if (!empty(world, p.down()) && empty(world, p) && empty(world, p.up()) && !below.isIn(BlockTags.LEAVES)
						&& !below.isIn(BlockTags.LOGS)) {
					return p;
				}
			}
		}
		return new BlockPos(box.getCenter().getX(), box.getMinY(), box.getCenter().getZ());
	}

	private void removeNpcs() {
		if (layout() == null) {
			return;
		}
		for (GuardEntity g : allGuards()) {
			g.discard();
		}
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			npc.discard();
		}
		for (CookEntity cook : world().getEntitiesByClass(CookEntity.class, npcArea(), e -> true)) {
			cook.discard();
		}
		standEveryoneUp();
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
		p.giveItemStack(new ItemStack(CbContent.MONEY, 2));
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

	/** Whether a guard who looks at the prisoner sees forbidden things in their hands (work tools at work are fine). */
	private boolean holdsContraband(ServerPlayerEntity p) {
		return isForbidden(p, p.getMainHandStack().getItem()) || isForbidden(p, p.getOffHandStack().getItem());
	}

	private boolean isForbidden(ServerPlayerEntity p, Item item) {
		if ((item == Items.IRON_NUGGET || item.getRegistryEntry().isIn(ItemTags.LOGS)) && phase() == Phase.WORK
				&& layout().workZone != null
				&& layout().workZone.expand(3).contains(p.getBlockPos())) {
			return false;
		}
		if (CbContent.WORK_TOOLS.contains(item) && phase() == Phase.WORK) {
			PrisonLayout l = layout();
			BlockPos pos = p.getBlockPos();
			if (l.workZone != null && l.workZone.expand(3).contains(pos)
					|| l.workshopZone != null && l.workshopZone.contains(pos)) {
				return false;
			}
		}
		return CbContent.isContraband(item);
	}

	private static int countContraband(PlayerEntity p) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (CbContent.isContraband(inv.getStack(i).getItem())) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	private static int count(PlayerEntity p, Item item) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) {
				n += inv.getStack(i).getCount();
			}
		}
		return n;
	}

	private static int removeContraband(PlayerEntity p) {
		int n = 0;
		PlayerInventory inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (CbContent.isContraband(inv.getStack(i).getItem())) {
				n += inv.getStack(i).getCount();
				inv.setStack(i, ItemStack.EMPTY);
			}
		}
		return n;
	}

	private static final Item[] SCRAP = { Items.STICK, Items.STICK, Items.STICK, Items.STRING, Items.STRING,
			Items.STRING, Items.FLINT, Items.FLINT, Items.IRON_NUGGET, Items.IRON_NUGGET, Items.IRON_NUGGET,
			Items.PAPER, Items.PAPER, Items.PAPER, Items.CLAY_BALL, Items.BREAD };

	private void refillScrap() {
		for (BlockPos pos : layout().scrapChests) {
			fillChest(pos, 5);
		}
	}

	private void fillChest(BlockPos pos, int stacks) {
		if (pos == null || !(world().getBlockEntity(pos) instanceof Inventory chest)
				|| !(chest instanceof net.minecraft.block.entity.ChestBlockEntity
						|| chest instanceof net.minecraft.block.entity.BarrelBlockEntity)) {
			return;
		}
		Random random = world().random;
		chest.clear();
		for (int i = 0; i < stacks; i++) {
			Item item = SCRAP[random.nextInt(SCRAP.length)];
			chest.setStack(random.nextInt(chest.size()), new ItemStack(item, 1 + random.nextInt(2)));
		}
	}

	/** Iron doors of service rooms (every one but the cells' and the day doors): locked, keys only. */
	private List<BlockPos> serviceDoors;

	private void lockServiceDoors() {
		PrisonLayout l = layout();
		if (serviceDoors == null) {
			java.util.Set<BlockPos> known = new java.util.HashSet<>(l.dayDoors);
			l.cells.forEach(c -> known.add(c.door));
			if (l.solitaryDoor != null) {
				known.add(l.solitaryDoor);
			}
			serviceDoors = new ArrayList<>();
			for (BlockBox a : l.areas) {
				for (BlockPos p : BlockPos.iterate(a.getMinX(), Math.max(a.getMinY(), l.breakMinY), a.getMinZ(), a.getMaxX(),
						a.getMaxY(), a.getMaxZ())) {
					BlockState s = world().getBlockState(p);
					if (s.isOf(Blocks.IRON_DOOR) && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !known.contains(p)) {
						serviceDoors.add(p.toImmutable());
						known.add(p.toImmutable());
					}
				}
			}
		}
		for (BlockPos p : serviceDoors) {
			setDoor(p, false);
		}
	}

	private void setDoors(boolean open) {
		setDoors(open, open);
	}

	/** Cell doors and indoor day doors follow cellsOpen; day doors that lead outdoors follow outdoorsOpen. */
	private void setDoors(boolean cellsOpen, boolean outdoorsOpen) {
		for (PrisonLayout.Cell c : layout().cells) {
			setDoor(c.door, cellsOpen);
		}
		for (BlockPos door : layout().dayDoors) {
			setDoor(door, isOutdoorDoor(door) ? outdoorsOpen && cellsOpen : cellsOpen);
		}
	}

	private final Map<BlockPos, Boolean> outdoorDoors = new HashMap<>();

	/** A door with open sky on one side of it. */
	private boolean isOutdoorDoor(BlockPos door) {
		return outdoorDoors.computeIfAbsent(door, d -> {
			BlockState st = world().getBlockState(d);
			if (!st.isOf(Blocks.IRON_DOOR)) {
				return false;
			}
			Direction f = st.get(DoorBlock.FACING);
			return world().isSkyVisible(d.offset(f).up()) || world().isSkyVisible(d.offset(f.getOpposite()).up());
		});
	}

	/**
	 * Lights out, after the walk back: inmates who are still on the way are let into their cells
	 * (behind everybody's back), then the doors are locked.
	 */
	private void lockUpForTheNight() {
		PrisonLayout layout = layout();
		for (PrisonerNpcEntity npc : npcPrisoners()) {
			if (npc.solitaryUntil == 0 && npc.infirmaryUntil == 0 && npc.cell >= 0 && npc.cell < layout.cells.size()
					&& !layout.cells.get(npc.cell).interior.contains(npc.getBlockPos())) {
				BlockPos s = layout.cells.get(npc.cell).spawn;
				npc.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, npc.getYaw(), 0);
				npc.getNavigation().stop();
			}
			if (npc.solitaryUntil == 0 && npc.infirmaryUntil == 0) {
				putToBed(npc);
			}
		}
		setDoors(false);
		for (ServerPlayerEntity p : allOnline()) {
			p.playSoundToPlayer(SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.MASTER, 1.0F, 0.8F);
			p.sendMessage(Text.literal("Двери заперты. Кто вне камеры — нарушитель.").formatted(Formatting.RED), true);
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
