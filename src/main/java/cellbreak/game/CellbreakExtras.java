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
import cellbreak.block.PrisonBedBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
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
import net.minecraft.util.math.BlockBox;
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
	private final Map<Integer, DummyBed> dummies = new HashMap<>();
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

	/**
	 * Someone on a rope: going up (hand over hand, from where they stood to the top of the wall) or
	 * sliding down. Moved by velocity, not teleports, so the camera stays free.
	 */
	private static final class Climb {
		final BlockPos top;
		final Vec3d start;
		final Vec3d foot;
		final List<BlockPos> rope;
		/** Their own rope, thrown just now: used up at the top, pulled back down if they climb down. */
		final boolean own;
		final double upSpeed;
		long from;
		double y;
		boolean down;

		Climb(BlockPos top, Vec3d start, Vec3d foot, List<BlockPos> rope, boolean own, long from, int seconds) {
			this.top = top;
			this.start = start;
			this.foot = foot;
			this.rope = rope;
			this.own = own;
			this.from = from;
			this.y = start.y;
			this.upSpeed = (top.getY() - 0.9 - foot.y) / Math.max(20, seconds * 20 - 10);
		}

		double height() {
			return top.getY() - foot.y;
		}
	}

	private static final double SLIDE_SPEED = 0.16;

	/** Ropes left hanging on walls: where, and when they are taken down. */
	private final Map<BlockPos, Long> hangingRopes = new HashMap<>();
	private static final int ROPE_STAYS_SECONDS = 60;

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
		takeDownRopes(true);
		for (Entity e : world().getEntitiesByClass(Entity.class, game.npcArea(),
				e -> e instanceof GuardDogEntity || e.getCommandTags().contains(TAG_DUMMY) || e.getCommandTags().contains(TAG_EYE)
						|| e.getCommandTags().contains(TAG_EXTRA))) {
			e.discard();
		}
		cameraEyes.clear();
		dummies.clear();
		for (PrisonLayout.Cell c : game.layout().cells) {
			uncoverCell(c.interior.expand(1));
		}
		curtained.clear();
		// Dummies left in the bunks by an earlier round (or a crash).
		for (PrisonLayout.Cell c : game.layout().cells) {
			for (BlockPos q : BlockPos.iterate(c.interior.getMinX() - 1, c.interior.getMinY() - 1, c.interior.getMinZ() - 1,
					c.interior.getMaxX() + 1, c.interior.getMaxY() + 1, c.interior.getMaxZ() + 1)) {
				BlockState st = world().getBlockState(q);
				if (st.getBlock() instanceof PrisonBedBlock && st.get(PrisonBedBlock.DUMMY)) {
					world().setBlockState(q, st.with(PrisonBedBlock.DUMMY, false), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
				}
			}
		}
	}

	private static final String TAG_DUMMY = "cellbreak_dummy";
	private static final String TAG_EYE = "cellbreak_cam";
	private static final String TAG_EXTRA = "cellbreak_extra";

	void tick() {
		long t = ticks();
		if (!climbs.isEmpty()) {
			climbMove();
		}
		if (t % 10 == 0) {
			disguiseChecks();
			cameraChecks();
			lockdownTick();
			climbTick();
			viewTick();
		}
		if (t % 20 == 7) {
			curtainChecks();
		}
		if (t % 10 == 5) {
			dogTick();
			droppedDummies();
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

	/** A dummy in a cell's bed: the bunk's own dummy model, or (an ordinary bed) a head on an armor stand. */
	private record DummyBed(BlockPos head, UUID stand) {
	}

	/**
	 * Right or left click on a bed (or the dummy thrown onto it): lay a dummy in your own bed, or
	 * (sneaking, empty hand) take it back. A guard who clicks a dummy bed pulls the dummy out.
	 * Returns true when the click was handled (a message says what happened).
	 */
	public boolean useBed(ServerPlayerEntity p, BlockPos bed, boolean dropped) {
		PlayerData d = game.data(p);
		ItemStack hand = dropped ? new ItemStack(CbContent.DUMMY) : p.getMainHandStack();
		boolean holding = hand.isOf(CbContent.DUMMY);
		if (!game.isRunning() || d == null) {
			if (holding) {
				p.sendMessage(Text.literal("Кукла работает только во время раунда.").formatted(Formatting.GRAY));
				return !dropped;
			}
			return false;
		}
		if (d.role == Role.GUARD && !dropped) {
			for (var en : dummies.entrySet()) {
				if (en.getValue().head().equals(bed)) {
					removeDummy(en.getKey());
					p.sendMessage(Text.literal("Под одеялом кукла! Хозяин камеры где-то гуляет.").formatted(Formatting.GOLD));
					return true;
				}
			}
			return false;
		}
		if (d.role != Role.PRISONER || d.cell < 0) {
			return false;
		}
		PrisonLayout.Cell cell = game.layout().cells.get(d.cell);
		boolean own = cell.interior.expand(1).contains(bed);
		if (holding) {
			if (!own) {
				p.sendMessage(Text.literal("Это не твоя кровать. Куклу кладут в свою камеру — в камеру " + (d.cell + 1) + ".")
						.formatted(Formatting.RED));
				return !dropped;
			}
			if (dummies.containsKey(d.cell)) {
				p.sendMessage(Text.literal("Кукла уже лежит в кровати.").formatted(Formatting.GRAY));
				return !dropped;
			}
			UUID standId = null;
			if (!setDummy(bed, true)) {
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
				standId = stand.getUuid();
			}
			dummies.put(d.cell, new DummyBed(bed, standId));
			if (!dropped) {
				hand.decrement(1);
			}
			world().playSound(null, bed, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0F, 0.9F);
			p.sendMessage(Text.literal("Ты уложил куклу под одеяло. Ночной обход может и не заметить... "
					+ "Забрать: присядь и ПКМ по кровати пустой рукой.").formatted(Formatting.GREEN));
			return true;
		}
		if (own && hand.isEmpty() && p.isSneaking() && dummies.containsKey(d.cell)) {
			removeDummy(d.cell);
			p.giveItemStack(new ItemStack(CbContent.DUMMY));
			p.sendMessage(Text.literal("Ты забрал куклу.").formatted(Formatting.GRAY));
			return true;
		}
		return false;
	}

	/** Shows or hides the dummy in a prison bunk (both halves). False: not a bunk with a dummy model. */
	private boolean setDummy(BlockPos head, boolean on) {
		BlockState st = world().getBlockState(head);
		if (!(st.getBlock() instanceof PrisonBedBlock)) {
			return false;
		}
		BlockPos foot = head.offset(st.get(BedBlock.FACING).getOpposite());
		world().setBlockState(head, st.with(PrisonBedBlock.DUMMY, on), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
		BlockState fs = world().getBlockState(foot);
		if (fs.getBlock() instanceof PrisonBedBlock) {
			world().setBlockState(foot, fs.with(PrisonBedBlock.DUMMY, on), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
		}
		return true;
	}

	private void removeDummy(int cell) {
		DummyBed b = dummies.remove(cell);
		if (b == null) {
			return;
		}
		setDummy(b.head(), false);
		if (b.stand() != null) {
			Entity e = world().getEntity(b.stand());
			if (e != null) {
				e.discard();
			}
		}
	}

	/** A dummy thrown (Q) onto a bed lies down in it, as if laid by hand. */
	private void droppedDummies() {
		for (ItemEntity item : world().getEntitiesByClass(ItemEntity.class, game.npcArea(),
				e -> e.getStack().isOf(CbContent.DUMMY) && e.isOnGround() && !e.getCommandTags().contains("cellbreak_dummy_tried"))) {
			// One try per landing (no message spam); picking it up and throwing again tries again.
			item.addCommandTag("cellbreak_dummy_tried");
			if (!(item.getOwner() instanceof ServerPlayerEntity p)) {
				continue;
			}
			BlockPos head = null;
			for (BlockPos q : BlockPos.iterate(item.getBlockPos().add(-1, -1, -1), item.getBlockPos().add(1, 0, 1))) {
				BlockState st = world().getBlockState(q);
				if (st.getBlock() instanceof BedBlock) {
					head = st.get(BedBlock.PART) == BedPart.HEAD ? q.toImmutable() : q.offset(st.get(BedBlock.FACING)).toImmutable();
					break;
				}
			}
			if (head != null && useBed(p, head, true)) {
				item.getStack().decrement(1);
				if (item.getStack().isEmpty()) {
					item.discard();
				}
			}
		}
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
		removeDummy(d.cell);
		game.guardSays(guard, "Это же кукла! Подушка в одеяле!");
		return false;
	}

	// ------------------------------------------------------------------ the curtain over the cell bars

	/** Cells whose bars are curtained off (the rag is on the bars). */
	private final Set<Integer> curtained = new HashSet<>();

	/**
	 * Right click on your own cell's bars with a rag: a curtain over all of them (nobody looks in).
	 * Again with an empty hand: the rag comes down and back into the pocket.
	 */
	public boolean useCurtain(ServerPlayerEntity p, BlockPos pos) {
		BlockState st = world().getBlockState(pos);
		if (!st.isOf(CbContent.CELL_BARS) || !game.isRunning() || !game.isPrisoner(p)) {
			return false;
		}
		PlayerData d = game.data(p);
		if (d.cell < 0 || d.cell >= game.layout().cells.size()) {
			return false;
		}
		BlockBox box = game.layout().cells.get(d.cell).interior.expand(1);
		if (!box.contains(pos)) {
			return false;
		}
		boolean on = st.get(cellbreak.block.CellBarsBlock.CURTAIN);
		ItemStack hand = p.getMainHandStack();
		if (!on && hand.isOf(CbContent.RAG)) {
			setCurtain(box, true);
			curtained.add(d.cell);
			hand.decrement(1);
			world().playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0F, 0.9F);
			p.sendMessage(Text.literal("Ты завесил решётку тряпкой: снаружи не видно, что в камере. "
					+ "Охрана такого не любит — сорвут и запомнят. Снять: ПКМ пустой рукой.").formatted(Formatting.GOLD));
			return true;
		}
		if (on && hand.isEmpty()) {
			setCurtain(box, false);
			curtained.remove(d.cell);
			p.giveItemStack(new ItemStack(CbContent.RAG));
			p.sendMessage(Text.literal("Ты снял тряпку с решётки.").formatted(Formatting.GRAY), true);
			return true;
		}
		return false;
	}

	private void setCurtain(BlockBox box, boolean on) {
		for (BlockPos q : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
			BlockState st = world().getBlockState(q);
			if (st.isOf(CbContent.CELL_BARS) && st.get(cellbreak.block.CellBarsBlock.CURTAIN) != on) {
				world().setBlockState(q, st.with(cellbreak.block.CellBarsBlock.CURTAIN, on), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
			}
		}
	}

	/** Takes a curtain down (a guard does): true if there was one. */
	boolean uncoverCell(BlockBox box) {
		boolean had = false;
		for (BlockPos q : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
			BlockState st = world().getBlockState(q);
			if (st.isOf(CbContent.CELL_BARS) && st.get(cellbreak.block.CellBarsBlock.CURTAIN)) {
				had = true;
				world().setBlockState(q, st.with(cellbreak.block.CellBarsBlock.CURTAIN, false), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
			}
		}
		for (int i = 0; i < game.layout().cells.size(); i++) {
			if (game.layout().cells.get(i).interior.expand(1).equals(box)) {
				curtained.remove(i);
			}
		}
		return had;
	}

	/** Whether a cell is curtained off (for the night round). */
	boolean isCurtained(int cell) {
		return curtained.contains(cell);
	}

	/** By day an NPC guard who sees a curtain on a cell's bars tears it down; the owner is noted. */
	private void curtainChecks() {
		if (curtained.isEmpty()) {
			return;
		}
		for (int cell : new ArrayList<>(curtained)) {
			PrisonLayout.Cell c = game.layout().cells.get(cell);
			for (GuardEntity g : game.npcGuards()) {
				if (g.getBlockPos().getSquaredDistance(c.door) < 8 * 8 && hasClearView(g, c.door)) {
					uncoverCell(c.interior.expand(1));
					game.guardSays(g, "Это что ещё за занавески? Снять! Решётку не завешивать!");
					ServerPlayerEntity owner = game.cellOwner(c.spawn);
					if (owner != null) {
						game.addHeat(owner, 15);
						owner.sendMessage(Text.literal("Охрана сорвала тряпку с решётки твоей камеры. Подозрение +15").formatted(Formatting.RED));
					}
					break;
				}
			}
		}
	}

	private boolean hasClearView(GuardEntity g, BlockPos pos) {
		Vec3d eye = g.getEyePos();
		Vec3d to = Vec3d.ofCenter(pos);
		BlockHitResult hit = world().raycast(new RaycastContext(eye, to, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, g));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().getSquaredDistance(pos) <= 2;
	}

	// ------------------------------------------------------------------ 2. the guard disguise

	/** A prisoner in a guard's cap and jacket (navy leather): passes for a guard from a distance. */
	public static boolean disguised(net.minecraft.entity.player.PlayerEntity p) {
		return isUniform(p.getEquippedStack(EquipmentSlot.CHEST)) && isUniform(p.getEquippedStack(EquipmentSlot.HEAD));
	}

	private static boolean isUniform(ItemStack s) {
		return CbContent.isGuardUniform(s);
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
		return post != null && pos.getSquaredDistance(post) < 7 * 7 && CbContent.isDoor(world().getBlockState(pos));
	}

	private List<BlockPos> gateDoors() {
		List<BlockPos> list = new ArrayList<>();
		BlockPos post = game.layout().gatePost;
		if (post == null) {
			return list;
		}
		for (BlockPos p : BlockPos.iterate(post.add(-7, -3, -7), post.add(7, 3, 7))) {
			BlockState s = world().getBlockState(p);
			if (CbContent.isDoor(s) && s.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
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

	/** The highest wall a rope reaches, in blocks above your feet. */
	private static final int ROPE_REACH = 14;

	/**
	 * Rope with a hook thrown up a wall (at most {@link #ROPE_REACH} blocks high): the hook catches
	 * two times in three; climbing takes 2 seconds plus one per block, is loud and in plain view
	 * (outdoors the snipers take aim), and the rope stays up there.
	 */
	public boolean useRope(ServerPlayerEntity p, BlockHitResult hit) {
		if (!game.isRunning() || hit.getSide().getAxis() == Direction.Axis.Y || !game.isPrisoner(p)) {
			return false;
		}
		if (climbs.containsKey(p.getUuid()) || p.getItemCooldownManager().isCoolingDown(CbContent.ROPE_HOOK)) {
			return true;
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
		int height = top == null ? 0 : top.getY() - p.getBlockY();
		if (top == null || height < 2) {
			p.sendMessage(Text.literal("Тут не за что зацепиться.").formatted(Formatting.GRAY), true);
			return true;
		}
		if (height > ROPE_REACH) {
			p.sendMessage(Text.literal("Слишком высоко: верёвки хватает на " + ROPE_REACH + " блоков.").formatted(Formatting.GRAY), true);
			return true;
		}
		world().playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.0F, 0.8F);
		game.makeNoise(p.getBlockPos(), p.getBlockPos());
		game.suspiciousOutdoors(p, p.getBlockPos());
		if (random().nextFloat() < 0.35F) {
			p.getItemCooldownManager().set(CbContent.ROPE_HOOK, 40);
			world().playSound(null, wall.up(height), SoundEvents.BLOCK_CHAIN_FALL, SoundCategory.PLAYERS, 1.2F, 0.8F);
			p.sendMessage(Text.literal("Крюк не зацепился и с лязгом упал. Попробуй ещё.").formatted(Formatting.RED), true);
			return true;
		}
		int seconds = 2 + height;
		// The rope hangs down the face of the wall, from the hook at the top to the ground.
		Direction side = hit.getSide();
		BlockPos column = wall.offset(side);
		List<BlockPos> rope = new ArrayList<>();
		for (int y = top.getY() - 1; y >= p.getBlockY() - 1; y--) {
			BlockPos q = new BlockPos(column.getX(), y, column.getZ());
			BlockState here = world().getBlockState(q);
			if (!here.isAir() && !here.isOf(cellbreak.CbDecor.HANGING_ROPE) && !here.isOf(cellbreak.CbDecor.ROPE_TOP)) {
				break;
			}
			boolean hook = rope.isEmpty();
			world().setBlockState(q, (hook ? cellbreak.CbDecor.ROPE_TOP : cellbreak.CbDecor.HANGING_ROPE).getDefaultState()
					.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, side));
			rope.add(q);
			hangingRopes.put(q, Long.MAX_VALUE);
		}
		Vec3d foot = new Vec3d(column.getX() + 0.5 + side.getOffsetX() * 0.2, p.getY(), column.getZ() + 0.5 + side.getOffsetZ() * 0.2);
		climbs.put(p.getUuid(), new Climb(top, p.getPos(), foot, rope, true, ticks(), seconds));
		p.sendMessage(Text.literal("Крюк зацепился! Лезешь вверх (" + seconds + " с). Присесть — слезть вниз, отойти от стены — спрыгнуть.")
				.formatted(Formatting.GOLD));
		return true;
	}

	/** Right click on a rope hanging on a wall: climb it (from below) or climb down it (from the top of the wall). */
	public boolean useHangingRope(ServerPlayerEntity p, BlockPos pos) {
		BlockState st = world().getBlockState(pos);
		if (!game.isRunning() || !game.isPrisoner(p) || climbs.containsKey(p.getUuid())
				|| !(st.isOf(cellbreak.CbDecor.HANGING_ROPE) || st.isOf(cellbreak.CbDecor.ROPE_TOP))) {
			return false;
		}
		// The whole rope: up to the hook, down to its end.
		BlockPos hook = pos;
		while (world().getBlockState(hook.up()).isOf(cellbreak.CbDecor.HANGING_ROPE) || world().getBlockState(hook.up()).isOf(cellbreak.CbDecor.ROPE_TOP)) {
			hook = hook.up();
		}
		BlockPos end = pos;
		while (world().getBlockState(end.down()).isOf(cellbreak.CbDecor.HANGING_ROPE)) {
			end = end.down();
		}
		Direction side = world().getBlockState(hook).get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
		BlockPos top = hook.offset(side.getOpposite()).up();
		Vec3d foot = new Vec3d(end.getX() + 0.5 + side.getOffsetX() * 0.2, end.getY(), end.getZ() + 0.5 + side.getOffsetZ() * 0.2);
		List<BlockPos> rope = new ArrayList<>();
		for (BlockPos q = hook; q.getY() >= end.getY(); q = q.down()) {
			rope.add(q);
		}
		int seconds = 2 + (top.getY() - end.getY());
		// Nobody takes the rope down under a climber.
		for (BlockPos q : rope) {
			hangingRopes.computeIfPresent(q, (k, v) -> Math.max(v, ticks() + (seconds + 10) * 20L));
		}
		Climb c = new Climb(top, p.getPos(), foot, rope, false, ticks(), seconds);
		if (p.getY() >= top.getY() - 0.5) {
			// From the top of the wall: over the edge and down.
			c.down = true;
			c.y = top.getY() - 0.9;
			p.teleport(world(), foot.x, c.y, foot.z, p.getYaw(), p.getPitch());
			p.sendMessage(Text.literal("Спускаешься по верёвке...").formatted(Formatting.GOLD), true);
		} else {
			p.sendMessage(Text.literal("Лезешь по верёвке (" + seconds + " с). Присесть — слезть вниз.").formatted(Formatting.GOLD), true);
		}
		climbs.put(p.getUuid(), c);
		return true;
	}

	/** Every tick: the climbers go up their ropes (hand over hand) or slide down. */
	void climbMove() {
		for (var it = climbs.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			ServerPlayerEntity p = game.server.getPlayerManager().getPlayer(e.getKey());
			Climb c = e.getValue();
			long t = ticks() - c.from;
			boolean onRope = t < 10 && !c.down
					|| Math.abs(p == null ? 99 : p.getX() - c.foot.x) < 1.5 && Math.abs(p.getZ() - c.foot.z) < 1.5;
			if (p == null || !game.isPrisoner(p) || p.hurtTime > 0 || !onRope) {
				// Hit, or stepped away from the wall: let go and fall.
				if (p != null) {
					p.sendMessage(Text.literal("Ты сорвался с верёвки!").formatted(Formatting.RED), true);
					if (c.own) {
						// The rope stays up on the wall: it is not in the pocket any more.
						ItemStack rope = findRope(p);
						if (rope != null) {
							rope.decrement(1);
						}
					}
				}
				finish(c, false);
				it.remove();
				continue;
			}
			if (p.isSneaking() && !c.down && t >= 10) {
				c.down = true;
				p.sendMessage(Text.literal("Слезаешь вниз...").formatted(Formatting.GOLD), true);
			}
			double x = c.foot.x;
			double z = c.foot.z;
			if (c.down) {
				c.y -= SLIDE_SPEED;
				if (c.y <= c.foot.y) {
					p.setVelocity(Vec3d.ZERO);
					p.velocityModified = true;
					p.fallDistance = 0;
					if (c.own) {
						// Down again before the top: the rope is pulled back off the wall, still usable.
						removeRope(c.rope);
						p.sendMessage(Text.literal("Ты слез и снял верёвку.").formatted(Formatting.GRAY), true);
					} else {
						p.sendMessage(Text.literal("Ты спустился.").formatted(Formatting.GRAY), true);
					}
					it.remove();
					continue;
				}
			} else if (t < 10) {
				// First a step up to the rope.
				double k = t / 10.0;
				x = c.start.x + (c.foot.x - c.start.x) * k;
				z = c.start.z + (c.foot.z - c.start.z) * k;
			} else {
				// Hand over hand: a pull every half second.
				double phase = ((t - 10) % 10) / 10.0;
				c.y += c.upSpeed * (phase < 0.4 ? 2.5 : 0);
				if (c.y >= c.top.getY() - 0.9) {
					it.remove();
					p.teleport(world(), c.top.getX() + 0.5, c.top.getY(), c.top.getZ() + 0.5, p.getYaw(), p.getPitch());
					p.fallDistance = 0;
					finish(c, true);
					if (c.own) {
						ItemStack rope = p.getMainHandStack().isOf(CbContent.ROPE_HOOK) ? p.getMainHandStack() : findRope(p);
						if (rope != null) {
							rope.decrement(1);
						}
					}
					p.sendMessage(Text.literal("Ты наверху! Верёвка осталась висеть на стене.").formatted(Formatting.GREEN), true);
					continue;
				}
			}
			// Pulled to where they should be by velocity: the camera stays the player's own.
			Vec3d want = new Vec3d(x, c.y, z);
			p.setVelocity(want.subtract(p.getPos()).multiply(0.8));
			p.velocityModified = true;
			p.fallDistance = 0;
		}
	}

	private void climbTick() {
		for (var e : climbs.entrySet()) {
			ServerPlayerEntity p = game.server.getPlayerManager().getPlayer(e.getKey());
			Climb c = e.getValue();
			if (p == null) {
				continue;
			}
			world().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_WOOL_STEP, SoundCategory.PLAYERS, 1.0F, 0.7F);
			world().playSound(null, c.top, SoundEvents.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 0.8F, 0.6F);
			game.makeNoise(p.getBlockPos(), p.getBlockPos());
			game.suspiciousOutdoors(p, p.getBlockPos());
			// Progress under the crosshair (with a bar): metres up the wall.
			int height = (int) Math.max(1, Math.round(c.height()));
			int done = (int) Math.max(0, Math.min(height, Math.round(c.y - c.foot.y)));
			p.sendMessage(Text.literal((c.down ? "Спуск по верёвке: " : "Подъём по верёвке: ") + done + "/" + height + " м")
					.formatted(Formatting.GOLD), true);
		}
		takeDownRopes(false);
	}

	/** A climb is over: an own rope that was not pulled back stays on the wall for a while. */
	private void finish(Climb c, boolean atTop) {
		if (c.own || atTop) {
			leaveRope(c);
		}
	}

	private void removeRope(List<BlockPos> rope) {
		for (BlockPos q : rope) {
			BlockState st = world().getBlockState(q);
			if (st.isOf(cellbreak.CbDecor.HANGING_ROPE) || st.isOf(cellbreak.CbDecor.ROPE_TOP)) {
				world().setBlockState(q, Blocks.AIR.getDefaultState());
			}
			hangingRopes.remove(q);
		}
	}

	/** The rope stays on the wall for a while (anyone can see where someone went over). */
	private void leaveRope(Climb c) {
		long at = ticks() + ROPE_STAYS_SECONDS * 20L;
		for (BlockPos q : c.rope) {
			if (hangingRopes.getOrDefault(q, 0L) != Long.MAX_VALUE && !c.own) {
				continue;
			}
			hangingRopes.put(q, at);
		}
	}

	private void takeDownRopes(boolean all) {
		if (game.layout() == null) {
			hangingRopes.clear();
			return;
		}
		if (all) {
			// Also ropes from a round that did not end properly (a crash): look over the whole prison.
			for (net.minecraft.util.math.BlockBox a : game.layout().areas) {
				for (BlockPos q : BlockPos.iterate(a.getMinX(), a.getMinY(), a.getMinZ(), a.getMaxX(), a.getMaxY(), a.getMaxZ())) {
					BlockState st = world().getBlockState(q);
					if (st.isOf(cellbreak.CbDecor.HANGING_ROPE) || st.isOf(cellbreak.CbDecor.ROPE_TOP)) {
						world().setBlockState(q, Blocks.AIR.getDefaultState());
					}
				}
			}
		}
		for (var it = hangingRopes.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (!all && ticks() < e.getValue()) {
				continue;
			}
			BlockState st = world().getBlockState(e.getKey());
			if (st.isOf(cellbreak.CbDecor.HANGING_ROPE) || st.isOf(cellbreak.CbDecor.ROPE_TOP)) {
				world().setBlockState(e.getKey(), Blocks.AIR.getDefaultState());
			}
			it.remove();
		}
	}

	private static ItemStack findRope(ServerPlayerEntity p) {
		for (int i = 0; i < p.getInventory().size(); i++) {
			if (p.getInventory().getStack(i).isOf(CbContent.ROPE_HOOK)) {
				return p.getInventory().getStack(i);
			}
		}
		return null;
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
				if (d.wanted && d.solitaryUntil == 0 && d.infirmaryUntil == 0 && !d.escaped && p.squaredDistanceTo(dog) < best) {
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
			// Nobody to chase (caught, in the infirmary, gone): forget the chase, the bites and the grudge.
			dog.setTarget(null);
			dog.setAttacker(null);
			dog.stopAnger();
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
		// Only who stands in the line-up: the missing are the counting guard's business, not the dogs'.
		BlockBox zone = game.layout().rollCallZone;
		line.removeIf(p -> sniffed.contains(p.getUuid()) || game.data(p).solitaryUntil != 0 || game.data(p).escaped
				|| zone != null && !zone.expand(1).contains(p.getBlockPos()));
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
			// Forbidden things in hand, by the same rules as a guard's eyes (work tools and field goods at work are fine).
			boolean bad = wrongPlace || night || game.holdsContraband(p);
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
