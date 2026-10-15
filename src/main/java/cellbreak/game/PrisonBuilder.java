package cellbreak.game;

import java.util.ArrayList;
import java.util.List;

import cellbreak.CbContent;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LightBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Builds the default prison. Coordinates in this class are relative to the origin:
 * x/z grow east/south, y = 0 is the level players stand on (the floor is at y = -1).
 * The prison yard spans x 0..47, z 0..39 inside a two block thick perimeter wall.
 */
public class PrisonBuilder {
	public static final int SIZE_X = 48;
	public static final int SIZE_Z = 40;
	public static final int CELLS = 8;

	private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

	private final ServerWorld world;
	private final BlockPos origin;
	private final List<BlockPos> bars = new ArrayList<>();

	private final BlockState wall = CbContent.PRISON_WALL.getDefaultState();
	private final BlockState reinforced = CbContent.REINFORCED_WALL.getDefaultState();
	private final BlockState floor = CbContent.PRISON_FLOOR.getDefaultState();
	private final BlockState air = Blocks.AIR.getDefaultState();

	public PrisonBuilder(ServerWorld world, BlockPos origin) {
		this.world = world;
		this.origin = origin;
	}

	public PrisonLayout build() {
		PrisonLayout layout = new PrisonLayout();

		// Terrain: stone bottom, diggable dirt, prison floor inside, grass outside, air above.
		fill(-12, -7, -12, SIZE_X + 11, -7, SIZE_Z + 11, Blocks.STONE.getDefaultState());
		fill(-12, -6, -12, SIZE_X + 11, -2, SIZE_Z + 11, Blocks.DIRT.getDefaultState());
		fill(-12, -1, -12, SIZE_X + 11, -1, SIZE_Z + 11, Blocks.GRASS_BLOCK.getDefaultState());
		fill(0, -1, 0, SIZE_X - 1, -1, SIZE_Z - 1, floor);
		fill(-12, 0, -12, SIZE_X + 11, 14, SIZE_Z + 11, air);

		// Perimeter wall, two blocks thick, with foundations three blocks deep.
		for (int t = 1; t <= 2; t++) {
			fill(-t, -3, -t, SIZE_X - 1 + t, 4, -t, wall);
			fill(-t, -3, SIZE_Z - 1 + t, SIZE_X - 1 + t, 4, SIZE_Z - 1 + t, wall);
			fill(-t, -3, -t, -t, 4, SIZE_Z - 1 + t, wall);
			fill(SIZE_X - 1 + t, -3, -t, SIZE_X - 1 + t, 4, SIZE_Z - 1 + t, wall);
		}
		layout.areas.add(box(-2, -7, -2, SIZE_X + 1, 10, SIZE_Z + 1));
		layout.breakMinY = origin.getY() - 6;

		buildCells(layout);
		buildSolitary(layout);
		buildGuardRoom(layout);
		buildCanteen(layout);
		buildYard(layout);
		buildWorkshop(layout);
		buildShower(layout);
		buildExerciseYard(layout);
		buildTowers(layout);

		// Invisible light so the prison is never pitch black (above the roofs: vents stay dark).
		BlockState light = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 15);
		for (int x = 2; x < SIZE_X; x += 6) {
			for (int z = 2; z < SIZE_Z; z += 6) {
				if (world.getBlockState(at(x, 6, z)).isAir()) {
					set(x, 6, z, light);
				}
			}
		}

		for (BlockPos pos : bars) {
			world.setBlockState(pos, Block.postProcessState(world.getBlockState(pos), world, pos), FLAGS);
		}

		layout.patrolPoints.add(at(10, 0, 10));
		layout.patrolPoints.add(at(26, 0, 10));
		layout.patrolPoints.add(at(11, 0, 14));
		layout.patrolPoints.add(at(11, 0, 21));
		layout.patrolPoints.add(at(31, 0, 18));
		layout.patrolPoints.add(at(40, 0, 30));
		layout.patrolPoints.add(at(30, 0, 35));
		layout.patrolPoints.add(at(8, 0, 33));
		layout.patrolPoints.add(at(18, 0, 0));
		layout.patrolPoints.add(at(36, 0, 0));
		layout.patrolPoints.add(at(46, 0, 22));
		layout.patrolPoints.add(at(8, 0, 27));
		layout.patrolPoints.add(at(19, 0, 27));
		layout.patrolPoints.add(at(8, 0, 34));
		layout.patrolPoints.add(at(38, 0, 36));
		return layout;
	}

	private void buildCells(PrisonLayout layout) {
		// Eight cells in a row: x 2..34, back wall z = 2, front wall z = 7, ceiling at y = 3, roof at y = 5.
		// Between ceiling and roof runs a vent (1 block high, y = 4) above the back of all cells. Each
		// cell has a vent cover in its ceiling; the vent leaves the block through a cover in the east wall.
		fill(2, 0, 2, 2 + CELLS * 4, 5, 7, wall);
		fill(3, 4, 3, 1 + CELLS * 4, 4, 3, air);
		set(2 + CELLS * 4, 4, 3, CbContent.VENT_COVER.getDefaultState());
		for (int i = 0; i < CELLS; i++) {
			int x0 = 2 + i * 4;
			fill(x0 + 1, 0, 3, x0 + 3, 2, 6, air);
			set(x0 + 2, 3, 3, CbContent.VENT_COVER.getDefaultState());
			bar(x0 + 2, 1, 2);
			door(x0 + 2, 0, 7, Direction.SOUTH);
			bed(x0 + 1, 3);
			set(x0 + 3, 0, 3, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
			set(x0 + 2, 2, 6, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 9));
			layout.cells.add(new PrisonLayout.Cell(at(x0 + 2, 0, 5), at(x0 + 2, 0, 7), at(x0 + 3, 0, 3),
					box(x0 + 1, 0, 3, x0 + 3, 2, 6)));
		}
	}

	private void buildSolitary(PrisonLayout layout) {
		// Reinforced box, also underground so nobody digs out.
		fill(38, -5, 1, 42, 3, 6, reinforced);
		fill(39, 0, 2, 41, 2, 5, air);
		door(40, 0, 6, Direction.SOUTH);
		set(40, 2, 3, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 6));
		layout.solitarySpawn = at(40, 0, 3);
		layout.solitaryInterior = box(39, 0, 2, 41, 2, 5);
		layout.solitaryDoor = at(40, 0, 6);
	}

	private void buildGuardRoom(PrisonLayout layout) {
		fill(40, -5, 9, 46, 3, 14, reinforced);
		fill(41, 0, 10, 45, 2, 13, air);
		door(43, 0, 14, Direction.SOUTH);
		bar(40, 1, 11);
		bar(40, 1, 12);
		set(41, 0, 10, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
		set(43, 2, 11, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 13));
		layout.guardSpawn = at(43, 0, 11);
		layout.guardRoomDoor = at(43, 0, 14);
		layout.npcGuardSpawn = at(43, 0, 16);
	}

	private void buildCanteen(PrisonLayout layout) {
		fill(2, 0, 16, 20, 3, 26, wall);
		fill(3, 0, 17, 19, 3, 25, air);
		fill(10, 0, 16, 11, 1, 16, air);
		for (int z : new int[] { 19, 22 }) {
			fill(5, 0, z, 17, 0, z, Blocks.SPRUCE_SLAB.getDefaultState());
		}
		fill(3, 0, 25, 8, 0, 25, Blocks.SMOOTH_STONE.getDefaultState());
		layout.canteenZone = box(3, 0, 17, 19, 2, 25);
	}

	private void buildYard(PrisonLayout layout) {
		fill(28, -1, 20, 35, -1, 27, Blocks.YELLOW_CONCRETE.getDefaultState());
		layout.rollCallZone = box(28, 0, 20, 35, 2, 27);
		for (int[] p : new int[][] { { 24, 38 }, { 46, 30 }, { 1, 30 } }) {
			set(p[0], 0, p[1], Blocks.CHEST.getDefaultState());
			layout.scrapChests.add(at(p[0], 0, p[1]));
		}
	}

	/** Workshop: wood and metal work, a metal detector in the doorway. */
	private void buildWorkshop(PrisonLayout layout) {
		fill(2, 0, 29, 14, 3, 37, wall);
		fill(3, 0, 30, 13, 3, 36, air);
		fill(8, 0, 29, 8, 1, 29, air);
		set(8, 0, 30, CbContent.DETECTOR.getDefaultState());
		set(5, 0, 35, CbContent.JOB_DESK.getDefaultState());
		set(11, 0, 35, CbContent.JOB_DESK.getDefaultState());
		set(3, 0, 32, Blocks.FURNACE.getDefaultState().with(net.minecraft.block.FurnaceBlock.FACING, Direction.EAST));
		set(3, 0, 33, Blocks.FURNACE.getDefaultState().with(net.minecraft.block.FurnaceBlock.FACING, Direction.EAST));
		set(13, 0, 32, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST));
		set(13, 0, 34, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST));
		fill(4, -1, 31, 12, -1, 34, Blocks.SPRUCE_PLANKS.getDefaultState());
		layout.workZone = box(3, 0, 30, 13, 2, 36);
		layout.workSupplies.add(at(13, 0, 32));
		layout.workSupplies.add(at(13, 0, 34));
	}

	private void buildShower(PrisonLayout layout) {
		fill(16, 0, 29, 22, 3, 35, wall);
		fill(17, 0, 30, 21, 3, 34, air);
		fill(19, 0, 29, 19, 1, 29, air);
		fill(17, -1, 30, 21, -1, 34, Blocks.LIGHT_BLUE_CONCRETE.getDefaultState());
		for (int x = 17; x <= 21; x += 2) {
			set(x, 2, 34, Blocks.IRON_TRAPDOOR.getDefaultState());
		}
		layout.showerZone = box(17, 0, 30, 21, 2, 34);
	}

	/** Exercise yard in the south-east corner, marked with green floor and pull-up bars. */
	private void buildExerciseYard(PrisonLayout layout) {
		fill(26, -1, 30, 46, -1, 38, Blocks.GREEN_CONCRETE.getDefaultState());
		fill(27, -1, 31, 45, -1, 37, Blocks.LIME_CONCRETE.getDefaultState());
		for (int x = 30; x <= 42; x += 6) {
			set(x, 0, 34, Blocks.IRON_BARS.getDefaultState());
			set(x, 1, 34, Blocks.IRON_BARS.getDefaultState());
			set(x + 2, 0, 34, Blocks.IRON_BARS.getDefaultState());
			set(x + 2, 1, 34, Blocks.IRON_BARS.getDefaultState());
			set(x + 1, 2, 34, Blocks.IRON_BARS.getDefaultState());
		}
		layout.yardZone = box(26, 0, 30, 46, 2, 38);
	}

	/** Sniper towers on the four corners of the perimeter wall. */
	private void buildTowers(PrisonLayout layout) {
		// No railing: anything around the platform would block the sniper's view.
		for (int[] c : new int[][] { { -2, -2 }, { SIZE_X + 1, -2 }, { -2, SIZE_Z + 1 }, { SIZE_X + 1, SIZE_Z + 1 } }) {
			fill(c[0] - 1, -3, c[1] - 1, c[0] + 1, 9, c[1] + 1, reinforced);
			fill(c[0] - 2, 10, c[1] - 2, c[0] + 2, 10, c[1] + 2, reinforced);
			for (int[] d : new int[][] { { -2, -2 }, { 2, -2 }, { -2, 2 }, { 2, 2 } }) {
				set(c[0] + d[0], 11, c[1] + d[1], Blocks.LANTERN.getDefaultState());
			}
			layout.sniperPosts.add(at(c[0], 11, c[1]));
		}
	}

	private void bar(int x, int y, int z) {
		set(x, y, z, CbContent.CELL_BARS.getDefaultState());
		bars.add(at(x, y, z));
	}

	private void door(int x, int y, int z, Direction facing) {
		BlockState door = Blocks.IRON_DOOR.getDefaultState().with(DoorBlock.FACING, facing);
		set(x, y, z, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** Bed with its head at (x, zHead) and its foot one block south. */
	private void bed(int x, int zHead) {
		BlockState bed = Blocks.ORANGE_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH);
		set(x, 0, zHead, bed.with(BedBlock.PART, BedPart.HEAD));
		set(x, 0, zHead + 1, bed.with(BedBlock.PART, BedPart.FOOT));
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, state);
				}
			}
		}
	}

	private void set(int x, int y, int z, BlockState state) {
		world.setBlockState(at(x, y, z), state, FLAGS);
	}

	private BlockPos at(int x, int y, int z) {
		return origin.add(x, y, z);
	}

	private BlockBox box(int x1, int y1, int z1, int x2, int y2, int z2) {
		BlockPos a = at(x1, y1, z1);
		BlockPos b = at(x2, y2, z2);
		return BlockBox.create(a, b);
	}
}
