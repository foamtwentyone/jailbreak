package cellbreak.entity;

import cellbreak.game.CellbreakGame;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * A guard dog: follows the guards, sniffs the line-up for contraband and runs down whoever is
 * wanted. The game steers it (see CellbreakExtras); it cannot be tamed.
 */
public class GuardDogEntity extends WolfEntity {
	public GuardDogEntity(EntityType<? extends WolfEntity> type, World world) {
		super(type, world);
		setPersistent();
	}

	@Override
	protected void initGoals() {
		goalSelector.add(1, new SwimGoal(this));
		goalSelector.add(2, new MeleeAttackGoal(this, 1.15, true));
		goalSelector.add(8, new LookAroundGoal(this));
	}

	/** The game picks the dog's prey; a hit from a prisoner does not start a private feud. */
	@Override
	public void setTarget(net.minecraft.entity.LivingEntity target) {
		if (target instanceof net.minecraft.server.network.ServerPlayerEntity p && game() != null && game().isOutOfPlay(p)) {
			target = null;
		}
		super.setTarget(target);
	}

	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		return ActionResult.PASS;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	public CellbreakGame game() {
		return getServer() == null ? null : CellbreakGame.get(getServer());
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("CellbreakDog", true);
	}
}
