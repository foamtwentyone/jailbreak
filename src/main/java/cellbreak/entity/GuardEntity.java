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
	private static final int HITS_TO_ARREST = 3;
	/** A prisoner seen this recently but out of walking reach (ladders) gets a guard via a service passage. */
	private static final int SHORTCUT_TICKS = 80;

	private final Map<UUID, Integer> hits = new HashMap<>();
	private int unreachableTicks;
	private boolean sniper;

	public GuardEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		setPersistent();
		setCanPickUpLoot(false);
	}

	public static DefaultAttributeContainer.Builder createGuardAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 40.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		goalSelector.add(1, new ChaseGoal(this));
		goalSelector.add(2, new PatrolGoal(this));
		goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 12.0F));
		goalSelector.add(4, new LookAroundGoal(this));
		targetSelector.add(1, new RadioTargetGoal(this));
	}

	@Override
	public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason, EntityData data) {
		EntityData result = super.initialize(world, difficulty, reason, data);
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
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		sniper = nbt.getBoolean("Sniper");
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
		LivingEntity target = getTarget();
		if (target != null && !isWantedPrisoner(target)) {
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
				game.arrest(p, "задержан охраной");
			}
		}
		return hit;
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
			if (game == null || guard.getTarget() != null || guard.getRandom().nextInt(40) != 0) {
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
			return guard.getTarget() == null && !guard.getNavigation().isIdle();
		}
	}
}
