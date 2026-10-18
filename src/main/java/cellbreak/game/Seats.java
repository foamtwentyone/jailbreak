package cellbreak.game;

import cellbreak.CbDecor;
import cellbreak.block.DecorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Sitting on benches and canteen stools: a right click puts the player on an invisible marker stand
 * on the seat; sneaking gets them up and the stand goes away.
 */
public final class Seats {
	private Seats() {
	}

	private static final String TAG = "cellbreak_seat";
	/** Where the stand goes: players hang 0.6 below whatever they ride, so the stand is above the seat. */
	private static final double HEIGHT = 0.35;

	public static boolean sit(ServerPlayerEntity p, BlockPos pos, BlockState state) {
		if (!CbDecor.isSeat(state.getBlock()) || p.isSneaking() || p.hasVehicle() || p.isSpectator()) {
			return false;
		}
		ServerWorld w = p.getServerWorld();
		if (p.squaredDistanceTo(pos.toCenterPos()) > 4 * 4
				|| !w.getEntitiesByClass(ArmorStandEntity.class, new Box(pos).expand(0.2), ArmorStandEntity::hasPassengers).isEmpty()) {
			return true;
		}
		float yaw = state.getBlock() instanceof StairsBlock ? state.get(StairsBlock.FACING).getOpposite().asRotation()
				: state.get(DecorBlock.FACING).asRotation();
		ArmorStandEntity stand = new ArmorStandEntity(w, pos.getX() + 0.5, pos.getY() + HEIGHT, pos.getZ() + 0.5);
		NbtCompound nbt = new NbtCompound();
		stand.writeCustomDataToNbt(nbt);
		nbt.putBoolean("Marker", true);
		nbt.putBoolean("Invisible", true);
		nbt.putBoolean("NoGravity", true);
		nbt.putBoolean("Invulnerable", true);
		stand.readCustomDataFromNbt(nbt);
		stand.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() + HEIGHT, pos.getZ() + 0.5, yaw, 0);
		stand.addCommandTag(TAG);
		w.spawnEntity(stand);
		p.startRiding(stand, true);
		p.setYaw(yaw);
		p.setHeadYaw(yaw);
		return true;
	}

	/** Once a second: seats nobody sits on any more are removed. */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) {
			return;
		}
		for (ServerWorld w : server.getWorlds()) {
			for (ArmorStandEntity stand : w.getEntitiesByType(EntityType.ARMOR_STAND,
					e -> e.getCommandTags().contains(TAG) && !e.hasPassengers())) {
				stand.discard();
			}
		}
	}
}
