package cellbreak.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * A walk-through scanner arch, two blocks high. The passage runs along FACING; the lamp on top shows
 * the result of the last scan (green: clean, red: contraband found).
 */
public class DetectorBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
	/** 0 idle, 1 green (clean), 2 red (alarm). */
	public static final IntProperty LAMP = IntProperty.of("lamp", 0, 2);

	private static final VoxelShape POSTS_NS = VoxelShapes.union(createCuboidShape(0, 0, 4, 2, 16, 12),
			createCuboidShape(14, 0, 4, 16, 16, 12));
	private static final VoxelShape POSTS_EW = VoxelShapes.union(createCuboidShape(4, 0, 0, 12, 16, 2),
			createCuboidShape(4, 0, 14, 12, 16, 16));
	private static final VoxelShape TOP_NS = VoxelShapes.union(POSTS_NS, createCuboidShape(0, 14, 4, 16, 16, 12));
	private static final VoxelShape TOP_EW = VoxelShapes.union(POSTS_EW, createCuboidShape(4, 14, 0, 12, 16, 16));

	public DetectorBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER).with(LAMP, 0));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF, LAMP);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		boolean ns = state.get(FACING).getAxis() == Direction.Axis.Z;
		if (state.get(HALF) == DoubleBlockHalf.UPPER) {
			return ns ? TOP_NS : TOP_EW;
		}
		return ns ? POSTS_NS : POSTS_EW;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		BlockPos pos = ctx.getBlockPos();
		World world = ctx.getWorld();
		if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).canReplace(ctx)) {
			return null;
		}
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
			WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.get(HALF);
		if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
				&& !(neighbor.isOf(this) && neighbor.get(HALF) != half)) {
			return net.minecraft.block.Blocks.AIR.getDefaultState();
		}
		return state;
	}

	/** Lights the lamp on top of the arch whose lower half is at {@code lower} for two seconds. */
	public static void flash(World world, BlockPos lower, boolean alarm) {
		BlockPos top = lower.up();
		BlockState state = world.getBlockState(top);
		if (state.getBlock() instanceof DetectorBlock block) {
			world.setBlockState(top, state.with(LAMP, alarm ? 2 : 1), Block.NOTIFY_LISTENERS);
			world.scheduleBlockTick(top, block, 40);
		}
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (state.get(LAMP) != 0) {
			world.setBlockState(pos, state.with(LAMP, 0), Block.NOTIFY_LISTENERS);
		}
	}
}
