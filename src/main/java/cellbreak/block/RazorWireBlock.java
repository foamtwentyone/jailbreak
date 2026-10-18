package cellbreak.block;

import cellbreak.entity.GuardEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Concertina razor wire on top of walls and fences: slows whoever climbs into it and cuts them. */
public class RazorWireBlock extends Block {
	public RazorWireBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
		if (entity instanceof GuardEntity || entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) {
			return;
		}
		entity.slowMovement(state, new Vec3d(0.35, 0.3, 0.35));
		if (!world.isClient && entity instanceof PlayerEntity && world.getTime() % 10 == 0) {
			entity.damage(world.getDamageSources().cactus(), 1.0F);
		}
	}
}
