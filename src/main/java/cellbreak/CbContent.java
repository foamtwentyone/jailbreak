package cellbreak;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import cellbreak.block.CellBarsBlock;
import cellbreak.block.ToolLockedBlock;
import cellbreak.entity.GuardEntity;
import cellbreak.item.MakeshiftToolItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BlockPredicatesChecker;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterials;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Registers every block, item and entity of the mod. */
public final class CbContent {
	private CbContent() {
	}

	// ---- items that blocks refer to are resolved lazily through suppliers

	public static final Block PRISON_WALL = block("prison_wall",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_PICKAXE, 60, () -> CbContent.CRACKED_PRISON_WALL),
			stone(MapColor.STONE_GRAY));
	public static final Block CRACKED_PRISON_WALL = block("cracked_prison_wall",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_PICKAXE, 60, null),
			stone(MapColor.STONE_GRAY));
	public static final Block REINFORCED_WALL = block("reinforced_wall",
			Block::new,
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(-1.0F, 3600000.0F)
					.sounds(BlockSoundGroup.METAL).dropsNothing().allowsSpawning(Blocks::never));
	public static final Block CELL_BARS = block("cell_bars",
			s -> new CellBarsBlock(s, () -> CbContent.FILE, 80),
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(50.0F, 1200.0F)
					.sounds(BlockSoundGroup.METAL).nonOpaque().dropsNothing());
	public static final Block VENT_COVER = block("vent_cover",
			s -> new ToolLockedBlock(s, () -> CbContent.FILE, 40, null),
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(50.0F, 1200.0F)
					.sounds(BlockSoundGroup.METAL).dropsNothing());
	public static final Block PRISON_FLOOR = block("prison_floor",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_SHOVEL, 40, null),
			stone(MapColor.OFF_WHITE));

	public static final Item MAKESHIFT_PICKAXE = item("makeshift_pickaxe",
			new MakeshiftToolItem(breaks(new Item.Settings().maxDamage(6), PRISON_WALL, CRACKED_PRISON_WALL)));
	public static final Item MAKESHIFT_SHOVEL = item("makeshift_shovel",
			new MakeshiftToolItem(breaks(new Item.Settings().maxDamage(24), PRISON_FLOOR,
					Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.GRAVEL)));
	public static final Item FILE = item("file",
			new MakeshiftToolItem(breaks(new Item.Settings().maxDamage(4), CELL_BARS, VENT_COVER)));
	public static final Item SHIV = item("shiv",
			new SwordItem(ToolMaterials.WOOD, new Item.Settings().maxDamage(30)
					.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.WOOD, 2, -2.0F))));
	public static final Item CELL_KEY = item("cell_key", new Item(new Item.Settings().maxCount(1)));
	public static final Item HANDCUFFS = item("handcuffs", new Item(new Item.Settings().maxCount(1)));
	public static final Item BATON = item("baton",
			new SwordItem(ToolMaterials.IRON, new Item.Settings()
					.component(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(true))
					.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.IRON, 0, -2.4F))));

	/** Items a prisoner is not allowed to carry. */
	public static final Set<Item> CONTRABAND = Set.of(MAKESHIFT_PICKAXE, MAKESHIFT_SHOVEL, FILE, SHIV, CELL_KEY);

	public static final EntityType<GuardEntity> GUARD = Registry.register(Registries.ENTITY_TYPE, id("guard"),
			EntityType.Builder.create(GuardEntity::new, SpawnGroup.MISC).dimensions(0.6F, 1.8F).maxTrackingRange(10)
					.build("guard"));

	public static final Item GUARD_SPAWN_EGG = item("guard_spawn_egg",
			new SpawnEggItem(GUARD, 0x222E54, 0xE2B636, new Item.Settings()));

	public static void init() {
		FabricDefaultAttributeRegistry.register(GUARD, GuardEntity.createGuardAttributes());

		List<Item> tabItems = List.of(PRISON_WALL.asItem(), CRACKED_PRISON_WALL.asItem(), REINFORCED_WALL.asItem(),
				CELL_BARS.asItem(), VENT_COVER.asItem(), PRISON_FLOOR.asItem(), MAKESHIFT_PICKAXE,
				MAKESHIFT_SHOVEL, FILE, SHIV, CELL_KEY, HANDCUFFS, BATON, GUARD_SPAWN_EGG);
		Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
				.icon(() -> new ItemStack(MAKESHIFT_PICKAXE))
				.displayName(Text.translatable("itemGroup.cellbreak"))
				.entries((ctx, entries) -> tabItems.forEach(entries::add))
				.build());
	}

	public static Identifier id(String path) {
		return Identifier.of(Cellbreak.MOD_ID, path);
	}

	private static AbstractBlock.Settings stone(MapColor color) {
		return AbstractBlock.Settings.create().mapColor(color).strength(50.0F, 1200.0F)
				.sounds(BlockSoundGroup.STONE).dropsNothing();
	}

	/** Lets the item break the given blocks in adventure mode. */
	private static Item.Settings breaks(Item.Settings settings, Block... blocks) {
		BlockPredicate predicate = BlockPredicate.Builder.create().blocks(blocks).build();
		return settings.maxCount(1).component(DataComponentTypes.CAN_BREAK,
				new BlockPredicatesChecker(List.of(predicate), true));
	}

	private static Block block(String name, Function<AbstractBlock.Settings, Block> factory,
			AbstractBlock.Settings settings) {
		Block block = Registry.register(Registries.BLOCK, id(name), factory.apply(settings));
		Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
		return block;
	}

	private static Item item(String name, Item item) {
		return Registry.register(Registries.ITEM, id(name), item);
	}
}
