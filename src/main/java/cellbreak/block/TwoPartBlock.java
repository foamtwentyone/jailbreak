package cellbreak.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

/**
 * Furniture two blocks big: tall (the second part above: power rack, punching bag) or long (the
 * second part behind: treadmill). The base part is the one the game remembers.
 */
public class TwoPartBlock extends DecorBlock {
	public enum Part implements StringIdentifiable {
		BASE, EXTRA;

		@Override
		public String asString() {
			return this == BASE ? "base" : "extra";
		}
	}

	public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);

	private final boolean tall;
	private final VoxelShape[] extraShapes = new VoxelShape[4];

	public TwoPartBlock(Settings settings, boolean tall, double[][] baseBoxes, double[][] extraBoxes) {
		super(settings, baseBoxes);
		this.tall = tall;
		setDefaultState(getDefaultState().with(PART, Part.BASE));
		for (Direction d : Direction.Type.HORIZONTAL) {
			extraShapes[d.getHorizontal()] = rotated(extraBoxes, d);
		}
	}

	/** Where the second part goes for a base at pos. */
	public BlockPos extraPos(BlockPos base, Direction facing) {
		return tall ? base.up() : base.offset(facing.getOpposite());
	}

	/** The base of the furniture a block belongs to. */
	public BlockPos basePos(BlockState state, BlockPos pos) {
		if (state.get(PART) == Part.BASE) {
			return pos;
		}
		return tall ? pos.down() : pos.offset(state.get(FACING));
	}

	private Direction towardsPartner(BlockState state) {
		Direction f = state.get(FACING);
		if (tall) {
			return state.get(PART) == Part.BASE ? Direction.UP : Direction.DOWN;
		}
		return state.get(PART) == Part.BASE ? f.getOpposite() : f;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(PART);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		BlockState state = super.getPlacementState(ctx);
		BlockPos other = extraPos(ctx.getBlockPos(), state.get(FACING));
		if (!ctx.getWorld().getBlockState(other).canReplace(ctx)) {
			return null;
		}
		return state;
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		world.setBlockState(extraPos(pos, state.get(FACING)), state.with(PART, Part.EXTRA), Block.NOTIFY_ALL);
	}

	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
			WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		if (direction == towardsPartner(state)
				&& !(neighbor.isOf(this) && neighbor.get(PART) != state.get(PART) && neighbor.get(FACING) == state.get(FACING))) {
			return Blocks.AIR.getDefaultState();
		}
		return state;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(PART) == Part.BASE ? super.getCollisionShape(state, world, pos, context)
				: extraShapes[state.get(FACING).getHorizontal()];
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		if (state.get(PART) == Part.BASE) {
			return super.getOutlineShape(state, world, pos, context);
		}
		VoxelShape s = extraShapes[state.get(FACING).getHorizontal()];
		return s.isEmpty() ? net.minecraft.util.shape.VoxelShapes.fullCube() : s;
	}
}
