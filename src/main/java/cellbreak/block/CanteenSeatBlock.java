package cellbreak.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * A round stool welded to the canteen table. It stays a stairs block so that the game finds it as
 * a seat (and knows which way the table is: FACING points away from it, to the aisle).
 */
public class CanteenSeatBlock extends StairsBlock {
	private static final double[][] BOXES = { { 4, 0, 4, 12, 8, 12 }, { 7, 0, 7, 9, 3, 16 } };
	private final VoxelShape[] shapes = new VoxelShape[4];

	public CanteenSeatBlock(BlockState base, Settings settings) {
		super(base, settings);
		for (Direction d : Direction.Type.HORIZONTAL) {
			shapes[d.getHorizontal()] = DecorBlock.rotated(BOXES, d);
		}
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shapes[state.get(FACING).getHorizontal()];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shapes[state.get(FACING).getHorizontal()];
	}
}
