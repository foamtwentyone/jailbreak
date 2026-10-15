package cellbreak.item;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A fragile tool that loses one durability point per block it breaks. */
public class MakeshiftToolItem extends Item {
	public MakeshiftToolItem(Settings settings) {
		super(settings);
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		if (!world.isClient) {
			stack.damage(1, miner, EquipmentSlot.MAINHAND);
		}
		return true;
	}
}
