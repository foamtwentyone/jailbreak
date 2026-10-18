package cellbreak.block;

import net.minecraft.block.BarrelBlock;

/**
 * Prison storage (cell cabinets, scrap bins, the kitchen fridge, tool and guard lockers). It is a
 * barrel inside: the same 27 slots and the same block entity, so every rule that looks for a chest
 * or a barrel keeps working. Only the model differs.
 */
public class StorageBlock extends BarrelBlock {
	public StorageBlock(Settings settings) {
		super(settings);
	}
}
