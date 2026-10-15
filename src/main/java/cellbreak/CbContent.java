package cellbreak;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import cellbreak.block.CellBarsBlock;
import cellbreak.block.FakeBlock;
import cellbreak.block.ToolLockedBlock;
import cellbreak.entity.GuardEntity;
import cellbreak.entity.PrisonerNpcEntity;
import cellbreak.item.MakeshiftToolItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ToolComponent;
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
	// Break times (ticks): wall 8 s per stage, bars 10 s, vent cover 6 s, floor 6 s.

	public static final Block PRISON_WALL = block("prison_wall",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_PICKAXE, 160, () -> CbContent.CRACKED_PRISON_WALL),
			stone(MapColor.STONE_GRAY));
	public static final Block CRACKED_PRISON_WALL = block("cracked_prison_wall",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_PICKAXE, 160, null),
			stone(MapColor.STONE_GRAY));
	public static final Block REINFORCED_WALL = block("reinforced_wall",
			Block::new,
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(-1.0F, 3600000.0F)
					.sounds(BlockSoundGroup.METAL).dropsNothing().allowsSpawning(Blocks::never));
	public static final Block CELL_BARS = block("cell_bars",
			s -> new CellBarsBlock(s, () -> CbContent.FILE, 200),
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(50.0F, 1200.0F)
					.sounds(BlockSoundGroup.METAL).nonOpaque().dropsNothing());
	public static final Block VENT_COVER = block("vent_cover",
			s -> new ToolLockedBlock(s, () -> CbContent.FILE, 120, null),
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(50.0F, 1200.0F)
					.sounds(BlockSoundGroup.METAL).dropsNothing());
	public static final Block PRISON_FLOOR = block("prison_floor",
			s -> new ToolLockedBlock(s, () -> CbContent.MAKESHIFT_SHOVEL, 120, null),
			stone(MapColor.OFF_WHITE));
	/** Prisoners hand in their work here (planks, iron ingots) for money. */
	public static final Block JOB_DESK = block("job_desk", Block::new,
			AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(2.5F).sounds(BlockSoundGroup.WOOD));
	/** Walk-through arch that beeps when a prisoner carries contraband. */
	public static final Block DETECTOR = block("detector", Block::new,
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(3.0F).sounds(BlockSoundGroup.METAL)
					.noCollision().nonOpaque());

	public static final Block FAKE_PRISON_WALL = fake("fake_prison_wall", false);
	public static final Block FAKE_STONE_BRICKS = fake("fake_stone_bricks", false);
	public static final Block FAKE_PRISON_FLOOR = fake("fake_prison_floor", false);
	public static final Block FAKE_SMOOTH_STONE = fake("fake_smooth_stone", false);
	public static final Block FAKE_VENT_COVER = fake("fake_vent_cover", true);

	/** Vanilla walls of existing maps that the makeshift pickaxe can chip (they crack first). */
	public static final List<Block> VANILLA_WALLS = List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
			Blocks.CHISELED_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS);
	public static final List<Block> VANILLA_FLOORS = List.of(Blocks.SMOOTH_STONE);
	public static final List<Block> VANILLA_GROUND = List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
			Blocks.GRAVEL, Blocks.SAND, Blocks.DIRT_PATH, Blocks.ROOTED_DIRT, Blocks.PODZOL);
	public static final List<Block> VANILLA_BARS = List.of(Blocks.IRON_BARS);

	// Mining speeds = hardness * 30 / ticks: stone bricks 8 s per stage, smooth stone floor 6 s,
	// ground 2 s, iron bars 10 s.
	public static final Item MAKESHIFT_PICKAXE = item("makeshift_pickaxe",
			new MakeshiftToolItem(tool(new Item.Settings().maxDamage(6), List.of(PRISON_WALL, CRACKED_PRISON_WALL),
					ToolComponent.Rule.ofAlwaysDropping(VANILLA_WALLS, 0.28F))));
	public static final Item MAKESHIFT_SHOVEL = item("makeshift_shovel",
			new MakeshiftToolItem(tool(new Item.Settings().maxDamage(24), List.of(PRISON_FLOOR),
					ToolComponent.Rule.ofAlwaysDropping(VANILLA_FLOORS, 0.5F),
					ToolComponent.Rule.ofAlwaysDropping(VANILLA_GROUND, 0.375F))));
	public static final Item FILE = item("file",
			new MakeshiftToolItem(tool(new Item.Settings().maxDamage(4), List.of(CELL_BARS, VENT_COVER),
					ToolComponent.Rule.ofAlwaysDropping(VANILLA_BARS, 0.75F))));
	public static final Item SHIV = item("shiv",
			new SwordItem(ToolMaterials.WOOD, new Item.Settings().maxDamage(30)
					.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.WOOD, 2, -2.0F))));
	public static final Item CELL_KEY = item("cell_key", new Item(new Item.Settings().maxCount(1)));
	public static final Item HANDCUFFS = item("handcuffs", new Item(new Item.Settings().maxCount(1)));
	public static final Item BATON = item("baton",
			new SwordItem(ToolMaterials.IRON, new Item.Settings()
					.component(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(true))
					.attributeModifiers(SwordItem.createAttributeModifiers(ToolMaterials.IRON, 0, -2.4F))));

	public static final Item MONEY = item("money", new Item(new Item.Settings()));
	public static final Item FAKE_WALL = item("fake_wall", new Item(new Item.Settings().maxCount(16)));
	public static final Item FAKE_VENT = item("fake_vent", new Item(new Item.Settings().maxCount(16)));

	/** Items a prisoner is not allowed to carry. */
	public static final Set<Item> CONTRABAND = Set.of(MAKESHIFT_PICKAXE, MAKESHIFT_SHOVEL, FILE, SHIV, CELL_KEY,
			BATON, HANDCUFFS, FAKE_WALL, FAKE_VENT);

	public static final EntityType<GuardEntity> GUARD = Registry.register(Registries.ENTITY_TYPE, id("guard"),
			EntityType.Builder.create(GuardEntity::new, SpawnGroup.MISC).dimensions(0.6F, 1.8F).maxTrackingRange(10)
					.build("guard"));

	public static final Item GUARD_SPAWN_EGG = item("guard_spawn_egg",
			new SpawnEggItem(GUARD, 0x222E54, 0xE2B636, new Item.Settings()));

	public static final EntityType<PrisonerNpcEntity> PRISONER = Registry.register(Registries.ENTITY_TYPE,
			id("prisoner"), EntityType.Builder.create(PrisonerNpcEntity::new, SpawnGroup.MISC).dimensions(0.6F, 1.8F)
					.maxTrackingRange(10).build("prisoner"));

	public static final Item PRISONER_SPAWN_EGG = item("prisoner_spawn_egg",
			new SpawnEggItem(PRISONER, 0xE6761E, 0x3A2A20, new Item.Settings()));

	public static void init() {
		FabricDefaultAttributeRegistry.register(GUARD, GuardEntity.createGuardAttributes());
		FabricDefaultAttributeRegistry.register(PRISONER, PrisonerNpcEntity.createPrisonerAttributes());

		List<Item> tabItems = List.of(PRISON_WALL.asItem(), CRACKED_PRISON_WALL.asItem(), REINFORCED_WALL.asItem(),
				CELL_BARS.asItem(), VENT_COVER.asItem(), PRISON_FLOOR.asItem(), JOB_DESK.asItem(), DETECTOR.asItem(),
				MAKESHIFT_PICKAXE, MAKESHIFT_SHOVEL, FILE, SHIV, CELL_KEY, FAKE_WALL, FAKE_VENT, MONEY, HANDCUFFS, BATON,
				GUARD_SPAWN_EGG, PRISONER_SPAWN_EGG);
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

	/**
	 * A makeshift tool: breaks our own blocks (their break time is set by the block) and the vanilla
	 * blocks named in the rules, also in adventure mode.
	 */
	private static Item.Settings tool(Item.Settings settings, List<Block> ownBlocks, ToolComponent.Rule... vanilla) {
		List<Block> all = new ArrayList<>(ownBlocks);
		for (ToolComponent.Rule rule : vanilla) {
			rule.blocks().forEach(entry -> all.add(entry.value()));
		}
		BlockPredicate predicate = BlockPredicate.Builder.create().blocks(all).build();
		return settings.maxCount(1)
				.component(DataComponentTypes.CAN_BREAK, new BlockPredicatesChecker(List.of(predicate), true))
				.component(DataComponentTypes.TOOL, new ToolComponent(List.of(vanilla), 1.0F, 0));
	}

	private static Block block(String name, Function<AbstractBlock.Settings, Block> factory,
			AbstractBlock.Settings settings) {
		Block block = Registry.register(Registries.BLOCK, id(name), factory.apply(settings));
		Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
		return block;
	}

	private static Block fake(String name, boolean vent) {
		return Registry.register(Registries.BLOCK, id(name), new FakeBlock(AbstractBlock.Settings.create()
				.mapColor(MapColor.STONE_GRAY).strength(0.5F).sounds(BlockSoundGroup.STONE).dropsNothing(), vent));
	}

	private static Item item(String name, Item item) {
		return Registry.register(Registries.ITEM, id(name), item);
	}
}
