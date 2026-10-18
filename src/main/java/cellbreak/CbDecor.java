package cellbreak;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import cellbreak.block.DecorBlock;
import cellbreak.block.RazorWireBlock;
import cellbreak.block.TwoPartBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * The look of the prison (0.5): concrete trims, steel, wired glass, lights, razor wire, CCTV, and
 * the furniture with real 3D models (gym machines, the cell toilet, the guards' monitors). None of
 * these blocks matter to the game rules except the gym machines (see {@link #exerciseName}).
 */
public final class CbDecor {
	private CbDecor() {
	}

	public static final List<Block> BLOCKS = new ArrayList<>();

	private static AbstractBlock.Settings concrete() {
		return AbstractBlock.Settings.create().mapColor(MapColor.STONE_GRAY).strength(50.0F, 1200.0F)
				.sounds(BlockSoundGroup.STONE).dropsNothing();
	}

	private static AbstractBlock.Settings steel() {
		return AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(50.0F, 1200.0F)
				.sounds(BlockSoundGroup.METAL).dropsNothing();
	}

	private static AbstractBlock.Settings furniture() {
		return steel().nonOpaque().pistonBehavior(PistonBehavior.BLOCK);
	}

	// ---- building materials
	public static final Block SMOOTH_CONCRETE = block("smooth_concrete", Block::new, concrete());
	public static final Block CONCRETE_SLAB = block("concrete_slab", SlabBlock::new, concrete());
	public static final Block CONCRETE_STAIRS = block("concrete_stairs",
			s -> new StairsBlock(SMOOTH_CONCRETE.getDefaultState(), s), concrete());
	public static final Block CONCRETE_BARRIER = block("concrete_barrier", WallBlock::new, concrete());
	/** Smooth stone slabs of the map: walkways and roof edges, in polished concrete. */
	public static final Block FLOOR_SLAB = block("floor_slab", SlabBlock::new, concrete());
	public static final Block DARK_CONCRETE = block("dark_concrete", Block::new, concrete());
	public static final Block DARK_CONCRETE_SLAB = block("dark_concrete_slab", SlabBlock::new, concrete());
	public static final Block DARK_CONCRETE_STAIRS = block("dark_concrete_stairs",
			s -> new StairsBlock(DARK_CONCRETE.getDefaultState(), s), concrete());
	public static final Block ASPHALT = block("asphalt", Block::new, concrete().mapColor(MapColor.DEEPSLATE_GRAY));
	public static final Block STEEL_PANEL = block("steel_panel", Block::new, steel());
	public static final Block WIRED_GLASS = block("wired_glass", TransparentBlock::new,
			AbstractBlock.Settings.create().strength(50.0F, 1200.0F).sounds(BlockSoundGroup.GLASS).nonOpaque()
					.dropsNothing().allowsSpawning(Blocks::never).solidBlock(Blocks::never).suffocates(Blocks::never)
					.blockVision(Blocks::never));
	public static final Block WIRED_GLASS_PANE = block("wired_glass_pane", PaneBlock::new,
			AbstractBlock.Settings.create().strength(50.0F, 1200.0F).sounds(BlockSoundGroup.GLASS).nonOpaque().dropsNothing());
	/** A recessed fluorescent ceiling light. */
	public static final Block LIGHT_PANEL = block("light_panel", Block::new,
			concrete().mapColor(MapColor.WHITE).sounds(BlockSoundGroup.GLASS).luminance(s -> 15));
	/** A caged bulkhead lamp (hanging or standing, like a lantern). */
	public static final Block CAGED_LAMP = block("caged_lamp", LanternBlock::new,
			steel().nonOpaque().luminance(s -> 15).sounds(BlockSoundGroup.LANTERN));
	public static final Block RAZOR_WIRE = block("razor_wire", RazorWireBlock::new,
			steel().noCollision().nonOpaque().sounds(BlockSoundGroup.CHAIN));
	/** Canteen tables (the map's iron trapdoors) in stainless steel. */
	public static final Block STEEL_TABLE = block("steel_table", s -> new TrapdoorBlock(BlockSetType.IRON, s),
			steel().nonOpaque().allowsSpawning(Blocks::never));
	/** Canteen stools welded to the tables (stairs inside, so that inmates sit on them). */
	public static final Block STEEL_SEAT = block("steel_seat",
			s -> new cellbreak.block.CanteenSeatBlock(STEEL_PANEL.getDefaultState(), s), steel().nonOpaque());

	// ---- storage: barrels inside (27 slots), prison furniture outside
	public static final Block CELL_CABINET = block("cell_cabinet", cellbreak.block.StorageBlock::new, steel().strength(2.5F).nonOpaque());
	public static final Block SCRAP_BIN = block("scrap_bin", cellbreak.block.StorageBlock::new, steel().strength(2.5F).nonOpaque());
	public static final Block KITCHEN_FRIDGE = block("kitchen_fridge", cellbreak.block.StorageBlock::new, steel().strength(2.5F).nonOpaque());
	public static final Block TOOL_CABINET = block("tool_cabinet", cellbreak.block.StorageBlock::new, steel().strength(2.5F).nonOpaque());
	public static final Block GUARD_LOCKER = block("guard_locker", cellbreak.block.StorageBlock::new, steel().strength(2.5F).nonOpaque());
	/** Steel bunks: real beds drawn with a model (grey blanket; blue for the guards, white in the infirmary). */
	public static final Block PRISON_BED = block("prison_bed",
			s -> new cellbreak.block.PrisonBedBlock(net.minecraft.util.DyeColor.GRAY, s),
			steel().strength(0.2F).nonOpaque().sounds(BlockSoundGroup.WOOL));
	public static final Block GUARD_BED = block("guard_bed",
			s -> new cellbreak.block.PrisonBedBlock(net.minecraft.util.DyeColor.BLUE, s),
			steel().strength(0.2F).nonOpaque().sounds(BlockSoundGroup.WOOL));
	public static final Block INFIRMARY_BED = block("infirmary_bed",
			s -> new cellbreak.block.PrisonBedBlock(net.minecraft.util.DyeColor.WHITE, s),
			steel().strength(0.2F).nonOpaque().sounds(BlockSoundGroup.WOOL));
	/** Shower taps and wall switches: levers inside (the showers run on lever state). */
	public static final Block SHOWER_VALVE = block("shower_valve", net.minecraft.block.LeverBlock::new,
			steel().noCollision().nonOpaque().strength(0.5F));
	public static final Block WALL_SWITCH = block("wall_switch", net.minecraft.block.LeverBlock::new,
			steel().noCollision().nonOpaque().strength(0.5F));
	public static final Block STEEL_LADDER = block("steel_ladder", net.minecraft.block.LadderBlock::new,
			steel().strength(0.4F).nonOpaque().sounds(BlockSoundGroup.LADDER));
	public static final Block HAZARD_STEEL = block("hazard_steel", Block::new, steel().mapColor(MapColor.YELLOW));

	// ---- furniture with models (boxes in pixels, facing north)
	public static final Block CCTV_CAMERA = block("cctv_camera",
			s -> new DecorBlock(s, new double[][] { { 5, 5, 2, 11, 12, 16 } }), furniture());
	public static final Block SECURITY_MONITOR = block("security_monitor",
			s -> new DecorBlock(s, new double[][] { { 0, 0, 1, 16, 13, 10 } }), furniture().luminance(s -> 6));
	public static final Block STEEL_TOILET = block("steel_toilet",
			s -> new DecorBlock(s, new double[][] { { 3, 0, 2, 13, 6, 16 }, { 3, 0, 9, 13, 15, 16 } }), furniture());
	public static final Block STEEL_SINK = block("steel_sink",
			s -> new DecorBlock(s, new double[][] { { 3, 9, 7, 13, 14, 16 } }), furniture());
	public static final Block BENCH_PRESS = block("bench_press",
			s -> new DecorBlock(s, new double[][] { { 5, 0, 2, 11, 8, 16 }, { 2, 0, 2, 4, 16, 4 }, { 12, 0, 2, 14, 16, 4 } }),
			furniture());
	public static final Block EXERCISE_BIKE = block("exercise_bike",
			s -> new DecorBlock(s, new double[][] { { 5, 0, 2, 11, 14, 15 } }), furniture());
	public static final Block DUMBBELL_RACK = block("dumbbell_rack",
			s -> new DecorBlock(s, new double[][] { { 0, 0, 4, 16, 13, 12 } }), furniture());
	public static final Block PLATE_TREE = block("plate_tree",
			s -> new DecorBlock(s, new double[][] { { 4, 0, 4, 12, 16, 12 } }), furniture());
	public static final Block GYM_MIRROR = block("gym_mirror",
			s -> new DecorBlock(s, new double[][] { { 0, 0, 14, 16, 16, 16 } }), furniture().sounds(BlockSoundGroup.GLASS));
	public static final Block TREADMILL = block("treadmill",
			s -> new TwoPartBlock(s, false,
					new double[][] { { 2, 0, 3, 14, 4, 16 }, { 2, 0, 3, 3, 16, 5 }, { 13, 0, 3, 14, 16, 5 } },
					new double[][] { { 2, 0, 0, 14, 4, 14 } }),
			furniture());
	public static final Block POWER_RACK = block("power_rack",
			s -> new TwoPartBlock(s, true,
					new double[][] { { 1, 0, 2, 3, 16, 4 }, { 13, 0, 2, 15, 16, 4 }, { 1, 0, 12, 3, 16, 14 }, { 13, 0, 12, 15, 16, 14 },
							{ 0, 0, 0, 16, 1, 16 } },
					new double[][] { { 1, 0, 2, 3, 16, 4 }, { 13, 0, 2, 15, 16, 4 }, { 1, 0, 12, 3, 16, 14 }, { 13, 0, 12, 15, 16, 14 } }),
			furniture());
	public static final Block SHOWER_HEAD = block("shower_head",
			s -> new DecorBlock(s, new double[][] { { 5, 8, 5, 11, 14, 16 } }), furniture().noCollision());
	public static final Block WASHING_MACHINE = block("washing_machine",
			s -> new DecorBlock(s, new double[][] { { 1, 0, 1, 15, 16, 15 } }), furniture());
	public static final Block LAUNDRY_CART = block("laundry_cart",
			s -> new DecorBlock(s, new double[][] { { 2, 0, 3, 14, 12, 13 } }), furniture().sounds(BlockSoundGroup.WOOL));
	public static final Block PUNCHING_BAG = block("punching_bag",
			s -> new TwoPartBlock(s, true, new double[][] { { 3, 0, 3, 13, 16, 13 } }, new double[][] { { 3, 0, 3, 13, 14, 13 } }),
			furniture().sounds(BlockSoundGroup.WOOL));

	/** Steel name plates instead of wooden signs (the text stays). */
	public static final net.minecraft.block.WoodType STEEL_PLATE = new net.fabricmc.fabric.api.object.builder.v1.block.type.WoodTypeBuilder()
			.soundGroup(BlockSoundGroup.METAL).register(CbContent.id("steel_plate"), BlockSetType.IRON);
	public static final Block STEEL_SIGN = Registry.register(Registries.BLOCK, CbContent.id("steel_sign"),
			new net.minecraft.block.SignBlock(STEEL_PLATE, steel().noCollision().strength(1.0F)));
	public static final Block STEEL_WALL_SIGN = Registry.register(Registries.BLOCK, CbContent.id("steel_wall_sign"),
			new net.minecraft.block.WallSignBlock(STEEL_PLATE, steel().noCollision().strength(1.0F).dropsLike(STEEL_SIGN)));
	public static final Item STEEL_SIGN_ITEM = Registry.register(Registries.ITEM, CbContent.id("steel_sign"),
			new net.minecraft.item.SignItem(new Item.Settings().maxCount(16), STEEL_SIGN, STEEL_WALL_SIGN));

	/** Blocks drawn with cutout (holes) or translucency; registered on the client. */
	public static final List<Block> CUTOUT = List.of(CAGED_LAMP, RAZOR_WIRE, STEEL_TABLE, CCTV_CAMERA, SECURITY_MONITOR,
			STEEL_TOILET, STEEL_SINK, BENCH_PRESS, EXERCISE_BIKE, DUMBBELL_RACK, PLATE_TREE, GYM_MIRROR, TREADMILL,
			POWER_RACK, PUNCHING_BAG, STEEL_SEAT, SCRAP_BIN, PRISON_BED, GUARD_BED, INFIRMARY_BED, SHOWER_VALVE, WALL_SWITCH,
			STEEL_LADDER, WASHING_MACHINE, LAUNDRY_CART, SHOWER_HEAD);
	public static final List<Block> TRANSLUCENT = List.of(WIRED_GLASS, WIRED_GLASS_PANE);

	static void init() {
		for (Block b : List.of(CELL_CABINET, SCRAP_BIN, KITCHEN_FRIDGE, TOOL_CABINET, GUARD_LOCKER)) {
			net.minecraft.block.entity.BlockEntityType.BARREL.addSupportedBlock(b);
		}
		net.minecraft.block.entity.BlockEntityType.SIGN.addSupportedBlock(STEEL_SIGN);
		net.minecraft.block.entity.BlockEntityType.SIGN.addSupportedBlock(STEEL_WALL_SIGN);
	}

	/** The gym exercise a machine block stands for, or null if the block is not gym gear. */
	public static String exerciseName(Block block) {
		if (block == BENCH_PRESS) {
			return "Жим лёжа";
		}
		if (block == POWER_RACK) {
			return "Подтягивания";
		}
		if (block == TREADMILL) {
			return "Беговая дорожка";
		}
		if (block == EXERCISE_BIKE) {
			return "Велотренажёр";
		}
		if (block == DUMBBELL_RACK) {
			return "Гантели";
		}
		if (block == PUNCHING_BAG) {
			return "Груша";
		}
		return null;
	}

	/** The base block of a two-block machine (clicking either half counts as the machine). */
	public static BlockPos basePos(BlockView world, BlockPos pos) {
		var state = world.getBlockState(pos);
		if (state.getBlock() instanceof TwoPartBlock two) {
			return two.basePos(state, pos);
		}
		return pos;
	}

	private static Block block(String name, Function<AbstractBlock.Settings, Block> factory, AbstractBlock.Settings settings) {
		Block block = Registry.register(Registries.BLOCK, CbContent.id(name), factory.apply(settings));
		Registry.register(Registries.ITEM, CbContent.id(name), new BlockItem(block, new Item.Settings()));
		BLOCKS.add(block);
		return block;
	}
}
