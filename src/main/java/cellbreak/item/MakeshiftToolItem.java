package cellbreak.item;

import java.util.function.Supplier;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A fragile tool. Every block wears it down by one point, and the more worn it is, the more likely
 * it snaps early. A snapped tool leaves its broken remains, which are cheaper to fix than a new tool.
 */
public class MakeshiftToolItem extends Item {
	/** Chance to snap at full wear; it grows with the square of the wear. */
	private static final float MAX_EARLY_BREAK = 0.45F;

	private final Supplier<Item> brokenInto;

	public MakeshiftToolItem(Settings settings, Supplier<Item> brokenInto) {
		super(settings);
		this.brokenInto = brokenInto;
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		if (world.isClient || (miner instanceof PlayerEntity p && p.isCreative())) {
			return true;
		}
		int damage = stack.getDamage() + 1;
		float wear = (float) damage / stack.getMaxDamage();
		if (damage >= stack.getMaxDamage() || world.random.nextFloat() < MAX_EARLY_BREAK * wear * wear) {
			snap(stack, world, miner);
		} else {
			stack.setDamage(damage);
		}
		return true;
	}

	private void snap(ItemStack stack, World world, LivingEntity miner) {
		world.playSound(null, miner.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.8F,
				0.8F + world.random.nextFloat() * 0.4F);
		ItemStack broken = new ItemStack(brokenInto.get());
		if (miner.getMainHandStack() == stack) {
			miner.equipStack(EquipmentSlot.MAINHAND, broken);
		} else {
			stack.decrement(1);
			if (miner instanceof PlayerEntity p) {
				p.giveItemStack(broken);
			}
		}
		if (miner instanceof PlayerEntity p) {
			p.sendMessage(Text.literal("Сломалось: ").append(stack.getName()).append(". Обломки можно починить.")
					.formatted(Formatting.GOLD), true);
		}
	}
}
