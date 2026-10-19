package cellbreak.block;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;

/** A steel prison bunk: a real bed (sleeping, NPCs, the dummy) drawn with a block model. */
public class PrisonBedBlock extends BedBlock {
	/** The bed dummy lies in it (head on the pillow, a lump under the blanket). */
	public static final BooleanProperty DUMMY = BooleanProperty.of("dummy");

	public PrisonBedBlock(DyeColor color, Settings settings) {
		super(color, settings);
		setDefaultState(getDefaultState().with(DUMMY, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(DUMMY);
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
