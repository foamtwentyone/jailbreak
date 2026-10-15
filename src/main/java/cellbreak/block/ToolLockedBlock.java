package cellbreak.block;

import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * A block that can only be broken with one specific makeshift tool, taking a fixed
 * number of ticks. Optionally turns into another block instead of disappearing
 * (a wall first cracks, then breaks).
 */
public class ToolLockedBlock extends Block {
	private final Supplier<Item> tool;
	private final int breakTicks;
	private final Supplier<Block> brokenInto;

	public ToolLockedBlock(Settings settings, Supplier<Item> tool, int breakTicks, Supplier<Block> brokenInto) {
		super(settings);
		this.tool = tool;
		this.breakTicks = breakTicks;
		this.brokenInto = brokenInto;
	}

	public boolean isTool(ItemStack stack) {
		// The work pickaxe from the field breaks walls just like the makeshift one.
		return tool != null && (stack.isOf(tool.get())
				|| tool.get() == cellbreak.CbContent.MAKESHIFT_PICKAXE && stack.isOf(cellbreak.CbContent.WORK_PICKAXE));
	}

	@Override
	protected float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
		if (!isTool(player.getMainHandStack())) {
			return 0.0F;
		}
		return 1.0F / breakTicks;
	}

	@Override
	public void afterBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool) {
		super.afterBreak(world, player, pos, state, blockEntity, tool);
		if (brokenInto != null && world.getBlockState(pos).isAir()) {
			world.setBlockState(pos, brokenInto.get().getDefaultState());
		}
	}
}
