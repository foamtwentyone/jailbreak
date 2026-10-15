package cellbreak.block;

import java.util.function.Supplier;

import net.minecraft.block.BlockState;
import net.minecraft.block.PaneBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/** Iron-bars style pane that can only be cut with a file. */
public class CellBarsBlock extends PaneBlock {
	private final Supplier<Item> tool;
	private final int breakTicks;

	public CellBarsBlock(Settings settings, Supplier<Item> tool, int breakTicks) {
		super(settings);
		this.tool = tool;
		this.breakTicks = breakTicks;
	}

	@Override
	protected float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
		if (!player.getMainHandStack().isOf(tool.get())) {
			return 0.0F;
		}
		return 1.0F / breakTicks;
	}
}
