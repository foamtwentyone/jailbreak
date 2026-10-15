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
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;

/**
 * NPC guard: patrols the prison and chases wanted prisoners (guards learn about them by radio,
 * no line of sight needed). Three hits and the prisoner is arrested.
 */
public class GuardEntity extends PathAwareEntity {
	private static final int HITS_TO_ARREST = 3;

	private final Map<UUID, Integer> hits = new HashMap<>();

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
		goalSelector.add(1, new MeleeAttackGoal(this, 1.3, true));
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

	private CellbreakGame game() {
		if (getWorld() instanceof ServerWorld sw) {
			return CellbreakGame.get(sw.getServer());
		}
		return null;
	}

	private boolean isWantedPrisoner(LivingEntity entity) {
		CellbreakGame game = game();
		return game != null && entity instanceof PlayerEntity p && game.isWanted(p);
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		LivingEntity target = getTarget();
		if (target != null && !isWantedPrisoner(target)) {
			setTarget(null);
		}
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

	/** Walks between the prison's patrol points. */
	static class PatrolGoal extends Goal {
		private final GuardEntity guard;
		private BlockPos destination;

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
			destination = points.get(guard.getRandom().nextInt(points.size()));
			return true;
		}

		@Override
		public void start() {
			guard.getNavigation().startMovingTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 0.8);
		}

		@Override
		public boolean shouldContinue() {
			return guard.getTarget() == null && !guard.getNavigation().isIdle();
		}
	}
}
