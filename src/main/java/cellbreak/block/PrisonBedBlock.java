package cellbreak.block;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

/** A steel prison bunk: a real bed (sleeping, NPCs, the dummy) drawn with a block model. */
public class PrisonBedBlock extends BedBlock {
	public PrisonBedBlock(DyeColor color, Settings settings) {
		super(color, settings);
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return null;
	}
}
