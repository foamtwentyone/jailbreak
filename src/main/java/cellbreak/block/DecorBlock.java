package cellbreak.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Furniture with a 3D model that turns to face the player: gym machines, cameras, monitors, the
 * cell toilet. The shape is given in pixels for a block facing north and turned with the block.
 */
public class DecorBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

	private final VoxelShape[] shapes = new VoxelShape[4];

	/** boxes: {x1, y1, z1, x2, y2, z2} in pixels, facing north. */
	public DecorBlock(Settings settings, double[][] boxes) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
		for (Direction d : Direction.Type.HORIZONTAL) {
			shapes[d.getHorizontal()] = rotated(boxes, d);
		}
	}

	/** The boxes turned from facing north to facing d (clockwise seen from above, like the model). */
	public static VoxelShape rotated(double[][] boxes, Direction d) {
		VoxelShape shape = VoxelShapes.empty();
		for (double[] b : boxes) {
			double x1 = b[0], z1 = b[2], x2 = b[3], z2 = b[5];
			double[] r = switch (d) {
				case EAST -> new double[] { 16 - z2, 16 - z1, x1, x2 };
				case SOUTH -> new double[] { 16 - x2, 16 - x1, 16 - z2, 16 - z1 };
				case WEST -> new double[] { z1, z2, 16 - x2, 16 - x1 };
				default -> new double[] { x1, x2, z1, z2 };
			};
			shape = VoxelShapes.union(shape, createCuboidShape(clamp(r[0]), clamp(b[1]), clamp(r[2]), clamp(r[1]),
					Math.min(b[4], 24), clamp(r[3])));
		}
		return shape.simplify();
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(16, v));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		VoxelShape s = shapes[state.get(FACING).getHorizontal()];
		return s.isEmpty() ? VoxelShapes.fullCube() : s;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shapes[state.get(FACING).getHorizontal()];
	}

	@Override
	protected BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}
}
