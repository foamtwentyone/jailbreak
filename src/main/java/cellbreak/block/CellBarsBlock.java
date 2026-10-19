package cellbreak.block;

import java.util.function.Supplier;

import net.minecraft.block.BlockState;
import net.minecraft.block.PaneBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/** Iron-bars style pane that can only be cut with a file (and curtained off with a rag from inside). */
public class CellBarsBlock extends PaneBlock {
	/** A rag hung over the bars: nobody sees into the cell. */
	public static final net.minecraft.state.property.BooleanProperty CURTAIN = net.minecraft.state.property.BooleanProperty.of("curtain");

	private final Supplier<Item> tool;
	private final int breakTicks;

	public CellBarsBlock(Settings settings, Supplier<Item> tool, int breakTicks) {
		super(settings);
		this.tool = tool;
		this.breakTicks = breakTicks;
		setDefaultState(getDefaultState().with(CURTAIN, false));
	}

	@Override
	protected void appendProperties(net.minecraft.state.StateManager.Builder<net.minecraft.block.Block, net.minecraft.block.BlockState> builder) {
		super.appendProperties(builder);
		builder.add(CURTAIN);
	}

	@Override
	protected float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
		if (!player.getMainHandStack().isOf(tool.get())) {
			return 0.0F;
		}
		return 1.0F / breakTicks;
	}
}
