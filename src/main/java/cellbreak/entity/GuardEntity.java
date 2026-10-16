package cellbreak.entity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cellbreak.CbContent;
import cellbreak.game.CellbreakGame;
import cellbreak.game.PrisonLayout;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;

/**
 * NPC guard. Patrols the prison; when a prisoner is wanted the guards learn about it by radio and
 * hunt them: they run at the prisoner while they see them and search the place where the prisoner
 * was last seen when they do not, so a prisoner can hide. Three hits and the prisoner is arrested.
 * A guard can also be a sniper: standing on a tower, watching the yard.
 */
public class GuardEntity extends PathAwareEntity {
	public static final int VARIANTS = 12;
	private static final TrackedData<Integer> VARIANT = DataTracker.registerData(GuardEntity.class,
			TrackedDataHandlerRegistry.INTEGER);
	private static final int HITS_TO_ARREST = 3;
	/** A prisoner seen this recently but out of walking reach (ladders) gets a guard via a service passage. */
	private static final int SHORTCUT_TICKS = 80;

	private static final String[] SURNAMES = { "Петров", "Сидоров", "Кузнецов", "Волков", "Морозов", "Соколов",
			"Лебедев", "Козлов", "Новиков", "Зайцев", "Павлов", "Голубев", "Виноградов", "Богданов", "Фёдоров", "Михайлов",
			"Беляев", "Тарасов", "Белов", "Комаров" };
	private static final String[] RANKS = { "Сержант", "Прапорщик", "Старшина", "Лейтенант", "Рядовой" };

	/** How a guard talks to a prisoner who comes up to them. */
	public enum Manner {
		STRICT, LAZY, KIND, SARCASTIC
	}

	private static final String[] STRICT = { "Чего надо? Руки за спину.", "Не задерживайся, заключённый.",
			"Ещё вопрос — и пойдёшь в карцер.", "Стоять ровно, когда говоришь с охраной!", "Я за тобой слежу.",
			"Номер свой помнишь? Вот и не забывай, где ты.", "Разговоры с охраной не положены.",
			"Двигай. Тут не курорт.", "Шаг в сторону — нарушение.", "Порядок есть порядок.",
			"Я таких, как ты, сотню видел.", "Режим для всех один. И для тебя тоже." };
	private static final String[] LAZY = { "Ох, отстань... у меня смена через два часа кончается.", "Иди куда шёл, мне лень писать рапорт.",
			"Я не видел, ты не видел. Договорились?", "Зевнул... извини. Чего хотел?", "Не создавай мне работы, пожалуйста.",
			"Скорей бы обед.", "Если что-то сломаешь — сделай так, чтобы я не заметил.", "Я тут просто стою. Ты тоже постой.",
			"Начальник орёт, ты пристаёшь... тяжёлая у меня жизнь.", "Можно я просто посижу?" };
	private static final String[] KIND = { "Как ты, держишься?", "Ешь хорошо, сил больше будет.", "Не нарушай — и всё будет нормально.",
			"Если тебя кто обижает, скажи мне.", "У всех бывают плохие дни. Держись.", "Работай честно — выйдешь раньше.",
			"Знаешь, я тоже не хотел тут работать. Но вот.", "Береги себя, парень.", "Привет. Тихо сегодня, да?",
			"Не лезь в драки, ладно?" };
	private static final String[] SARCASTIC = { "О, мой любимый заключённый. Что, опять соскучился?",
			"Ищешь выход? Он вон там. Шучу, ты здесь навсегда.", "Решил подружиться с охраной? Мило.",
			"Твоя камера скучает по тебе. Иди проведай.", "Ты такой подозрительный, что я даже восхищаюсь.",
			"Если копаешь подкоп — копай потише, ладно?", "Скажи, а кирка в кармане тебе не мешает? Шучу. Или нет.",
			"Свобода? Слышал о такой. Говорят, хорошая вещь.", "Ну что, герой, опять к нам с вопросами?",
			"Аплодирую твоей смелости. Подходить к охраннику — это сильно." };

	private Manner manner = Manner.STRICT;
	private final Map<UUID, Integer> hits = new HashMap<>();
	private int unreachableTicks;
	private boolean sniper;
	/** A job given by the game (count the line-up, stand at an exit): walk the route, then stay. */
	private List<BlockPos> duty;
	private BlockPos dutyLook;
	/** Walk the duty route round and round (watching a zone) instead of staying at its end. */
	private boolean dutyLoop;
	/** A short job (checking a noise) on top of the usual duty; the usual one comes back after. */
	private List<BlockPos> savedDuty;
	private BlockPos savedLook;
	private boolean savedLoop;
	private int checkUntil = -1;
	/** A prisoner caught fighting: the guard goes to stop them until this age. */
	private int breakUpUntil = -1;

	public GuardEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
		// Tables are iron trapdoors on bars: nobody walks across them.
		setPathfindingPenalty(net.minecraft.entity.ai.pathing.PathNodeType.TRAPDOOR, -1.0F);
	}

	public static DefaultAttributeContainer.Builder createGuardAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 160.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(VARIANT, 0);
	}

	public int getVariant() {
		return dataTracker.get(VARIANT);
	}

	/** Gives the guard a route to walk (slowly, looking around) and to stay at its end; null frees them. */
	public void setDuty(List<BlockPos> route, BlockPos lookAt) {
		setDuty(route, lookAt, false);
	}

	/** As above; with loop the guard walks the route round and round, pausing at each point. */
	public void setDuty(List<BlockPos> route, BlockPos lookAt, boolean loop) {
		duty = route;
		dutyLook = lookAt;
		dutyLoop = loop;
		checkUntil = -1;
	}

	/** Goes to look at what made a noise, then gets back to what they were doing. */
	public void checkNoise(BlockPos pos) {
		if (checkUntil < 0) {
			goCheck(pos, pos, 20);
		}
	}

	/** Goes to a place and watches a spot for a while, then gets back to the usual duty. */
	public void goCheck(BlockPos pos, BlockPos look, int seconds) {
		if (sniper) {
			return;
		}
		if (checkUntil < 0) {
			savedDuty = duty;
			savedLook = dutyLook;
			savedLoop = dutyLoop;
		}
		duty = List.of(pos);
		dutyLook = look;
		dutyLoop = false;
		checkUntil = age + seconds * 20;
	}

	/** The check is over early (the prisoner did what they were told). */
	public void endCheck() {
		if (checkUntil >= 0) {
			checkUntil = age;
		}
	}

	/** A prisoner was seen fighting: run there and stop them with the baton. */
	public void breakUpFight(PlayerEntity p) {
		if (sniper) {
			return;
		}
		setTarget(p);
		breakUpUntil = age + 15 * 20;
	}

	private boolean isBreakingUp(LivingEntity target) {
		return age < breakUpUntil && target instanceof PlayerEntity p && game() != null && game().isPrisoner(p)
				&& !game().isOutOfPlay(p);
	}

	public boolean hasDuty() {
		return duty != null;
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(1, new ChaseGoal(this));
		goalSelector.add(2, new DutyGoal(this));
		goalSelector.add(3, new PatrolGoal(this));
		goalSelector.add(4, new LookAtEntityGoal(this, PlayerEntity.class, 12.0F));
		goalSelector.add(5, new LookAroundGoal(this));
		targetSelector.add(1, new RadioTargetGoal(this));
	}

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason, EntityData data) {
		EntityData result = super.initialize(world, difficulty, reason, data);
		dataTracker.set(VARIANT, random.nextInt(VARIANTS));
		Manner[] all = Manner.values();
		manner = all[random.nextInt(all.length)];
		setCustomName(net.minecraft.text.Text.literal(RANKS[random.nextInt(RANKS.length)] + " "
				+ SURNAMES[random.nextInt(SURNAMES.length)]).formatted(net.minecraft.util.Formatting.BLUE));
		equipStack(EquipmentSlot.MAINHAND, new ItemStack(CbContent.BATON));
		setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0F);
		return result;
	}

	public boolean isSniper() {
		return sniper;
	}

	/** Turns this guard into a tower sniper: stands still, cannot be hurt, holds a crossbow. */
	public void makeSniper() {
		sniper = true;
		setAiDisabled(true);
		setNoGravity(true);
		setInvulnerable(true);
		equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
		setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.0F);
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("Sniper", sniper);
		nbt.putInt("Variant", getVariant());
		nbt.putString("Manner", manner.name());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		sniper = nbt.getBoolean("Sniper");
		dataTracker.set(VARIANT, nbt.getInt("Variant"));
		try {
			manner = Manner.valueOf(nbt.getString("Manner"));
		} catch (IllegalArgumentException e) {
			manner = Manner.STRICT;
		}
	}

	private CellbreakGame game() {
		if (getWorld() instanceof ServerWorld sw) {
			return CellbreakGame.get(sw.getServer());
		}
		return null;
	}

	private boolean isWantedPrisoner(LivingEntity entity) {
		CellbreakGame game = game();
		return !sniper && game != null && entity instanceof PlayerEntity p && game.isWanted(p);
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		if (checkUntil >= 0 && age > checkUntil) {
			duty = savedDuty;
			dutyLook = savedLook;
			dutyLoop = savedLoop;
			checkUntil = -1;
		}
		LivingEntity target = getTarget();
		if (target != null && !isWantedPrisoner(target) && !isBreakingUp(target)) {
			setTarget(null);
			target = null;
		}
		CellbreakGame game = game();
		if (target == null || game == null || squaredDistanceTo(target) < 9
				|| game.ticksSinceSeen((PlayerEntity) target) > 60) {
			unreachableTicks = 0;
			return;
		}
		// The prisoner was just seen but climbed out of walking reach (a ladder): check every second.
		if (age % 20 == 0) {
			Path path = getNavigation().findPathTo(target, 0);
			unreachableTicks = path != null && path.reachesTarget() ? 0 : unreachableTicks + 20;
		}
		if (unreachableTicks >= SHORTCUT_TICKS) {
			unreachableTicks = 0;
			BlockPos spot = findSpotNear(target.getBlockPos());
			if (spot != null) {
				getWorld().playSound(null, spot, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.HOSTILE, 1.0F, 1.0F);
				refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, getYaw(), getPitch());
				getNavigation().stop();
			}
		}
	}

	/** A free spot 2-4 blocks from the given position, on the same floor. */
	private BlockPos findSpotNear(BlockPos center) {
		World world = getWorld();
		for (int r = 2; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = 0; dy >= -1; dy--) {
						BlockPos p = center.add(dx, dy, dz);
						if (world.getBlockState(p.down()).isSolidBlock(world, p.down())
								&& world.getBlockState(p).getCollisionShape(world, p).isEmpty()
								&& world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()) {
							return p;
						}
					}
				}
			}
		}
		return null;
	}

	@Override
	public boolean tryAttack(Entity target) {
		boolean hit = super.tryAttack(target);
		CellbreakGame game = game();
		if (hit && game != null && target instanceof ServerPlayerEntity p && game.isPrisoner(p)) {
			int n = hits.merge(p.getUuid(), 1, Integer::sum);
			if (n >= HITS_TO_ARREST) {
				hits.remove(p.getUuid());
				setTarget(null);
				game.arrest(p, isBreakingUp(p) ? "задержан за драку" : "задержан охраной");
				breakUpUntil = -1;
			}
		}
		return hit;
	}

	/** A player comes up and clicks: the guard answers in their manner. */
	@Override
	protected net.minecraft.util.ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND || sniper) {
			return net.minecraft.util.ActionResult.PASS;
		}
		if (!getWorld().isClient) {
			String[] lines = switch (manner) {
				case LAZY -> LAZY;
				case KIND -> KIND;
				case SARCASTIC -> SARCASTIC;
				default -> STRICT;
			};
			getLookControl().lookAt(player);
			player.sendMessage(net.minecraft.text.Text.literal(getName().getString() + ": ").formatted(net.minecraft.util.Formatting.BLUE)
					.append(net.minecraft.text.Text.literal(lines[random.nextInt(lines.length)])
							.formatted(net.minecraft.util.Formatting.WHITE)), false);
		}
		return net.minecraft.util.ActionResult.SUCCESS;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	/** Picks up wanted prisoners anywhere nearby, even behind walls: the guards have radios. */
	static class RadioTargetGoal extends ActiveTargetGoal<PlayerEntity> {
		RadioTargetGoal(GuardEntity guard) {
			super(guard, PlayerEntity.class, 5, false, false, guard::isWantedPrisoner);
			targetPredicate = targetPredicate.ignoreVisibility();
		}
	}

	/**
	 * Runs at a wanted prisoner the guard can see; otherwise goes to where the prisoner was last
	 * seen (by any guard) and looks around there.
	 */
	static class ChaseGoal extends Goal {
		private final GuardEntity guard;
		private int repath;
		private int cooldown;

		ChaseGoal(GuardEntity guard) {
			this.guard = guard;
			setControls(EnumSet.of(Control.MOVE, Control.LOOK));
		}

		@Override
		public boolean canStart() {
			return guard.getTarget() instanceof PlayerEntity && guard.game() != null;
		}

		@Override
		public void stop() {
			guard.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (!(guard.getTarget() instanceof PlayerEntity target)) {
				return;
			}
			CellbreakGame game = guard.game();
			boolean sees = game.canGuardSee(guard, target);
			BlockPos goal = sees ? target.getBlockPos() : game.lastKnownPos(target);
			if (sees) {
				guard.getLookControl().lookAt(target, 30.0F, 30.0F);
			}
			if (goal != null && --repath <= 0) {
				repath = sees ? 5 : 20;
				guard.getNavigation().startMovingTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.3);
			}
			if (cooldown > 0) {
				cooldown--;
			}
			if (sees && cooldown == 0 && guard.squaredDistanceTo(target) < 6.25) {
				guard.swingHand(Hand.MAIN_HAND);
				guard.tryAttack(target);
				cooldown = 20;
			}
		}
	}

	/** Walks between the prison's patrol points it can actually reach. */
	static class PatrolGoal extends Goal {
		private final GuardEntity guard;
		private Path path;

		PatrolGoal(GuardEntity guard) {
			this.guard = guard;
			setControls(EnumSet.of(Control.MOVE));
		}

		@Override
		public boolean canStart() {
			CellbreakGame game = guard.game();
			if (game == null || guard.getTarget() != null || guard.duty != null || guard.getRandom().nextInt(40) != 0) {
				return false;
			}
			PrisonLayout layout = game.layout();
			if (layout == null || layout.patrolPoints.isEmpty()) {
				return false;
			}
			List<BlockPos> points = layout.patrolPoints;
			for (int attempt = 0; attempt < 4; attempt++) {
				BlockPos destination = points.get(guard.getRandom().nextInt(points.size()));
				path = guard.getNavigation().findPathTo(destination, 1);
				if (path != null && path.reachesTarget() && path.getLength() > 1) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void start() {
			guard.getNavigation().startMovingAlong(path, 0.8);
		}

		@Override
		public boolean shouldContinue() {
			return guard.getTarget() == null && guard.duty == null && !guard.getNavigation().isIdle();
		}
	}

	/** Walks the duty route point by point, then stays at the last point and watches. */
	static class DutyGoal extends Goal {
		private final GuardEntity guard;
		private List<BlockPos> route;
		private int index;
		private int stuck;
		private int pause;

		DutyGoal(GuardEntity guard) {
			this.guard = guard;
			setControls(EnumSet.of(Control.MOVE, Control.LOOK));
		}

		@Override
		public boolean canStart() {
			return guard.duty != null && guard.getTarget() == null;
		}

		@Override
		public void start() {
			route = guard.duty;
			index = 0;
			stuck = 0;
		}

		@Override
		public void stop() {
			guard.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (route != guard.duty) {
				start();
			}
			if (route == null || route.isEmpty()) {
				return;
			}
			BlockPos goal = route.get(Math.min(index, route.size() - 1));
			boolean last = index >= route.size() - 1;
			double d = guard.squaredDistanceTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);
			if (guard.dutyLoop && d < 2.0) {
				// Watching a zone: stands a while at each point, looking around, then goes on.
				guard.getNavigation().stop();
				if (pause == 0) {
					pause = 60 + guard.getRandom().nextInt(80);
				}
				if (--pause <= 0) {
					index = (index + 1) % route.size();
					stuck = 0;
				} else if (pause % 40 == 0) {
					guard.getLookControl().lookAt(guard.getX() + guard.getRandom().nextGaussian() * 8, guard.getEyeY(),
							guard.getZ() + guard.getRandom().nextGaussian() * 8);
				}
				return;
			}
			if (d < (last ? 1.0 : 2.0)) {
				if (!last) {
					index++;
					stuck = 0;
				} else {
					guard.getNavigation().stop();
					if (guard.dutyLook != null) {
						guard.getLookControl().lookAt(guard.dutyLook.getX() + 0.5, guard.dutyLook.getY() + 1.5,
								guard.dutyLook.getZ() + 0.5);
					}
				}
				return;
			}
			// Counting walks slowly, getting to a post is brisk.
			double speed = guard.dutyLoop ? 0.7 : route.size() > 1 && index > 0 ? 0.55 : 1.0;
			if (guard.getNavigation().isIdle() || guard.age % 20 == 0) {
				guard.getNavigation().startMovingTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, speed);
			}
			if (!guard.dutyLoop && route.size() > 1 && index > 0 && guard.dutyLook != null) {
				// Looks over the line while walking along it.
				guard.getLookControl().lookAt(guard.getX() + (guard.dutyLook.getX() + 0.5 - guard.getX()) * 0.1,
						guard.getEyeY() - 0.3, guard.dutyLook.getZ() + 0.5);
			}
			if (++stuck > 10 * 20 && guard.checkUntil < 0) {
				// Could not get there in time: comes in through a service door.
				stuck = 0;
				guard.getWorld().playSound(null, goal, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.HOSTILE, 0.6F, 1.0F);
				guard.refreshPositionAndAngles(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, guard.getYaw(), 0);
				guard.getNavigation().stop();
			}
		}
	}
}
