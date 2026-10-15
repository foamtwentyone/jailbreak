package cellbreak.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

/** A chalk drawing on a wall. FACING points away from the wall it is drawn on. */
public class GraffitiBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final int VARIANTS = 16;
	public static final IntProperty VARIANT = IntProperty.of("variant", 0, VARIANTS - 1);

	public GraffitiBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(VARIANT, 0));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, VARIANT);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return switch (state.get(FACING)) {
			case NORTH -> createCuboidShape(0, 0, 15, 16, 16, 16);
			case SOUTH -> createCuboidShape(0, 0, 0, 16, 16, 1);
			case WEST -> createCuboidShape(15, 0, 0, 16, 16, 16);
			default -> createCuboidShape(0, 0, 0, 1, 16, 16);
		};
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
			WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		// The wall behind is gone: so is the drawing.
		if (direction == state.get(FACING).getOpposite() && neighbor.getCollisionShape(world, neighborPos).isEmpty()) {
			return net.minecraft.block.Blocks.AIR.getDefaultState();
		}
		return state;
	}
}
