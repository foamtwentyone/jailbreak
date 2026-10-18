package cellbreak.game;

import java.util.HashMap;
import java.util.Map;

import cellbreak.CbContent;
import cellbreak.CbDecor;
import cellbreak.block.DecorBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.server.world.ServerWorld;

/**
 * Gives a prison built from vanilla blocks the realistic look of 0.5: painted cinder block inside
 * (a darker band along the floor), precast concrete outside, polished concrete, tiles and rubber on
 * the floors, steel cell doors, chain-link outdoors, wired glass, fluorescent lights, the cell
 * toilets and sinks, and razor wire along the top of the outer wall and the fences. Every block
 * keeps its role in the game (walls still crack, floors can still be dug, bars can still be cut).
 */
final class Restyle {
	private Restyle() {
	}

	/** Below this the old brick sewer and the foundations stay as they are. */
	private static final int MIN_Y = 62;

	static int run(ServerWorld w, PrisonLayout l) {
		Map<String, Integer> count = new HashMap<>();
		BlockBox all = null;
		for (BlockBox a : l.areas) {
			all = all == null ? a : encompass(all, a);
		}
		if (all == null) {
			return 0;
		}
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int x = all.getMinX(); x <= all.getMaxX(); x++) {
			for (int z = all.getMinZ(); z <= all.getMaxZ(); z++) {
				if (!inside(l, x, z)) {
					continue;
				}
				for (int y = Math.max(all.getMinY(), MIN_Y); y <= all.getMaxY(); y++) {
					p.set(x, y, z);
					BlockState s = w.getBlockState(p);
					if (s.isAir()) {
						continue;
					}
					BlockState n = restyled(w, l, p.toImmutable(), s);
					if (n != null && n != s) {
						if (n.getBlock() instanceof cellbreak.block.StorageBlock) {
							// Drop the old container first, or the chest's contents would spill.
							if (w.getBlockEntity(p) instanceof net.minecraft.inventory.Inventory inv) {
								inv.clear();
							}
							w.removeBlockEntity(p);
						}
						w.setBlockState(p, n, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
						if (n.getBlock() instanceof cellbreak.block.StorageBlock) {
							name(w, p.toImmutable(), n.getBlock());
						}
						count.merge(n.getBlock().getTranslationKey(), 1, Integer::sum);
					}
				}
			}
		}
		// The map's own seats were invisible saddled pigs: the saddles float over the new stools.
		for (BlockBox a : l.areas) {
			for (var e : w.getEntitiesByClass(net.minecraft.entity.passive.PigEntity.class, net.minecraft.util.math.Box.from(a),
					e -> e.getCommandTags().contains("seat"))) {
				e.discard();
			}
		}
		int wire = razorWire(w, l, all);
		count.put("razor_wire", wire);
		return count.values().stream().mapToInt(Integer::intValue).sum();
	}

	private static BlockBox encompass(BlockBox a, BlockBox b) {
		return new BlockBox(Math.min(a.getMinX(), b.getMinX()), Math.min(a.getMinY(), b.getMinY()), Math.min(a.getMinZ(), b.getMinZ()),
				Math.max(a.getMaxX(), b.getMaxX()), Math.max(a.getMaxY(), b.getMaxY()), Math.max(a.getMaxZ(), b.getMaxZ()));
	}

	private static boolean inside(PrisonLayout l, int x, int z) {
		for (BlockBox a : l.areas) {
			if (x >= a.getMinX() && x <= a.getMaxX() && z >= a.getMinZ() && z <= a.getMaxZ()) {
				return true;
			}
		}
		return false;
	}

	private static boolean sky(ServerWorld w, BlockPos p) {
		return w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) <= p.getY();
	}

	/** A wall block with open air under the sky next to it (or above it): the outside of a building. */
	private static boolean exterior(ServerWorld w, BlockPos p) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) {
				continue;
			}
			BlockPos q = p.offset(d);
			if (!w.getBlockState(q).isOpaqueFullCube(w, q) && sky(w, q)) {
				return true;
			}
		}
		return false;
	}

	/** The first block of a wall above a floor: it gets the darker band. */
	private static boolean nearFloor(ServerWorld w, BlockPos p) {
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos q = p.offset(d);
			if (!w.getBlockState(q).isOpaqueFullCube(w, q) && w.getBlockState(q.down()).isOpaqueFullCube(w, q.down())) {
				return true;
			}
		}
		return false;
	}

	private static boolean in(BlockBox box, BlockPos p, int below) {
		return box != null && p.getX() >= box.getMinX() && p.getX() <= box.getMaxX() && p.getZ() >= box.getMinZ()
				&& p.getZ() <= box.getMaxZ() && p.getY() >= box.getMinY() - below && p.getY() <= box.getMaxY();
	}

	private static boolean inCell(PrisonLayout l, BlockPos p) {
		for (PrisonLayout.Cell c : l.cells) {
			if (c.interior.expand(1).contains(p)) {
				return true;
			}
		}
		return false;
	}

	private static BlockState restyled(ServerWorld w, PrisonLayout l, BlockPos p, BlockState s) {
		Block b = s.getBlock();
		if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) {
			return storage(w, l, p, s);
		}
		int job = l.jobStations.indexOf(p);
		if (job >= 0 && "laundry".equals(l.jobKinds.get(job))) {
			Direction f = front(w, p);
			Block to = b == Blocks.WATER_CAULDRON || b == Blocks.CAULDRON ? CbDecor.WASHING_MACHINE : CbDecor.LAUNDRY_CART;
			return to.getDefaultState().with(DecorBlock.FACING, f == null ? Direction.SOUTH : f);
		}
		if (b == Blocks.WHITE_BED && in(l.infirmaryZone, p, 0)) {
			return copy(s, CbDecor.INFIRMARY_BED);
		}
		if (b == Blocks.TRIPWIRE_HOOK && in(l.showerZone, p, 0)) {
			return CbDecor.SHOWER_HEAD.getDefaultState().with(DecorBlock.FACING, s.get(net.minecraft.block.TripwireHookBlock.FACING));
		}
		if (b == Blocks.LEVER) {
			return copy(s, in(l.showerZone, p, 0) ? CbDecor.SHOWER_VALVE : CbDecor.WALL_SWITCH);
		}
		if (b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS) {
			if (exterior(w, p)) {
				return CbContent.CONCRETE_WALL.getDefaultState();
			}
			return (nearFloor(w, p) ? CbContent.PRISON_WALL_BAND : CbContent.PRISON_WALL).getDefaultState();
		}
		if (b == Blocks.CHISELED_STONE_BRICKS) {
			return (exterior(w, p) ? CbContent.CONCRETE_WALL : CbContent.CONCRETE_COLUMN).getDefaultState();
		}
		if (b == Blocks.SMOOTH_STONE) {
			if (sky(w, p.up()) && p.getY() >= 74) {
				return CbContent.ROOF_GRAVEL.getDefaultState();
			}
			if (in(l.gymZone, p, 1)) {
				return CbContent.GYM_FLOOR.getDefaultState();
			}
			if (in(l.canteenZone, p, 1) || in(l.showerZone, p, 1) || in(l.infirmaryZone, p, 1)) {
				return CbContent.FLOOR_TILE.getDefaultState();
			}
			return CbContent.PRISON_FLOOR.getDefaultState();
		}
		if (b == Blocks.POLISHED_ANDESITE) {
			if (in(l.gymZone, p, 1)) {
				return CbContent.GYM_FLOOR.getDefaultState();
			}
			return (sky(w, p.up()) && p.getY() <= 66 ? CbDecor.ASPHALT : CbDecor.DARK_CONCRETE).getDefaultState();
		}
		if (b == Blocks.IRON_BARS) {
			BlockPos top = p;
			while (w.getBlockState(top.up()).isOf(Blocks.IRON_BARS) || w.getBlockState(top.up()).isOf(CbContent.CHAIN_LINK)) {
				top = top.up();
			}
			return copy(s, sky(w, top.up()) ? CbContent.CHAIN_LINK : CbContent.CELL_BARS);
		}
		if (b == Blocks.IRON_DOOR) {
			return copy(s, CbContent.CELL_DOOR);
		}
		if (inCell(l, p)) {
			if (b == Blocks.HOPPER) {
				Direction f = front(w, p);
				return f == null ? CbContent.PRISON_WALL_BAND.getDefaultState()
						: CbDecor.STEEL_TOILET.getDefaultState().with(DecorBlock.FACING, f);
			}
			if (b == Blocks.WATER_CAULDRON || b == Blocks.CAULDRON) {
				Direction f = front(w, p);
				if (f != null) {
					return CbDecor.STEEL_SINK.getDefaultState().with(DecorBlock.FACING, f);
				}
			}
			if (b == Blocks.DROPPER) {
				Direction f = front(w, p);
				return f == null ? CbContent.PRISON_WALL_BAND.getDefaultState()
						: CbDecor.STEEL_SINK.getDefaultState().with(DecorBlock.FACING, f);
			}
			if (b == Blocks.TRIPWIRE_HOOK || b == Blocks.BIRCH_TRAPDOOR) {
				return Blocks.AIR.getDefaultState();
			}
		}
		if (b == Blocks.OBSERVER && l.cameras.contains(p)) {
			Direction f = Direction.byId(l.cameraFacing.get(l.cameras.indexOf(p)));
			if (f.getAxis().isHorizontal()) {
				return CbDecor.CCTV_CAMERA.getDefaultState().with(DecorBlock.FACING, f);
			}
		}
		Block to = SIMPLE.get(b);
		return to == null ? null : copy(s, to);
	}

	/** Which piece of furniture a container becomes: decided by what the game keeps in it. */
	private static BlockState storage(ServerWorld w, PrisonLayout l, BlockPos p, BlockState s) {
		Block to = CbDecor.CELL_CABINET;
		if (l.scrapChests.contains(p)) {
			to = CbDecor.SCRAP_BIN;
		} else if (l.foodChests.contains(p) || in(l.canteenZone, p, 0)) {
			to = CbDecor.KITCHEN_FRIDGE;
		} else if (l.workSupplies.contains(p)) {
			to = CbDecor.TOOL_CABINET;
		} else if (p.equals(l.locker)) {
			to = CbDecor.GUARD_LOCKER;
		}
		Direction f = s.contains(net.minecraft.block.ChestBlock.FACING) ? s.get(net.minecraft.block.ChestBlock.FACING) : front(w, p);
		if (f == null) {
			f = Direction.NORTH;
		}
		if (to == CbDecor.SCRAP_BIN && !w.getBlockState(p.up()).isAir()) {
			f = Direction.UP;
		}
		return to.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, to == CbDecor.SCRAP_BIN ? Direction.UP : f);
	}

	private static void name(ServerWorld w, BlockPos p, Block b) {
		String name = b == CbDecor.SCRAP_BIN ? "Ящик с хламом" : b == CbDecor.KITCHEN_FRIDGE ? "Холодильник"
				: b == CbDecor.TOOL_CABINET ? "Ящик с инструментами" : b == CbDecor.GUARD_LOCKER ? "Шкафчик охраны" : "Тумбочка";
		var be = w.getBlockEntity(p);
		if (be != null) {
			var nbt = be.createNbt(w.getRegistryManager());
			nbt.putString("CustomName", net.minecraft.text.Text.Serialization.toJsonString(net.minecraft.text.Text.literal(name),
					w.getRegistryManager()));
			be.read(nbt, w.getRegistryManager());
			be.markDirty();
		}
	}

	/** The open side of a fixture standing against a wall. */
	private static Direction front(ServerWorld w, BlockPos p) {
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockPos open = p.offset(d);
			BlockPos back = p.offset(d.getOpposite());
			if (w.getBlockState(open).isAir() && !w.getBlockState(back).isAir()) {
				return d;
			}
		}
		return null;
	}

	private static final Map<Block, Block> SIMPLE = new HashMap<>();

	static {
		SIMPLE.put(Blocks.SMOOTH_STONE_SLAB, CbDecor.FLOOR_SLAB);
		SIMPLE.put(Blocks.STONE_BRICK_SLAB, CbDecor.CONCRETE_SLAB);
		SIMPLE.put(Blocks.MOSSY_STONE_BRICK_SLAB, CbDecor.CONCRETE_SLAB);
		SIMPLE.put(Blocks.STONE_BRICK_STAIRS, CbDecor.CONCRETE_STAIRS);
		SIMPLE.put(Blocks.MOSSY_STONE_BRICK_STAIRS, CbDecor.CONCRETE_STAIRS);
		SIMPLE.put(Blocks.STONE_BRICK_WALL, CbDecor.CONCRETE_BARRIER);
		SIMPLE.put(Blocks.POLISHED_ANDESITE_SLAB, CbDecor.DARK_CONCRETE_SLAB);
		SIMPLE.put(Blocks.POLISHED_ANDESITE_STAIRS, CbDecor.DARK_CONCRETE_STAIRS);
		SIMPLE.put(Blocks.IRON_BLOCK, CbDecor.STEEL_PANEL);
		SIMPLE.put(Blocks.GLASS, CbDecor.WIRED_GLASS);
		SIMPLE.put(Blocks.GLASS_PANE, CbDecor.WIRED_GLASS_PANE);
		SIMPLE.put(Blocks.REDSTONE_LAMP, CbDecor.LIGHT_PANEL);
		SIMPLE.put(Blocks.SEA_LANTERN, CbDecor.LIGHT_PANEL);
		SIMPLE.put(Blocks.LANTERN, CbDecor.CAGED_LAMP);
		SIMPLE.put(Blocks.IRON_TRAPDOOR, CbDecor.STEEL_TABLE);
		SIMPLE.put(Blocks.POLISHED_DIORITE_STAIRS, CbDecor.STEEL_SEAT);
		SIMPLE.put(Blocks.COBWEB, Blocks.AIR);
		SIMPLE.put(Blocks.WHITE_BED, CbDecor.PRISON_BED);
		SIMPLE.put(Blocks.BLUE_BED, CbDecor.GUARD_BED);
		SIMPLE.put(Blocks.LADDER, CbDecor.STEEL_LADDER);
		SIMPLE.put(Blocks.GOLD_BLOCK, CbDecor.HAZARD_STEEL);
		SIMPLE.put(Blocks.DISPENSER, CbDecor.STEEL_PANEL);
		SIMPLE.put(Blocks.DROPPER, CbDecor.STEEL_PANEL);
		SIMPLE.put(Blocks.HOPPER, CbDecor.STEEL_PANEL);
		// Leftovers of the map's old redstone machinery.
		SIMPLE.put(Blocks.REDSTONE_WIRE, Blocks.AIR);
		SIMPLE.put(Blocks.REPEATER, Blocks.AIR);
		SIMPLE.put(Blocks.COMPARATOR, Blocks.AIR);
		SIMPLE.put(Blocks.REDSTONE_TORCH, Blocks.AIR);
		SIMPLE.put(Blocks.REDSTONE_WALL_TORCH, Blocks.AIR);
	}

	/** The new block with every property the old one shares with it (facing, half, hinge, sides...). */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static BlockState copy(BlockState from, Block to) {
		BlockState s = to.getDefaultState();
		for (Property prop : from.getProperties()) {
			if (s.contains(prop)) {
				s = s.with(prop, from.get(prop));
			}
		}
		return s;
	}

	/**
	 * Concertina wire on top of the outer wall (the edge of the prison area) and of the outdoor
	 * fences, with gaps where the snipers and the gate guard stand.
	 */
	private static int razorWire(ServerWorld w, PrisonLayout l, BlockBox all) {
		int placed = 0;
		BlockState wire = CbDecor.RAZOR_WIRE.getDefaultState();
		for (int x = all.getMinX(); x <= all.getMaxX(); x++) {
			for (int z = all.getMinZ(); z <= all.getMaxZ(); z++) {
				if (!inside(l, x, z)) {
					continue;
				}
				boolean edge = !inside(l, x - 2, z) || !inside(l, x + 2, z) || !inside(l, x, z - 2) || !inside(l, x, z + 2);
				for (int y = all.getMaxY(); y >= MIN_Y + 3; y--) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = w.getBlockState(p);
					if (s.isAir()) {
						continue;
					}
					BlockPos up = p.up();
					boolean fence = s.isOf(CbContent.CHAIN_LINK) && w.getBlockState(p.down(2)).isOf(CbContent.CHAIN_LINK);
					boolean wall = edge && s.isOf(CbContent.CONCRETE_WALL);
					if ((fence || wall) && w.getBlockState(up).isAir() && sky(w, up) && !nearPost(l, up)) {
						w.setBlockState(up, wire, Block.NOTIFY_LISTENERS);
						placed++;
					}
					break;
				}
			}
		}
		return placed;
	}

	private static boolean nearPost(PrisonLayout l, BlockPos p) {
		for (BlockPos s : l.sniperPosts) {
			if (s.getSquaredDistance(p) < 4 * 4) {
				return true;
			}
		}
		return l.gatePost != null && l.gatePost.getSquaredDistance(p) < 6 * 6;
	}
}
