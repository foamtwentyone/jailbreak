package cellbreak.block;

import net.minecraft.block.Block;

/**
 * A paper-and-paint copy of a prison block, put over a hole to hide it from the guards. Prisoners
 * take it off again with sneak + right click.
 */
public class FakeBlock extends Block {
	/** True for the vent cover copy, false for walls and floors. */
	public final boolean vent;

	public FakeBlock(Settings settings, boolean vent) {
		super(settings);
		this.vent = vent;
	}
}
