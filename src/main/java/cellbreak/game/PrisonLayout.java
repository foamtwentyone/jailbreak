package cellbreak.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

/** Key places of a built prison, in absolute world coordinates. */
public class PrisonLayout {
	public static class Cell {
		public final BlockPos spawn;
		public final BlockPos door;
		public final BlockPos desk;
		public final BlockBox interior;

		public Cell(BlockPos spawn, BlockPos door, BlockPos desk, BlockBox interior) {
			this.spawn = spawn;
			this.door = door;
			this.desk = desk;
			this.interior = interior;
		}
	}

	/** Anyone outside this box (horizontally) has escaped. */
	public BlockBox escapeBox;
	public final List<Cell> cells = new ArrayList<>();
	public BlockBox rollCallZone;
	public BlockBox canteenZone;
	public BlockPos solitarySpawn;
	public BlockBox solitaryInterior;
	public BlockPos solitaryDoor;
	public BlockPos guardSpawn;
	public BlockPos guardRoomDoor;
	public BlockPos npcGuardSpawn;
	public final List<BlockPos> patrolPoints = new ArrayList<>();
	public final List<BlockPos> scrapChests = new ArrayList<>();

	public boolean isEscaped(BlockPos pos) {
		return pos.getX() < escapeBox.getMinX() || pos.getX() > escapeBox.getMaxX()
				|| pos.getZ() < escapeBox.getMinZ() || pos.getZ() > escapeBox.getMaxZ();
	}

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.putIntArray("escape", box(escapeBox));
		NbtList cellList = new NbtList();
		for (Cell c : cells) {
			NbtCompound cn = new NbtCompound();
			cn.putLong("spawn", c.spawn.asLong());
			cn.putLong("door", c.door.asLong());
			cn.putLong("desk", c.desk.asLong());
			cn.putIntArray("interior", box(c.interior));
			cellList.add(cn);
		}
		nbt.put("cells", cellList);
		nbt.putIntArray("rollCall", box(rollCallZone));
		nbt.putIntArray("canteen", box(canteenZone));
		nbt.putLong("solitarySpawn", solitarySpawn.asLong());
		nbt.putIntArray("solitaryInterior", box(solitaryInterior));
		nbt.putLong("solitaryDoor", solitaryDoor.asLong());
		nbt.putLong("guardSpawn", guardSpawn.asLong());
		nbt.putLong("guardRoomDoor", guardRoomDoor.asLong());
		nbt.putLong("npcGuardSpawn", npcGuardSpawn.asLong());
		nbt.putLongArray("patrol", patrolPoints.stream().mapToLong(BlockPos::asLong).toArray());
		nbt.putLongArray("scrap", scrapChests.stream().mapToLong(BlockPos::asLong).toArray());
		return nbt;
	}

	public static PrisonLayout fromNbt(NbtCompound nbt) {
		PrisonLayout l = new PrisonLayout();
		l.escapeBox = box(nbt.getIntArray("escape"));
		for (NbtElement e : nbt.getList("cells", NbtElement.COMPOUND_TYPE)) {
			NbtCompound cn = (NbtCompound) e;
			l.cells.add(new Cell(BlockPos.fromLong(cn.getLong("spawn")), BlockPos.fromLong(cn.getLong("door")),
					BlockPos.fromLong(cn.getLong("desk")), box(cn.getIntArray("interior"))));
		}
		l.rollCallZone = box(nbt.getIntArray("rollCall"));
		l.canteenZone = box(nbt.getIntArray("canteen"));
		l.solitarySpawn = BlockPos.fromLong(nbt.getLong("solitarySpawn"));
		l.solitaryInterior = box(nbt.getIntArray("solitaryInterior"));
		l.solitaryDoor = BlockPos.fromLong(nbt.getLong("solitaryDoor"));
		l.guardSpawn = BlockPos.fromLong(nbt.getLong("guardSpawn"));
		l.guardRoomDoor = BlockPos.fromLong(nbt.getLong("guardRoomDoor"));
		l.npcGuardSpawn = BlockPos.fromLong(nbt.getLong("npcGuardSpawn"));
		for (long p : nbt.getLongArray("patrol")) {
			l.patrolPoints.add(BlockPos.fromLong(p));
		}
		for (long p : nbt.getLongArray("scrap")) {
			l.scrapChests.add(BlockPos.fromLong(p));
		}
		return l;
	}

	private static int[] box(BlockBox b) {
		return new int[] { b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ() };
	}

	private static BlockBox box(int[] a) {
		return new BlockBox(a[0], a[1], a[2], a[3], a[4], a[5]);
	}
}
