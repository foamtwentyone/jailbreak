package cellbreak.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

/**
 * Key places of a prison, in absolute world coordinates. Built by {@link PrisonBuilder} or marked
 * by hand on an existing map with the /cellbreak map commands.
 */
public class PrisonLayout {
	/** How far past the prison area a prisoner must get to count as escaped. */
	public static final int ESCAPE_MARGIN = 2;

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

	/** The prison territory including its outer walls (any shape, as a union of boxes). */
	public final List<BlockBox> areas = new ArrayList<>();
	/** Makeshift tools only work inside the areas and not below this height. */
	public int breakMinY = -64;
	public final List<Cell> cells = new ArrayList<>();
	/** Iron doors that are open during the day and locked at lights out (besides cell doors). */
	public final List<BlockPos> dayDoors = new ArrayList<>();
	public BlockBox rollCallZone;
	public BlockBox canteenZone;
	/** Optional zones: parts of the routine without their zone are skipped. */
	public BlockBox workZone;
	public BlockBox yardZone;
	public BlockBox showerZone;
	/** Chests refilled with raw materials (logs, raw iron, coal) when work starts. */
	public final List<BlockPos> workSupplies = new ArrayList<>();
	/** Where snipers stand (on towers or walls). */
	public final List<BlockPos> sniperPosts = new ArrayList<>();
	public BlockPos solitarySpawn;
	public BlockBox solitaryInterior;
	public BlockPos solitaryDoor;
	public BlockPos guardSpawn;
	public BlockPos guardRoomDoor;
	public BlockPos npcGuardSpawn;
	public final List<BlockPos> patrolPoints = new ArrayList<>();
	public final List<BlockPos> scrapChests = new ArrayList<>();

	public boolean isEscaped(BlockPos pos) {
		for (BlockBox a : areas) {
			if (pos.getX() >= a.getMinX() - ESCAPE_MARGIN && pos.getX() <= a.getMaxX() + ESCAPE_MARGIN
					&& pos.getZ() >= a.getMinZ() - ESCAPE_MARGIN && pos.getZ() <= a.getMaxZ() + ESCAPE_MARGIN) {
				return false;
			}
		}
		return true;
	}

	/** Whether makeshift tools may break the block at this position. */
	public boolean canBreakAt(BlockPos pos) {
		if (pos.getY() < breakMinY) {
			return false;
		}
		for (BlockBox a : areas) {
			if (pos.getX() >= a.getMinX() && pos.getX() <= a.getMaxX() && pos.getZ() >= a.getMinZ()
					&& pos.getZ() <= a.getMaxZ()) {
				return true;
			}
		}
		return false;
	}

	/** A box around the whole prison. */
	public BlockBox bounds() {
		BlockBox b = areas.get(0);
		for (BlockBox a : areas) {
			b = b.encompass(a);
		}
		return b;
	}

	/** Names of the things that still have to be marked before a round can start. */
	public List<String> missing() {
		List<String> m = new ArrayList<>();
		if (areas.isEmpty()) {
			m.add("территория (map area)");
		}
		if (cells.isEmpty()) {
			m.add("камеры (map cell)");
		}
		if (rollCallZone == null) {
			m.add("место переклички (map rollcall)");
		}
		if (canteenZone == null) {
			m.add("столовая (map canteen)");
		}
		if (solitarySpawn == null) {
			m.add("карцер (map solitary или map buildsolitary)");
		}
		if (guardSpawn == null) {
			m.add("комната охраны (map guardroom)");
		}
		if (npcGuardSpawn == null) {
			m.add("точка появления NPC-охраны (map npcspawn)");
		}
		return m;
	}

	public NbtCompound toNbt() {
		NbtCompound nbt = new NbtCompound();
		NbtList areaList = new NbtList();
		for (BlockBox a : areas) {
			NbtCompound an = new NbtCompound();
			an.putIntArray("box", box(a));
			areaList.add(an);
		}
		nbt.put("areas", areaList);
		nbt.putInt("breakMinY", breakMinY);
		NbtList cellList = new NbtList();
		for (Cell c : cells) {
			NbtCompound cn = new NbtCompound();
			cn.putLong("spawn", c.spawn.asLong());
			cn.putLong("door", c.door.asLong());
			if (c.desk != null) {
				cn.putLong("desk", c.desk.asLong());
			}
			cn.putIntArray("interior", box(c.interior));
			cellList.add(cn);
		}
		nbt.put("cells", cellList);
		nbt.putLongArray("dayDoors", longs(dayDoors));
		putBox(nbt, "rollCall", rollCallZone);
		putBox(nbt, "canteen", canteenZone);
		putBox(nbt, "work", workZone);
		putBox(nbt, "yard", yardZone);
		putBox(nbt, "shower", showerZone);
		nbt.putLongArray("workSupplies", longs(workSupplies));
		nbt.putLongArray("snipers", longs(sniperPosts));
		putPos(nbt, "solitarySpawn", solitarySpawn);
		putBox(nbt, "solitaryInterior", solitaryInterior);
		putPos(nbt, "solitaryDoor", solitaryDoor);
		putPos(nbt, "guardSpawn", guardSpawn);
		putPos(nbt, "guardRoomDoor", guardRoomDoor);
		putPos(nbt, "npcGuardSpawn", npcGuardSpawn);
		nbt.putLongArray("patrol", longs(patrolPoints));
		nbt.putLongArray("scrap", longs(scrapChests));
		return nbt;
	}

	public static PrisonLayout fromNbt(NbtCompound nbt) {
		PrisonLayout l = new PrisonLayout();
		for (NbtElement e : nbt.getList("areas", NbtElement.COMPOUND_TYPE)) {
			l.areas.add(box(((NbtCompound) e).getIntArray("box")));
		}
		l.breakMinY = nbt.getInt("breakMinY");
		for (NbtElement e : nbt.getList("cells", NbtElement.COMPOUND_TYPE)) {
			NbtCompound cn = (NbtCompound) e;
			l.cells.add(new Cell(BlockPos.fromLong(cn.getLong("spawn")), BlockPos.fromLong(cn.getLong("door")),
					cn.contains("desk") ? BlockPos.fromLong(cn.getLong("desk")) : null, box(cn.getIntArray("interior"))));
		}
		for (long p : nbt.getLongArray("dayDoors")) {
			l.dayDoors.add(BlockPos.fromLong(p));
		}
		l.rollCallZone = getBox(nbt, "rollCall");
		l.canteenZone = getBox(nbt, "canteen");
		l.workZone = getBox(nbt, "work");
		l.yardZone = getBox(nbt, "yard");
		l.showerZone = getBox(nbt, "shower");
		for (long p : nbt.getLongArray("workSupplies")) {
			l.workSupplies.add(BlockPos.fromLong(p));
		}
		for (long p : nbt.getLongArray("snipers")) {
			l.sniperPosts.add(BlockPos.fromLong(p));
		}
		l.solitarySpawn = getPos(nbt, "solitarySpawn");
		l.solitaryInterior = getBox(nbt, "solitaryInterior");
		l.solitaryDoor = getPos(nbt, "solitaryDoor");
		l.guardSpawn = getPos(nbt, "guardSpawn");
		l.guardRoomDoor = getPos(nbt, "guardRoomDoor");
		l.npcGuardSpawn = getPos(nbt, "npcGuardSpawn");
		for (long p : nbt.getLongArray("patrol")) {
			l.patrolPoints.add(BlockPos.fromLong(p));
		}
		for (long p : nbt.getLongArray("scrap")) {
			l.scrapChests.add(BlockPos.fromLong(p));
		}
		return l;
	}

	private static long[] longs(List<BlockPos> list) {
		return list.stream().mapToLong(BlockPos::asLong).toArray();
	}

	private static void putPos(NbtCompound nbt, String key, BlockPos pos) {
		if (pos != null) {
			nbt.putLong(key, pos.asLong());
		}
	}

	private static BlockPos getPos(NbtCompound nbt, String key) {
		return nbt.contains(key) ? BlockPos.fromLong(nbt.getLong(key)) : null;
	}

	private static void putBox(NbtCompound nbt, String key, BlockBox b) {
		if (b != null) {
			nbt.putIntArray(key, box(b));
		}
	}

	private static BlockBox getBox(NbtCompound nbt, String key) {
		return nbt.contains(key) ? box(nbt.getIntArray(key)) : null;
	}

	private static int[] box(BlockBox b) {
		return new int[] { b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ() };
	}

	private static BlockBox box(int[] a) {
		return new BlockBox(a[0], a[1], a[2], a[3], a[4], a[5]);
	}
}
