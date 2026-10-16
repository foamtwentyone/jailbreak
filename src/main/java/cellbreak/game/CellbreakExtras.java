package cellbreak.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cellbreak.CbContent;
import cellbreak.entity.GuardDogEntity;
import cellbreak.entity.GuardEntity;
import cellbreak.entity.PrisonerNpcEntity;
import cellbreak.game.CellbreakGame.Phase;
import cellbreak.game.CellbreakGame.PlayerData;
import cellbreak.game.CellbreakGame.Role;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.EulerAngle;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;

/**
 * The 0.4 additions to a round: bed dummies, the guard disguise, rope and gate escapes, guard dogs,
 * CCTV, lock-downs, the snitch, team escapes, guard tools, ambient sounds, prison jobs, parcels from
 * home and the end-of-round statistics.
 */
public class CellbreakExtras {
	private final CellbreakGame game;

	CellbreakExtras(CellbreakGame game) {
		this.game = game;
	}

	private ServerWorld world() {
		return game.world();
	}

	private long ticks() {
		return game.ticks;
	}

	private Random random() {
		return world().random;
	}

	// ------------------------------------------------------------------ round lifecycle

	/** Per-player numbers for the summary at the end of the round. */
	static final class Stats {
		int solitary;
		int infirmary;
		int holes;
		int fights;
		int reports;
		int earned;
		int maxHeat;
		long escapedAt = -1;
		boolean teamEscape;
	}

	private final Map<UUID, Stats> stats = new HashMap<>();
	private UUID snitch;
	private long lastReport = -100000;
	private final Map<Integer, UUID> dummies = new HashMap<>();
	private final Set<UUID> dummyChecked = new HashSet<>();
	private final long[] cameraBlindUntil = new long[64];
	private final Map<UUID, Integer> viewing = new HashMap<>();
	private final Map<UUID, Vec3d> viewFrom = new HashMap<>();
	private final List<UUID> cameraEyes = new ArrayList<>();
	private long lockdownUntil;
	private long lockdownCellsAt;
	private boolean lockdownCellsShut;
	private long gateCloseTick = -1;
	private final Map<UUID, Climb> climbs = new HashMap<>();
	private final Map<UUID, long[]> jobReps = new HashMap<>();
	private final Set<UUID> sniffed = new HashSet<>();
	private long nextDogCheck;

	private record Climb(BlockPos top, Vec3d start, long until) {
	}

	Stats stats(UUID id) {
		return stats.computeIfAbsent(id, k -> new Stats());
	}

	void onStart() {
		stats.clear();
		dummies.clear();
		dummyChecked.clear();
		viewing.clear();
		viewFrom.clear();
		climbs.clear();
		jobReps.clear();
		java.util.Arrays.fill(cameraBlindUntil, 0);
		lockdownUntil = 0;
		lockdownCellsShut = false;
		gateCloseTick = -1;
		lastReport = -100000;
		removeLeftovers();
		// The snitch: one prisoner in three or more works for the guards, in secret.
		List<ServerPlayerEntity> prisoners = game.online(Role.PRISONER);
		snitch = null;
		if (prisoners.size() >= 3) {
			ServerPlayerEntity s = prisoners.get(random().nextInt(prisoners.size()));
			snitch = s.getUuid();
			CellbreakGame.title(s, Text.literal("Ты — стукач").formatted(Formatting.DARK_PURPLE),
					Text.literal("Тайно работаешь на охрану. Не выдай себя!"));
			s.sendMessage(Text.literal("Ты стукач. Докладывай охране: присядь и ПКМ по охраннику, или /cellbreak donos <ник> "
					+ "рядом с охраной. За дело — деньги, подозрение к тебе растёт вдвое медленнее. Тебя могут заметить.")
					.formatted(Formatting.LIGHT_PURPLE));
			for (ServerPlayerEntity g : game.online(Role.GUARD)) {
				g.sendMessage(Text.literal("Наш человек среди заключённых: " + s.getName().getString() + ". Не раскрывай его.")
						.formatted(Formatting.LIGHT_PURPLE));
			}
		}
		spawnDogs();
		spawnCameraEyes();
		fillLocker();
	}

	void onStop() {
		for (UUID id : new ArrayList<>(viewing.keySet())) {
			ServerPlayerEntity p = game.server.getPlayerManager().getPlayer(id);
			if (p != null) {
				stopViewing(p);
			}
		}
		broadcastStats();
		removeLeftovers();
	}

	private void removeLeftovers() {
		if (game.layout() == null) {
			return;
		}
		for (Entity e : world().getEntitiesByClass(Entity.class, game.npcArea(),
				e -> e instanceof GuardDogEntity || e.getCommandTags().contains(TAG_DUMMY) || e.getCommandTags().contains(TAG_EYE)
						|| e.getCommandTags().contains(TAG_EXTRA))) {
			e.discard();
		}
		cameraEyes.clear();
		dummies.clear();
	}

	private static final String TAG_DUMMY = "cellbreak_dummy";
	private static final String TAG_EYE = "cellbreak_cam";
	private static final String TAG_EXTRA = "cellbreak_extra";

	void tick() {
		long t = ticks();
		if (t % 10 == 0) {
			disguiseChecks();
			cameraChecks();
			lockdownTick();
			climbTick();
			viewTick();
		}
		if (t % 10 == 5) {
			dogTick();
		}
		if (gateCloseTick > 0 && t >= gateCloseTick) {
			gateCloseTick = -1;
			for (BlockPos d : gateDoors()) {
				game.setDoor(d, false);
			}
		}
		ambientSounds();
		for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
			PlayerData d = game.data(p);
			if (d != null) {
				Stats s = stats(p.getUuid());
				s.maxHeat = Math.max(s.maxHeat, (int) d.heat);
			}
		}
	}

	void onPhase(Phase phase) {
		sniffed.clear();
		if (phase == Phase.LIGHTS_OUT) {
			dummyChecked.clear();
		}
		// Parcels from home every other day, at free time.
		if (phase == Phase.FREE_TIME && game.day % 2 == 0) {
			for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
				PlayerData d = game.data(p);
				if (d.escaped || d.solitaryUntil != 0) {
					continue;
				}
				boolean inspected = random().nextFloat() < 0.35F;
				ItemStack parcel = new ItemStack(CbContent.PARCEL);
				NbtCompound tag = new NbtCompound();
				tag.putBoolean("inspected", inspected);
				parcel.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
				parcel.set(DataComponentTypes.CUSTOM_NAME, Text.literal(inspected ? "Посылка с воли (вскрыта охраной)" : "Посылка с воли")
						.styled(st -> st.withItalic(false)));
				p.giveItemStack(parcel);
				p.sendMessage(Text.literal(inspected ? "Тебе пришла посылка. Охрана её вскрыла и проверила."
						: "Тебе пришла посылка с воли! ПКМ — открыть.").formatted(Formatting.AQUA));
				p.playSoundToPlayer(SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0F, 0.8F);
			}
		}
	}

	// ------------------------------------------------------------------ 1. the bed dummy

	/** Right click on a bed: lay a dummy in your own bed, or (sneaking, empty hand) take it back. */
	public boolean useBed(ServerPlayerEntity p, BlockPos bed) {
		PlayerData d = game.data(p);
		if (!game.isRunning() || d == null || d.role != Role.PRISONER || d.cell < 0) {
			return false;
		}
		PrisonLayout.Cell cell = game.layout().cells.get(d.cell);
		if (!cell.interior.expand(1).contains(bed)) {
			return false;
		}
		ItemStack hand = p.getMainHandStack();
		if (hand.isOf(CbContent.DUMMY)) {
			if (dummies.containsKey(d.cell)) {
				p.sendMessage(Text.literal("Кукла уже лежит в кровати.").formatted(Formatting.GRAY), true);
				return true;
			}
			ArmorStandEntity stand = new ArmorStandEntity(world(), bed.getX() + 0.5, bed.getY() - 0.92, bed.getZ() + 0.5);
			NbtCompound nbt = new NbtCompound();
			stand.writeCustomDataToNbt(nbt);
			nbt.putBoolean("Marker", true);
			nbt.putBoolean("Invisible", true);
			nbt.putBoolean("NoGravity", true);
			nbt.putBoolean("Invulnerable", true);
			stand.readCustomDataFromNbt(nbt);
			stand.setHeadRotation(new EulerAngle(270, 0, 0));
			stand.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.PLAYER_HEAD));
			stand.addCommandTag(TAG_DUMMY);
			world().spawnEntity(stand);
			dummies.put(d.cell, stand.getUuid());
			hand.decrement(1);
			p.sendMessage(Text.literal("Ты уложил куклу под одеяло. Ночной обход может и не заметить...").formatted(Formatting.GREEN), true);
			return true;
		}
		if (hand.isEmpty() && p.isSneaking() && dummies.containsKey(d.cell)) {
			Entity e = world().getEntity(dummies.remove(d.cell));
			if (e != null) {
				e.discard();
			}
			p.giveItemStack(new ItemStack(CbContent.DUMMY));
			p.sendMessage(Text.literal("Ты забрал куклу.").formatted(Formatting.GRAY), true);
			return true;
		}
		return false;
	}

	/**
	 * A guard on the night round looks into an empty player cell: a dummy in the bed fools them
	 * four times out of five (one look per night).
	 */
	boolean dummyFools(ServerPlayerEntity p, PlayerData d, GuardEntity guard) {
		if (!dummies.containsKey(d.cell)) {
			return false;
		}
		if (dummyChecked.contains(p.getUuid())) {
			return true;
		}
		dummyChecked.add(p.getUuid());
		if (random().nextFloat() < 0.8F) {
			return true;
		}
		Entity e = world().getEntity(dummies.remove(d.cell));
		if (e != null) {
			e.discard();
		}
		game.guardSays(guard, "Это же кукла! Подушка в одеяле!");
		return false;
	}

	// ------------------------------------------------------------------ 2. the guard disguise

	/** A prisoner in a guard's cap and jacket (navy leather): passes for a guard from a distance. */
	public static boolean disguised(net.minecraft.entity.player.PlayerEntity p) {
		return isUniform(p.getEquippedStack(EquipmentSlot.CHEST)) && isUniform(p.getEquippedStack(EquipmentSlot.HEAD));
	}

	private static boolean isUniform(ItemStack s) {
		DyedColorComponent c = s.get(DataComponentTypes.DYED_COLOR);
		return c != null && c.rgb() == CellbreakGame.GUARD_NAVY
				&& (s.isOf(Items.LEATHER_CHESTPLATE) || s.isOf(Items.LEATHER_HELMET) || s.isOf(Items.LEATHER_LEGGINGS));
	}

	/** Whether a watcher takes this prisoner for a colleague (too far away to tell). */
	boolean passesAsGuard(ServerPlayerEntity p, Entity watcher) {
		PlayerData d = game.data(p);
		return d != null && !d.wanted && disguised(p) && watcher.squaredDistanceTo(p) > 3 * 3;
	}

	static ItemStack uniform(Item piece) {
		ItemStack s = CellbreakGame.dyed(piece, CellbreakGame.GUARD_NAVY);
		s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Форма охранника").styled(st -> st.withItalic(false)));
		return s;
	}

	/** Up close a guard sees through the disguise. */
	private void disguiseChecks() {
		for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
			PlayerData d = game.data(p);
			if (d == null || d.wanted || !disguised(p)) {
				continue;
			}
			for (GuardEntity g : game.npcGuards()) {
				if (g.squaredDistanceTo(p) < 3 * 3 && g.canSee(p) && random().nextFloat() < 0.3F) {
					game.guardSays(g, "Стоп. Ты не из наших! " + p.getName().getString() + "?! Снимай форму!");
					for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS }) {
						if (isUniform(p.getEquippedStack(slot))) {
							p.equipStack(slot, ItemStack.EMPTY);
						}
					}
					game.addHeat(p, CellbreakGame.MAX_HEAT);
					lockdown("заключённый в форме охраны");
					break;
				}
			}
		}
	}

	private void fillLocker() {
		BlockPos l = game.layout().locker;
		if (l != null && world().getBlockEntity(l) instanceof net.minecraft.inventory.Inventory inv) {
			inv.clear();
			inv.setStack(4, uniform(Items.LEATHER_HELMET));
			inv.setStack(13, uniform(Items.LEATHER_CHESTPLATE));
			inv.setStack(22, uniform(Items.LEATHER_LEGGINGS));
			if (random().nextFloat() < 0.25F) {
				inv.setStack(26, new ItemStack(CbContent.GATE_KEY));
			}
		}
	}

	// ------------------------------------------------------------------ 3. escape routes: rope, gate

	public boolean isGateDoor(BlockPos pos) {
		BlockPos post = game.layout() == null ? null : game.layout().gatePost;
		return post != null && pos.getSquaredDistance(post) < 7 * 7 && world().getBlockState(pos).isOf(Blocks.IRON_DOOR);
	}

	private List<BlockPos> gateDoors() {
		List<BlockPos> list = new ArrayList<>();
		BlockPos post = game.layout().gatePost;
		if (post == null) {
			return list;
		}
		for (BlockPos p : BlockPos.iterate(post.add(-7, -3, -7), post.add(7, 3, 7))) {
			BlockState s = world().getBlockState(p);
			if (s.isOf(Blocks.IRON_DOOR) && s.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
				list.add(p.toImmutable());
			}
		}
		return list;
	}

	/** The gate key: the heavy gate opens only with a second prisoner at the winch. */
	public boolean useGateKey(ServerPlayerEntity p, BlockPos door) {
		if (!game.isRunning() || !isGateDoor(door)) {
			return false;
		}
		ServerPlayerEntity helper = null;
		for (ServerPlayerEntity o : game.online(Role.PRISONER)) {
			if (o != p && o.squaredDistanceTo(p) < 4 * 4 && !game.data(o).escaped) {
				helper = o;
			}
		}
		if (helper == null) {
			p.sendMessage(Text.literal("Ворота тяжёлые: ключ повернул, но одному не открыть — нужен второй у ворота.")
					.formatted(Formatting.GOLD), true);
			return true;
		}
		for (BlockPos d : gateDoors()) {
			game.setDoor(d, true);
		}
		gateCloseTick = ticks() + 8 * 20;
		world().playSound(null, door, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 2.0F, 0.6F);
		for (ServerPlayerEntity o : List.of(p, helper)) {
			o.sendMessage(Text.literal("Ворота открыты на 8 секунд! Бегом!").formatted(Formatting.GREEN), true);
		}
		game.makeNoise(door, door);
		return true;
	}

	/** Rope with a hook thrown up a wall: four seconds of climbing (loud), then you are on top. */
	public boolean useRope(ServerPlayerEntity p, BlockHitResult hit) {
		if (!game.isRunning() || hit.getSide().getAxis() == Direction.Axis.Y || !game.isPrisoner(p)) {
			return false;
		}
		BlockPos wall = hit.getBlockPos();
		BlockPos top = null;
		for (int dy = 1; dy <= 26; dy++) {
			BlockPos q = wall.up(dy);
			if (game.empty(world(), q) && game.empty(world(), q.up()) && !game.empty(world(), q.down())) {
				top = q;
				break;
			}
		}
		if (top == null || top.getY() - p.getBlockY() < 2) {
			p.sendMessage(Text.literal("Тут не за что зацепиться.").formatted(Formatting.GRAY), true);
			return true;
		}
		climbs.put(p.getUuid(), new Climb(top, p.getPos(), ticks() + 4 * 20));
		world().playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.0F, 0.8F);
		p.sendMessage(Text.literal("Крюк зацепился. Лезешь вверх... не двигайся 4 секунды.").formatted(Formatting.GOLD), true);
		return true;
	}

	private void climbTick() {
		for (var it = climbs.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayerEntity p = game.server.getPlayerManager().getPlayer(e.getKey());
			Climb c = e.getValue();
			if (p == null || p.getPos().squaredDistanceTo(c.start()) > 1.5) {
				if (p != null) {
					p.sendMessage(Text.literal("Ты сорвался с верёвки.").formatted(Formatting.RED), true);
				}
				it.remove();
				continue;
			}
			world().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 1.2F, 0.7F);
			game.makeNoise(p.getBlockPos(), p.getBlockPos());
			if (ticks() >= c.until()) {
				it.remove();
				p.teleport(world(), c.top().getX() + 0.5, c.top().getY(), c.top().getZ() + 0.5, p.getYaw(), p.getPitch());
				ItemStack rope = p.getMainHandStack();
				if (rope.isOf(CbContent.ROPE_HOOK) && random().nextFloat() < 0.5F) {
					rope.decrement(1);
					p.sendMessage(Text.literal("Ты наверху! Верёвка оборвалась.").formatted(Formatting.GREEN), true);
				} else {
					p.sendMessage(Text.literal("Ты наверху!").formatted(Formatting.GREEN), true);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 6. guard dogs

	private static final String[] DOG_NAMES = { "Мухтар", "Рекс" };

	private void spawnDogs() {
		BlockPos at = game.layout().npcGuardSpawn;
		for (String name : DOG_NAMES) {
			GuardDogEntity dog = CbContent.DOG.create(world());
			if (dog == null) {
				continue;
			}
			dog.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			dog.setCustomName(Text.literal(name).formatted(Formatting.GOLD));
			world().spawnEntity(dog);
		}
	}

	private List<GuardDogEntity> dogs() {
		return world().getEntitiesByClass(GuardDogEntity.class, game.npcArea(), Entity::isAlive);
	}

	private void dogTick() {
		List<GuardDogEntity> dogs = dogs();
		if (dogs.size() < DOG_NAMES.length && ticks() > nextDogCheck) {
			nextDogCheck = ticks() + 120 * 20;
			if (dogs.isEmpty()) {
				spawnDogs();
			}
		}
		Phase phase = game.phase();
		List<GuardEntity> guards = game.npcGuards();
		for (int i = 0; i < dogs.size(); i++) {
			GuardDogEntity dog = dogs.get(i);
			ServerPlayerEntity prey = null;
			double best = 40 * 40;
			for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
				PlayerData d = game.data(p);
				if (d.wanted && d.solitaryUntil == 0 && !d.escaped && p.squaredDistanceTo(dog) < best) {
					prey = p;
					best = p.squaredDistanceTo(dog);
				}
			}
			if (prey != null) {
				// On the scent: runs the wanted prisoner down and gives them away.
				dog.setTarget(prey);
				if (best < 20 * 20) {
					prey.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
				}
				if (ticks() % 40 < 10) {
					dog.playSound(SoundEvents.ENTITY_WOLF_GROWL, 1.5F, 1.0F);
				}
				continue;
			}
			dog.setTarget(null);
			if (phase.isRollCall() && game.phaseElapsed() > 15 * 20) {
				sniffLineup(dog, i);
				continue;
			}
			if (!guards.isEmpty()) {
				GuardEntity g = guards.get(i % guards.size());
				if (dog.squaredDistanceTo(g) > 5 * 5) {
					dog.getNavigation().startMovingTo(g, 1.2);
				}
			}
		}
	}

	/** At roll call the dogs walk along the line-up and sniff every prisoner for contraband. */
	private void sniffLineup(GuardDogEntity dog, int index) {
		List<ServerPlayerEntity> line = new ArrayList<>(game.online(Role.PRISONER));
		line.removeIf(p -> sniffed.contains(p.getUuid()) || game.data(p).solitaryUntil != 0 || game.data(p).escaped);
		if (line.isEmpty()) {
			return;
		}
		line.sort(java.util.Comparator.comparingDouble(p -> p.squaredDistanceTo(dog)));
		ServerPlayerEntity next = line.get(Math.min(index, line.size() - 1));
		if (dog.squaredDistanceTo(next) > 2.5 * 2.5) {
			dog.getNavigation().startMovingTo(next, 1.0);
			return;
		}
		sniffed.add(next.getUuid());
		dog.getLookControl().lookAt(next);
		if (CellbreakGame.countContraband(next) > 0) {
			dog.playSound(SoundEvents.ENTITY_WOLF_GROWL, 2.0F, 0.9F);
			world().playSound(null, dog.getBlockPos(), SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.HOSTILE, 2.0F, 1.2F);
			game.addHeat(next, 40);
			next.sendMessage(Text.literal(dog.getName().getString() + " учуял у тебя что-то запрещённое! Подозрение +40")
					.formatted(Formatting.RED));
		} else {
			dog.playSound(SoundEvents.ENTITY_WOLF_PANT, 0.8F, 1.0F);
		}
	}

	// ------------------------------------------------------------------ 7. CCTV

	private Direction cameraFacing(int i) {
		return Direction.byId(game.layout().cameraFacing.get(i));
	}

	private void spawnCameraEyes() {
		cameraEyes.clear();
		List<BlockPos> cams = game.layout().cameras;
		for (int i = 0; i < cams.size(); i++) {
			Direction f = cameraFacing(i);
			Vec3d at = Vec3d.ofCenter(cams.get(i)).add(Vec3d.of(f.getVector()).multiply(0.75)).add(0, -1.6, 0);
			ArmorStandEntity eye = new ArmorStandEntity(world(), at.x, at.y, at.z);
			NbtCompound nbt = new NbtCompound();
			eye.writeCustomDataToNbt(nbt);
			nbt.putBoolean("Marker", true);
			nbt.putBoolean("Invisible", true);
			nbt.putBoolean("NoGravity", true);
			nbt.putBoolean("Invulnerable", true);
			eye.readCustomDataFromNbt(nbt);
			float yaw = f.getAxis().isHorizontal() ? f.asRotation() : 0;
			eye.refreshPositionAndAngles(at.x, at.y, at.z, yaw, f == Direction.DOWN ? 60 : 20);
			eye.setHeadYaw(yaw);
			eye.addCommandTag(TAG_EYE);
			world().spawnEntity(eye);
			cameraEyes.add(eye.getUuid());
		}
	}

	public int cameraAt(BlockPos pos) {
		return game.layout() == null ? -1 : game.layout().cameras.indexOf(pos);
	}

	/** A rag over the lens: the camera is blind for three minutes. */
	public boolean blindCamera(ServerPlayerEntity p, BlockPos pos) {
		int i = cameraAt(pos);
		if (i < 0 || !game.isRunning()) {
			return false;
		}
		cameraBlindUntil[i] = ticks() + 180 * 20;
		p.getMainHandStack().damage(1, p, EquipmentSlot.MAINHAND);
		world().playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
		p.sendMessage(Text.literal("Ты завесил камеру тряпкой. Три минуты она ничего не видит.").formatted(Formatting.GREEN), true);
		if (game.isSeen(p, game.npcGuards(), game.online(Role.GUARD))) {
			game.addHeat(p, 25);
			p.sendMessage(Text.literal("Охрана видела, как ты закрыл камеру! Подозрение +25").formatted(Formatting.RED));
		}
		return true;
	}

	private boolean cameraSees(int i, ServerPlayerEntity p) {
		BlockPos cam = game.layout().cameras.get(i);
		Direction f = cameraFacing(i);
		Vec3d eye = Vec3d.ofCenter(cam).add(Vec3d.of(f.getVector()).multiply(0.6));
		Vec3d to = p.getEyePos().subtract(eye);
		if (to.lengthSquared() > 20 * 20 || to.normalize().dotProduct(Vec3d.of(f.getVector())) < 0.55) {
			return false;
		}
		BlockHitResult hit = world().raycast(new RaycastContext(eye, p.getEyePos(), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, p));
		return hit.getType() == HitResult.Type.MISS;
	}

	private final Map<UUID, Long> cameraWarned = new HashMap<>();
	private final long[] cameraCalled = new long[64];

	/** Cameras watch the prisoners like guards that never blink: wrong place, contraband, night. */
	private void cameraChecks() {
		List<BlockPos> cams = game.layout().cameras;
		if (cams.isEmpty()) {
			return;
		}
		Phase phase = game.phase();
		for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
			PlayerData d = game.data(p);
			if (d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0 || disguised(p) && !d.wanted) {
				continue;
			}
			boolean wrongPlace = phase.zone != CellbreakGame.Zone.ANYWHERE && game.phaseElapsed() > CellbreakGame.GRACE_SECONDS * 20
					&& !game.isInPlace(p, d, phase);
			boolean night = phase == Phase.LIGHTS_OUT && game.doorsLocked;
			boolean bad = wrongPlace || night || CellbreakGame.countContraband(p) > 0 && p.getMainHandStack().getItem() != Items.AIR
					&& CbContent.isContraband(p.getMainHandStack().getItem());
			if (!bad) {
				continue;
			}
			for (int i = 0; i < cams.size(); i++) {
				if (ticks() < cameraBlindUntil[i] || !cameraSees(i, p)) {
					continue;
				}
				game.addHeat(p, night ? 10 : 3);
				d.lastSeenTick = ticks();
				d.lastSeenPos = p.getBlockPos();
				if (ticks() - cameraWarned.getOrDefault(p.getUuid(), -10000L) > 10 * 20) {
					cameraWarned.put(p.getUuid(), ticks());
					p.sendMessage(Text.literal("Тебя видит камера наблюдения!").formatted(Formatting.RED));
					for (ServerPlayerEntity g : game.online(Role.GUARD)) {
						g.sendMessage(Text.literal("[Камера " + (i + 1) + "] " + p.getName().getString() + " — " + p.getBlockPos().toShortString())
								.formatted(Formatting.YELLOW));
					}
				}
				if (ticks() - cameraCalled[i] > 15 * 20) {
					cameraCalled[i] = ticks();
					game.npcGuards().stream().filter(g -> !g.hasDuty() || g.squaredDistanceTo(p) < 30 * 30)
							.min(java.util.Comparator.comparingDouble(g -> g.squaredDistanceTo(p)))
							.ifPresent(g -> g.goCheck(p.getBlockPos(), p.getBlockPos(), 15));
				}
				break;
			}
		}
		for (int i = 0; i < cams.size(); i++) {
			if (ticks() < cameraBlindUntil[i] && ticks() % 40 == 0) {
				Vec3d c = Vec3d.ofCenter(cams.get(i));
				world().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, c.x, c.y, c.z, 2, 0.2, 0.2, 0.2, 0);
			}
		}
	}

	/** The monitor in the guard room: a guard (player) watches through the cameras. */
	public boolean useMonitor(ServerPlayerEntity p, BlockPos pos) {
		if (!game.isRunning() || game.layout().monitor == null || !game.layout().monitor.equals(pos) || !game.isGuard(p)) {
			return false;
		}
		if (cameraEyes.isEmpty()) {
			p.sendMessage(Text.literal("Камер нет.").formatted(Formatting.GRAY), true);
			return true;
		}
		viewFrom.put(p.getUuid(), p.getPos());
		showCamera(p, 0);
		return true;
	}

	public boolean nextCamera(ServerPlayerEntity p) {
		Integer i = viewing.get(p.getUuid());
		if (i == null) {
			return false;
		}
		showCamera(p, (i + 1) % cameraEyes.size());
		return true;
	}

	private void showCamera(ServerPlayerEntity p, int i) {
		Entity eye = world().getEntity(cameraEyes.get(i));
		if (eye == null) {
			return;
		}
		viewing.put(p.getUuid(), i);
		p.setCameraEntity(eye);
		p.sendMessage(Text.literal("Камера " + (i + 1) + "/" + cameraEyes.size()
				+ (ticks() < cameraBlindUntil[i] ? " — ЗАКРЫТА!" : "") + ". ПКМ — следующая, присесть — выйти.")
				.formatted(Formatting.YELLOW), true);
	}

	private void stopViewing(ServerPlayerEntity p) {
		viewing.remove(p.getUuid());
		p.setCameraEntity(p);
		Vec3d back = viewFrom.remove(p.getUuid());
		if (back != null) {
			p.teleport(world(), back.x, back.y, back.z, p.getYaw(), p.getPitch());
		}
	}

	private void viewTick() {
		for (UUID id : new ArrayList<>(viewing.keySet())) {
			ServerPlayerEntity p = game.server.getPlayerManager().getPlayer(id);
			if (p == null) {
				viewing.remove(id);
				continue;
			}
			if (p.getCameraEntity() == p || p.isSneaking()) {
				stopViewing(p);
			}
		}
	}

	// ------------------------------------------------------------------ 8. lock-down

	public boolean lockdownActive() {
		return ticks() < lockdownUntil;
	}

	public long lockdownLeft() {
		return Math.max(0, lockdownUntil - ticks());
	}

	/** Something serious happened: everybody to their cells, doors shut, more guards, for three minutes. */
	public void lockdown(String reason) {
		boolean fresh = !lockdownActive();
		lockdownUntil = ticks() + 180 * 20;
		if (!fresh) {
			return;
		}
		lockdownCellsAt = ticks() + 30 * 20;
		lockdownCellsShut = false;
		for (ServerPlayerEntity p : game.allOnline()) {
			CellbreakGame.title(p, Text.literal("ИЗОЛЯЦИЯ").formatted(Formatting.DARK_RED),
					Text.literal(reason + ". Всем по камерам! 30 секунд."));
			p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 1.0F, 1.0F);
		}
		game.broadcast(Text.literal("Изоляция (" + reason + "): все по камерам, двери заперты на 3 минуты, усиленная охрана.")
				.formatted(Formatting.DARK_RED));
		applyLockdownDoors();
		for (int i = 0; i < 2; i++) {
			int before = game.npcGuards().size();
			game.spawnNpcGuard();
			List<GuardEntity> now = game.npcGuards();
			if (now.size() > before) {
				for (GuardEntity g : now) {
					if (g.age < 2) {
						g.addCommandTag(TAG_EXTRA);
					}
				}
			}
		}
	}

	/** During a lock-down: cells open for the first half minute (to get in), everything else shut. */
	void applyLockdownDoors() {
		PrisonLayout l = game.layout();
		boolean cellsOpen = ticks() < lockdownCellsAt;
		for (PrisonLayout.Cell c : l.cells) {
			game.setDoor(c.door, cellsOpen);
		}
		for (BlockPos d : l.dayDoors) {
			game.setDoor(d, false);
		}
	}

	private void lockdownTick() {
		if (lockdownUntil == 0) {
			return;
		}
		if (lockdownActive()) {
			if (!lockdownCellsShut && ticks() >= lockdownCellsAt) {
				lockdownCellsShut = true;
				applyLockdownDoors();
				for (ServerPlayerEntity p : game.allOnline()) {
					p.sendMessage(Text.literal("Камеры заперты. Кто снаружи — нарушитель.").formatted(Formatting.RED), true);
				}
			}
			if (lockdownCellsShut) {
				// Out of your cell while the prison is locked down: straight onto the wanted list.
				for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
					PlayerData d = game.data(p);
					if (d.wanted || d.escaped || d.solitaryUntil != 0 || d.infirmaryUntil != 0 || d.cell < 0) {
						continue;
					}
					if (!game.layout().cells.get(d.cell).interior.expand(1).contains(p.getBlockPos())
							&& game.isSeen(p, game.npcGuards(), game.online(Role.GUARD))) {
						game.addHeat(p, CellbreakGame.MAX_HEAT);
					}
				}
			}
			return;
		}
		// Over: the extra guards leave, the routine goes on.
		lockdownUntil = 0;
		for (Entity e : world().getEntitiesByClass(GuardEntity.class, game.npcArea(), g -> g.getCommandTags().contains(TAG_EXTRA))) {
			e.discard();
		}
		game.reapplyDoors();
		game.broadcast(Text.literal("Изоляция снята. Распорядок продолжается.").formatted(Formatting.GREEN));
	}

	// ------------------------------------------------------------------ 9. the snitch

	public boolean isSnitch(net.minecraft.entity.player.PlayerEntity p) {
		return p.getUuid().equals(snitch);
	}

	/** The snitch tells a guard about another prisoner. */
	public void report(ServerPlayerEntity s, ServerPlayerEntity target) {
		if (!isSnitch(s)) {
			s.sendMessage(Text.literal("Ты не стукач.").formatted(Formatting.GRAY), true);
			return;
		}
		if (target == null || target == s || !game.isPrisoner(target)) {
			s.sendMessage(Text.literal("Не на кого доложить.").formatted(Formatting.GRAY), true);
			return;
		}
		GuardEntity guard = game.npcGuards().stream().filter(g -> g.squaredDistanceTo(s) < 8 * 8)
				.min(java.util.Comparator.comparingDouble(g -> g.squaredDistanceTo(s))).orElse(null);
		boolean playerGuard = game.online(Role.GUARD).stream().anyMatch(g -> g.squaredDistanceTo(s) < 8 * 8);
		if (guard == null && !playerGuard) {
			s.sendMessage(Text.literal("Рядом нет охранника — некому доложить.").formatted(Formatting.GRAY), true);
			return;
		}
		if (ticks() - lastReport < 60 * 20) {
			s.sendMessage(Text.literal("Слишком часто — охрана заподозрит. Подожди минуту.").formatted(Formatting.GRAY), true);
			return;
		}
		lastReport = ticks();
		boolean dirty = CellbreakGame.countContraband(target) > 0;
		game.addHeat(target, dirty ? 45 : 15);
		if (guard != null) {
			game.guardSays(guard, "Понял. Проверим " + target.getName().getString() + ".");
			guard.goCheck(target.getBlockPos(), target.getBlockPos(), 20);
		}
		s.giveItemStack(new ItemStack(CbContent.MONEY, dirty ? 6 : 2));
		stats(s.getUuid()).reports++;
		stats(s.getUuid()).earned += dirty ? 6 : 2;
		s.sendMessage(Text.literal("Ты доложил на " + target.getName().getString() + (dirty ? ". У него было что-то запрещённое — +6 денег."
				: ". Ничего не нашли — +2 денег.")).formatted(Formatting.LIGHT_PURPLE));
		for (ServerPlayerEntity g : game.online(Role.GUARD)) {
			g.sendMessage(Text.literal("[Наводка] " + target.getName().getString() + (dirty ? " — при нём контрабанда." : " — проверить."))
					.formatted(Formatting.LIGHT_PURPLE));
		}
		// Somebody may have seen the whispering.
		for (ServerPlayerEntity o : game.online(Role.PRISONER)) {
			if (o != s && o.squaredDistanceTo(s) < 12 * 12 && o.canSee(s) && random().nextFloat() < 0.6F) {
				o.sendMessage(Text.literal("Ты видел, как " + s.getName().getString() + " шептался с охраной...")
						.formatted(Formatting.DARK_PURPLE));
			}
		}
	}

	/** The snitch sneaks up to a guard and right-clicks: tells on the most suspicious prisoner nearby. */
	public boolean reportToGuard(ServerPlayerEntity s) {
		if (!isSnitch(s) || !s.isSneaking()) {
			return false;
		}
		ServerPlayerEntity target = game.online(Role.PRISONER).stream()
				.filter(o -> o != s && !game.data(o).escaped && o.squaredDistanceTo(s) < 40 * 40)
				.max(java.util.Comparator.comparingDouble(o -> game.data(o).heat + CellbreakGame.countContraband(o) * 30))
				.orElse(null);
		report(s, target);
		return true;
	}

	double heatFactor(ServerPlayerEntity p, double amount) {
		return isSnitch(p) && amount < CellbreakGame.MAX_HEAT ? amount * 0.5 : amount;
	}

	// ------------------------------------------------------------------ 10. team escapes

	void onEscape(ServerPlayerEntity p) {
		Stats mine = stats(p.getUuid());
		mine.escapedAt = ticks();
		for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
			if (!e.getKey().equals(p.getUuid()) && e.getValue().escapedAt >= 0 && ticks() - e.getValue().escapedAt < 60 * 20) {
				mine.teamEscape = true;
				e.getValue().teamEscape = true;
				game.broadcast(Text.literal("КОМАНДНЫЙ ПОБЕГ! " + p.getName().getString() + " и " + game.name(e.getKey())
						+ " ушли вместе!").formatted(Formatting.GOLD, Formatting.BOLD));
			}
		}
		lockdown("побег " + p.getName().getString());
	}

	// ------------------------------------------------------------------ 11. guard tools

	/** The guards' tablet: every prisoner, where they are and how suspicious. */
	public boolean useTablet(ServerPlayerEntity g) {
		if (!game.isGuard(g)) {
			return false;
		}
		g.sendMessage(Text.literal("— Планшет охраны —").formatted(Formatting.BLUE));
		for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
			PlayerData d = game.data(p);
			String where = d.escaped ? "СБЕЖАЛ" : d.solitaryUntil != 0 ? "карцер" : d.infirmaryUntil != 0 ? "медпункт"
					: d.lastSeenPos != null && ticks() - d.lastSeenTick < 60 * 20 ? "видели у " + d.lastSeenPos.toShortString()
					: "нет данных";
			g.sendMessage(Text.literal(String.format("%s: подозрение %d%%%s, %s", p.getName().getString(), (int) d.heat,
					d.wanted ? ", РОЗЫСК" : "", where)).formatted(d.wanted ? Formatting.RED : Formatting.WHITE));
		}
		if (lockdownActive()) {
			g.sendMessage(Text.literal("Изоляция: ещё " + lockdownLeft() / 20 + " с").formatted(Formatting.DARK_RED));
		}
		return true;
	}

	/** Guard chat starting with "!" goes over the radio: only the guards hear it. */
	public boolean radio(ServerPlayerEntity sender, String message) {
		if (!game.isRunning() || !game.isGuard(sender) || !message.startsWith("!")) {
			return false;
		}
		Text line = Text.literal("[Рация] " + sender.getName().getString() + ": ").formatted(Formatting.BLUE)
				.append(Text.literal(message.substring(1).trim()).formatted(Formatting.WHITE));
		for (ServerPlayerEntity g : game.online(Role.GUARD)) {
			g.sendMessage(line);
			g.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.4F, 1.6F);
		}
		return true;
	}

	// ------------------------------------------------------------------ 12. sounds

	private void ambientSounds() {
		long t = ticks();
		Phase phase = game.phase();
		if (phase.zone == CellbreakGame.Zone.CANTEEN && t % 50 == 0) {
			// The canteen hums: low voices from the tables.
			List<PrisonerNpcEntity> npcs = game.npcPrisoners();
			if (!npcs.isEmpty()) {
				PrisonerNpcEntity n = npcs.get(random().nextInt(npcs.size()));
				if (game.zoneBox(CellbreakGame.Zone.CANTEEN) != null && game.zoneBox(CellbreakGame.Zone.CANTEEN).contains(n.getBlockPos())) {
					world().playSound(null, n.getBlockPos(), SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.35F,
							0.55F + random().nextFloat() * 0.2F);
				}
			}
		}
		if (t % 30 == 0) {
			// Being hunted: the heart pounds.
			for (ServerPlayerEntity p : game.online(Role.PRISONER)) {
				if (game.data(p).wanted) {
					p.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.MASTER, 0.9F, 1.0F);
				}
			}
		}
		if (phase == Phase.LIGHTS_OUT && t % 160 == 0) {
			for (GuardDogEntity dog : dogs()) {
				if (random().nextInt(3) == 0) {
					world().playSound(null, dog.getBlockPos(), SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.HOSTILE, 2.5F, 0.9F);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 13. jobs

	public int jobAt(BlockPos pos) {
		return game.layout() == null ? -1 : game.layout().jobStations.indexOf(pos);
	}

	boolean nearJob(BlockPos pos) {
		for (BlockPos s : game.layout().jobStations) {
			if (s.getSquaredDistance(pos) < 6 * 6) {
				return true;
			}
		}
		return false;
	}

	private static final int JOB_REPS = 12;

	/** Library, laundry, canteen: twelve clicks of honest work for some money, and sometimes more. */
	public boolean work(ServerPlayerEntity p, BlockPos pos) {
		int i = jobAt(pos);
		if (i < 0 || !game.isRunning() || !game.isPrisoner(p)) {
			return false;
		}
		if (game.phase() != Phase.WORK) {
			p.sendMessage(Text.literal("Работа — только в рабочее время.").formatted(Formatting.GRAY), true);
			return true;
		}
		String kind = game.layout().jobKinds.get(i);
		long[] st = jobReps.computeIfAbsent(p.getUuid(), k -> new long[] { -100, 0 });
		if (ticks() - st[0] < 8) {
			return true;
		}
		st[0] = ticks();
		st[1]++;
		p.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
		String title = switch (kind) {
			case "library" -> "Сортируешь книги";
			case "laundry" -> "Стираешь форму";
			default -> "Моешь столы";
		};
		world().playSound(null, pos, switch (kind) {
			case "library" -> SoundEvents.ITEM_BOOK_PAGE_TURN;
			case "laundry" -> SoundEvents.ENTITY_GENERIC_SPLASH;
			default -> SoundEvents.ITEM_BRUSH_BRUSHING_GENERIC;
		}, SoundCategory.PLAYERS, 0.6F, 0.9F + random().nextFloat() * 0.2F);
		if (st[1] < JOB_REPS) {
			p.sendMessage(Text.literal(title + ": " + st[1] + "/" + JOB_REPS).formatted(Formatting.YELLOW), true);
			return true;
		}
		st[1] = 0;
		p.giveItemStack(new ItemStack(CbContent.MONEY, 2));
		stats(p.getUuid()).earned += 2;
		String bonus = "";
		float r = random().nextFloat();
		switch (kind) {
			case "library" -> {
				if (r < 0.3F) {
					p.giveItemStack(hintBook());
					bonus = " В одной книге кто-то оставил записи...";
				}
			}
			case "laundry" -> {
				if (r < 0.2F) {
					Item[] pieces = { Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS };
					p.giveItemStack(uniform(pieces[random().nextInt(pieces.length)]));
					bonus = " В стирке попалась часть формы охранника!";
				}
			}
			default -> {
				if (r < 0.1F) {
					p.giveItemStack(new ItemStack(CbContent.SHIV));
					bonus = " Под подносом нашёлся кухонный нож.";
				} else if (r < 0.35F) {
					p.giveItemStack(new ItemStack(Items.BREAD, 2));
					bonus = " Повар сунул тебе хлеба.";
				}
			}
		}
		p.sendMessage(Text.literal(title + ": готово! +2 денег." + bonus).formatted(Formatting.GREEN));
		return true;
	}

	private static final String[][] HINTS = {
			{ "Записки", "Канализация под тюрьмой. Из комнаты слива отходов вниз ведёт узкая шахта." },
			{ "Записки", "Ворота в восточной стене. Ключ — у охраны или у торговцев. Одному ворот не открыть." },
			{ "Записки", "Майор у ворот не трогает своих. Форма охранника бывает в стирке и в шкафчике охраны." },
			{ "Записки", "Верёвка с крюком: три нитки, два самородка. Лезть 4 секунды, шумно." },
			{ "Записки", "Камеры слепнут, если завесить тряпкой. На три минуты." },
			{ "Записки", "Собаки нюхают строй на перекличке. Не носи с собой лишнего." },
			{ "Записки", "Ночной обход заглядывает в каждую камеру. Кукла из тряпки и бумаги обманет — почти всегда." } };

	private ItemStack hintBook() {
		String[] h = HINTS[random().nextInt(HINTS.length)];
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(h[0]), "???", 2,
				List.of(RawFilteredPair.of(Text.literal(h[1]))), true));
		return book;
	}

	// ------------------------------------------------------------------ 14. parcels

	public boolean openParcel(ServerPlayerEntity p) {
		ItemStack stack = p.getMainHandStack();
		if (!stack.isOf(CbContent.PARCEL)) {
			return false;
		}
		NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
		boolean inspected = data != null && data.copyNbt().getBoolean("inspected");
		stack.decrement(1);
		List<ItemStack> loot = new ArrayList<>();
		loot.add(new ItemStack(random().nextBoolean() ? Items.COOKED_BEEF : Items.COOKIE, 2 + random().nextInt(3)));
		loot.add(new ItemStack(CbContent.MONEY, 2 + random().nextInt(5)));
		if (random().nextBoolean()) {
			loot.add(new ItemStack(Items.STRING, 1 + random().nextInt(2)));
		}
		if (random().nextBoolean()) {
			loot.add(new ItemStack(Items.PAPER, 1 + random().nextInt(2)));
		}
		if (!inspected) {
			float r = random().nextFloat();
			if (r < 0.04F) {
				loot.add(new ItemStack(CbContent.GATE_KEY));
			} else if (r < 0.15F) {
				loot.add(new ItemStack(CbContent.FILE));
			} else if (r < 0.35F) {
				loot.add(new ItemStack(Items.IRON_NUGGET, 2));
			} else if (r < 0.5F) {
				loot.add(new ItemStack(CbContent.CHALK));
			}
		}
		for (ItemStack s : loot) {
			p.giveItemStack(s);
		}
		world().playSound(null, p.getBlockPos(), SoundEvents.ITEM_BUNDLE_DROP_CONTENTS, SoundCategory.PLAYERS, 1.0F, 1.0F);
		p.sendMessage(Text.literal("В посылке: " + String.join(", ", loot.stream().map(s -> s.getCount() + "× " + s.getName().getString())
				.toList())).formatted(Formatting.AQUA));
		return true;
	}

	// ------------------------------------------------------------------ 15. statistics

	private void broadcastStats() {
		if (stats.isEmpty() && game.players.isEmpty()) {
			return;
		}
		game.broadcast(Text.literal("===== Итоги раунда =====").formatted(Formatting.GOLD));
		UUID fastest = null;
		UUID mostSolitary = null;
		for (Map.Entry<UUID, PlayerData> e : game.players.entrySet()) {
			if (e.getValue().role != Role.PRISONER) {
				continue;
			}
			UUID id = e.getKey();
			Stats s = stats(id);
			String esc = s.escapedAt >= 0 ? "сбежал за " + s.escapedAt / 20 / 60 + " мин" + (s.teamEscape ? " (вместе!)" : "") : "не сбежал";
			game.broadcast(Text.literal(String.format("%s: %s; карцер %d, медпункт %d, проломов %d, драк %d, заработал %d, макс. подозрение %d%%",
					game.name(id), esc, s.solitary, s.infirmary, s.holes, s.fights, s.earned, s.maxHeat)).formatted(Formatting.WHITE));
			if (s.escapedAt >= 0 && (fastest == null || s.escapedAt < stats(fastest).escapedAt)) {
				fastest = id;
			}
			if (s.solitary > 0 && (mostSolitary == null || s.solitary > stats(mostSolitary).solitary)) {
				mostSolitary = id;
			}
		}
		if (fastest != null) {
			game.broadcast(Text.literal("Самый быстрый побег: " + game.name(fastest)).formatted(Formatting.GREEN));
		}
		if (mostSolitary != null) {
			game.broadcast(Text.literal("Завсегдатай карцера: " + game.name(mostSolitary) + " (" + stats(mostSolitary).solitary + ")")
					.formatted(Formatting.RED));
		}
		if (snitch != null) {
			game.broadcast(Text.literal("Стукачом был " + game.name(snitch) + " (доносов: " + stats(snitch).reports + ")")
					.formatted(Formatting.LIGHT_PURPLE));
		}
	}
}
